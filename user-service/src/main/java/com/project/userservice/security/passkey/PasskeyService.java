package com.project.userservice.security.passkey;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.model.PasskeyCredential;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.PasskeyCredentialRepository;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 패스키(WebAuthn) 등록 · 로그인(docs/68 §20).
 *
 * <h3>왜</h3>
 *
 * <p>§15 ~ §19 는 모두 "비밀번호가 샜을 때" 의 피해를 줄이는 장치다. 패스키는 <b>새어 나갈 비밀번호 자체를 없앤다.</b>
 * 기기가 사이트마다 키 쌍을 만들고, 서버는 공개 키만 갖는다. 로그인은 서버가 낸 난수(challenge)에 기기가 서명하는 것이다.
 * <ul>
 *   <li><b>피싱에 넘어가지 않는다</b> — 서명 안에 브라우저가 적은 출처(origin)와 RP ID 가 들어간다. 가짜 사이트에서 받은 서명은
 *       우리 출처가 아니라서 검증에 실패한다. 6자리 코드(TOTP · 메일)는 사람이 가짜 사이트에 옮겨 적을 수 있지만 이것은 사람이 옮길 수 없다.</li>
 *   <li><b>서버가 털려도 쓸 것이 없다</b> — 공개 키뿐이다. 깰 해시도 없다.</li>
 *   <li><b>크리덴셜 스터핑 대상이 아니다</b> — 다른 사이트에서 샐 값이 없다.</li>
 * </ul>
 *
 * <h3>결정</h3>
 * <ul>
 *   <li>사용자 확인(UV — 지문 · 얼굴 · 기기 PIN)을 <b>요구</b>한다. 기기를 가진 것 + 본인 확인 = 그 자체로 2단계다. 그래서 패스키 로그인은
 *       TOTP 2FA 와 위험 기반 확인(§19)을 건너뛴다.</li>
 *   <li>발견 가능한 자격증명(resident key)을 요구한다 — 로그인 때 이메일을 묻지 않는다(계정 목록을 기기가 보여 준다). 이메일을 먼저 받으면
 *       "이 주소에 패스키가 있나" 가 새어 나간다.</li>
 *   <li>증명(attestation)은 받지 않는다(none) — 어떤 제조사 기기인지 따지지 않는다. 소비자 서비스의 일반적인 선택이다.</li>
 *   <li>challenge 는 Redis 에 5분, <b>한 번만</b>(꺼내면서 지운다) — 같은 서명을 다시 보내도 통과하지 않는다.</li>
 *   <li>서명 횟수(sign count)가 줄면 복제된 키로 보고 거절한다(라이브러리 기본). 동기화 패스키는 늘 0 이라 검사가 건너뛰어진다.</li>
 *   <li>등록은 "최근 로그인" 을 요구한다(호출자 — PasskeyController). 훔친 세션으로 공격자가 자기 패스키를 심으면 비밀번호를 바꿔도 남는 뒷문이 된다.</li>
 * </ul>
 */
// [JA] パスキー(WebAuthn)の登録 · ログイン。漏れるパスワード自体をなくす。署名に origin と RP ID が入るのでフィッシングに負けず、
//      サーバーには公開鍵しかない。UV(生体 · PIN)を必須にし、それ自体を 2 段階とみなして TOTP · リスク確認を飛ばす。
//      challenge は Redis に 5 分、一度だけ。登録は「最近のログイン」を要求する(盗んだセッションで裏口を作らせない)。
@Slf4j
@Service
public class PasskeyService {

    static final String REG_KEY = "passkey:reg:";    // + userId → base64url(challenge) + ":" + userHandle
    static final String AUTH_KEY = "passkey:auth:";  // + sha256(대기 값) → base64url(challenge)
    /** 로그인 challenge 를 이 브라우저에 묶는 대기 쿠키(HttpOnly). 응답 본문에는 싣지 않는다. */
    public static final String COOKIE = "PENDING_PASSKEY";
    public static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    static final int MAX_PER_USER = 10;
    static final int NAME_MAX = 60;
    static final String METRIC = "auth.passkey";

    private static final Base64.Encoder B64U = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64U_DEC = Base64.getUrlDecoder();
    private static final List<PublicKeyCredentialParameters> ALGORITHMS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    private final PasskeyCredentialRepository repository;
    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final WebAuthnManager webAuthn = WebAuthnManager.createNonStrictWebAuthnManager();
    private final AttestedCredentialDataConverter credentialDataConverter =
            new AttestedCredentialDataConverter(new ObjectConverter());
    private final SecureRandom random = new SecureRandom();
    private final String rpId;
    private final String rpName;
    private final Set<Origin> origins;

    public PasskeyService(PasskeyCredentialRepository repository, StringRedisTemplate redis, MeterRegistry meters,
            @Value("${frontend.url:https://localhost:3000}") String frontendUrl,
            @Value("${auth.passkey.rp-id:}") String rpId,
            @Value("${auth.passkey.origins:}") String origins,
            @Value("${auth.passkey.rp-name:Shop}") String rpName) {
        this.repository = repository;
        this.redis = redis;
        this.meters = meters;
        URI front = URI.create(frontendUrl.trim());
        // RP ID 는 화면이 열리는 호스트 — 브라우저는 그 호스트(또는 상위 도메인)가 아니면 패스키를 만들지도 쓰지도 않는다.
        this.rpId = rpId == null || rpId.isBlank() ? front.getHost() : rpId.trim();
        this.origins = (origins == null || origins.isBlank() ? List.of(originOf(front)) : Arrays.asList(origins.split(",")))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).map(Origin::create)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        this.rpName = rpName;
    }

    private static String originOf(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    // ── 등록 ──────────────────────────────────────────────────────────────

    /** 등록 옵션 — navigator.credentials.create() 에 그대로 넘긴다(바이트는 base64url). */
    public Map<String, Object> registrationOptions(User user) {
        Long userId = user.getUserId();
        List<PasskeyCredential> existing = repository.findByUserIdOrderByCreatedAtAsc(userId);
        if (existing.size() >= MAX_PER_USER) {
            throw new PasskeyException("패스키는 " + MAX_PER_USER + "개까지 등록할 수 있습니다. 쓰지 않는 패스키를 먼저 삭제해 주세요.");
        }
        // 한 사람의 패스키는 같은 user handle 을 쓴다 — 기기가 같은 계정으로 알아보고 덮어쓴다(같은 기기에 두 번 생기지 않게).
        String handle = existing.isEmpty() ? B64U.encodeToString(randomBytes(32)) : existing.get(0).getUserHandle();
        String challenge = B64U.encodeToString(randomBytes(32));
        redis.opsForValue().set(REG_KEY + userId, challenge + ":" + handle, CHALLENGE_TTL);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", challenge);
        options.put("rp", Map.of("id", rpId, "name", rpName));
        String label = user.getEmail() == null ? String.valueOf(userId) : user.getEmail();
        options.put("user", Map.of("id", handle, "name", label,
                "displayName", user.getName() == null || user.getName().isBlank() ? label : user.getName()));
        options.put("pubKeyCredParams", List.of(
                Map.of("type", "public-key", "alg", -7),      // ES256
                Map.of("type", "public-key", "alg", -257)));  // RS256(일부 Windows Hello)
        options.put("timeout", CHALLENGE_TTL.toMillis());
        options.put("attestation", "none");
        options.put("authenticatorSelection", Map.of(
                "residentKey", "required", "requireResidentKey", true, "userVerification", "required"));
        List<Map<String, Object>> exclude = new ArrayList<>();
        for (PasskeyCredential c : existing) {
            exclude.add(Map.of("type", "public-key", "id", c.getCredentialId()));
        }
        options.put("excludeCredentials", exclude);
        return options;
    }

    /**
     * 등록을 마친다 — 브라우저가 돌려준 응답(PublicKeyCredential 의 JSON)을 검증하고 공개 키를 저장한다.
     *
     * @throws PasskeyException 시간이 지났다 · 검증 실패 · 이미 등록됨 · 개수 초과
     */
    @Transactional
    public PasskeyCredential register(Long userId, String responseJson, String name) {
        String state = redis.opsForValue().getAndDelete(REG_KEY + userId);
        if (state == null || state.indexOf(':') < 0) {
            throw new PasskeyException("패스키 등록 시간이 지났습니다. 다시 시도해 주세요.");
        }
        String challenge = state.substring(0, state.indexOf(':'));
        String handle = state.substring(state.indexOf(':') + 1);
        if (repository.countByUserId(userId) >= MAX_PER_USER) {
            throw new PasskeyException("패스키는 " + MAX_PER_USER + "개까지 등록할 수 있습니다. 쓰지 않는 패스키를 먼저 삭제해 주세요.");
        }

        RegistrationData data;
        try {
            data = webAuthn.verifyRegistrationResponseJSON(responseJson,
                    new RegistrationParameters(serverProperty(challenge), ALGORITHMS, true, true));
        } catch (RuntimeException e) {
            // 출처 · RP ID · challenge · 서명 · UV 중 하나가 맞지 않다. 원인은 운영 로그에만(응답에는 한 가지 문구).
            meters.counter(METRIC, "event", "register_rejected").increment();
            log.warn("패스키 등록 검증 실패 — userId={} ({})", userId, e.getClass().getSimpleName());
            throw new PasskeyException("패스키를 확인하지 못했습니다. 다시 시도해 주세요.");
        }

        AuthenticatorData<?> authData = data.getAttestationObject().getAuthenticatorData();
        AttestedCredentialData credential = authData.getAttestedCredentialData();
        String credentialId = B64U.encodeToString(credential.getCredentialId());
        if (repository.findByCredentialId(credentialId).isPresent()) {
            throw new PasskeyException("이미 등록된 패스키입니다.");
        }
        Set<AuthenticatorTransport> transports = data.getTransports();
        PasskeyCredential saved = repository.save(PasskeyCredential.builder()
                .userId(userId)
                .credentialId(credentialId)
                .userHandle(handle)
                .attestedCredentialData(credentialDataConverter.convert(credential))
                .signCount(authData.getSignCount())
                .uvInitialized(authData.isFlagUV())
                .backupEligible(authData.isFlagBE())
                .backupState(authData.isFlagBS())
                .transports(transports == null ? null : transports.stream()
                        .map(AuthenticatorTransport::getValue).sorted().collect(Collectors.joining(",")))
                .name(cleanName(name))
                .createdAt(LocalDateTime.now())
                .build());
        meters.counter(METRIC, "event", "registered").increment();
        log.info("패스키 등록 — userId={}, passkeyId={}", userId, saved.getPasskeyId());
        return saved;
    }

    static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (n.isEmpty()) {
            n = "패스키";
        }
        return n.length() > NAME_MAX ? n.substring(0, NAME_MAX) : n;
    }

    // ── 로그인 ────────────────────────────────────────────────────────────

    /** 로그인 옵션과, 그 challenge 를 이 브라우저에 묶을 대기 값(쿠키로만 보낸다). */
    public record LoginOptions(String pending, Map<String, Object> options) {
    }

    public LoginOptions authenticationOptions() {
        String pending = HexFormat.of().formatHex(randomBytes(32));
        String challenge = B64U.encodeToString(randomBytes(32));
        redis.opsForValue().set(AUTH_KEY + sha256(pending), challenge, CHALLENGE_TTL);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", challenge);
        options.put("rpId", rpId);
        options.put("timeout", CHALLENGE_TTL.toMillis());
        options.put("userVerification", "required");
        // 비워 둔다 — 기기가 이 사이트의 계정 목록을 보여 준다(이메일을 먼저 묻지 않는다).
        options.put("allowCredentials", List.of());
        return new LoginOptions(pending, options);
    }

    /**
     * 로그인 서명을 검증한다.
     *
     * @return 통과하면 사용자 id. 시간 초과 · 모르는 패스키 · 다른 계정의 user handle · 서명 · 출처 · UV · 복제 의심은 모두 빈 값
     *         (이유를 응답으로 구분해 주지 않는다 — 운영 지표 · 로그에만)
     */
    @Transactional
    public Optional<Long> authenticate(String pending, String responseJson) {
        if (pending == null || pending.isBlank() || responseJson == null) {
            return reject("no_pending");
        }
        // 꺼내면서 지운다 — 같은 서명을 다시 보내도(재전송) 두 번째는 challenge 가 없다.
        String challenge = redis.opsForValue().getAndDelete(AUTH_KEY + sha256(pending));
        if (challenge == null) {
            return reject("expired");
        }
        AuthenticationData parsed;
        try {
            parsed = webAuthn.parseAuthenticationResponseJSON(responseJson);
        } catch (RuntimeException e) {
            return reject("malformed");
        }
        Optional<PasskeyCredential> found = repository.findByCredentialId(B64U.encodeToString(parsed.getCredentialId()));
        if (found.isEmpty()) {
            // 삭제했거나 다른 사이트 · 다른 환경의 패스키.
            return reject("unknown_credential");
        }
        PasskeyCredential row = found.get();
        // 발견 가능한 자격증명 로그인에서는 기기가 user handle 을 꼭 돌려준다 — 저장한 값과 같아야 한다.
        byte[] handle = parsed.getUserHandle();
        if (handle == null || !MessageDigest.isEqual(handle, B64U_DEC.decode(row.getUserHandle()))) {
            return reject("user_handle_mismatch");
        }

        CredentialRecordImpl record = new CredentialRecordImpl(new NoneAttestationStatement(),
                row.isUvInitialized(), row.getBackupEligible(), row.getBackupState(), row.getSignCount(),
                credentialDataConverter.convert(row.getAttestedCredentialData()), null, null, null, null);
        try {
            webAuthn.verify(parsed, new AuthenticationParameters(serverProperty(challenge), record,
                    List.of(parsed.getCredentialId()), true, true));
        } catch (RuntimeException e) {
            // 출처(피싱 사이트) · RP ID · 서명 · UV 없음 · 서명 횟수 감소(복제) 등.
            log.warn("패스키 로그인 검증 실패 — passkeyId={} ({})", row.getPasskeyId(), e.getClass().getSimpleName());
            return reject("verification_failed");
        }

        AuthenticatorData<?> authData = parsed.getAuthenticatorData();
        row.setSignCount(authData.getSignCount());
        row.setBackupState(authData.isFlagBS());
        row.setLastUsedAt(LocalDateTime.now());
        repository.save(row);
        meters.counter(METRIC, "event", "login").increment();
        return Optional.of(row.getUserId());
    }

    private Optional<Long> reject(String reason) {
        meters.counter(METRIC, "event", "login_rejected", "reason", reason).increment();
        return Optional.empty();
    }

    // ── 관리 ──────────────────────────────────────────────────────────────

    /** 화면용 — 공개 키 · 자격증명 id 는 내보내지 않는다. */
    public record PasskeyView(Long id, String name, LocalDateTime createdAt, LocalDateTime lastUsedAt, boolean synced) {
    }

    @Transactional(readOnly = true)
    public List<PasskeyView> list(Long userId) {
        return repository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(c -> new PasskeyView(c.getPasskeyId(), c.getName(), c.getCreatedAt(), c.getLastUsedAt(),
                        Boolean.TRUE.equals(c.getBackupEligible())))
                .toList();
    }

    /** @return 내 것이고 지웠으면 true — 남의 것 · 없는 것은 구별하지 않는다(404). */
    @Transactional
    public boolean delete(Long userId, Long passkeyId) {
        Optional<PasskeyCredential> mine = repository.findByPasskeyIdAndUserId(passkeyId, userId);
        mine.ifPresent(repository::delete);
        return mine.isPresent();
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private ServerProperty serverProperty(String challenge) {
        return ServerProperty.builder()
                .origins(origins)
                .rpId(rpId)
                .challenge(new DefaultChallenge(B64U_DEC.decode(challenge)))
                .build();
    }

    String rpId() {
        return rpId;
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
