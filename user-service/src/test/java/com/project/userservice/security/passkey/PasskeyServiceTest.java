package com.project.userservice.security.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.project.userservice.model.PasskeyCredential;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.PasskeyCredentialRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 🔴 패스키 — 실제 서명으로 등록 · 로그인하고, 공격의 모양마다 거절되는지 본다(docs/68 §20).
 *
 * <p>저장소 · Redis 는 메모리 흉내, 서명 검증은 진짜(webauthn4j + {@link SoftwareAuthenticator}).
 */
@DisplayName("패스키 — 등록 · 로그인 · 거절")
class PasskeyServiceTest {

    private static final String ORIGIN = "https://shop.example.com";
    private static final String RP_ID = "shop.example.com";

    private final Map<String, String> redisStore = new HashMap<>();
    private final List<PasskeyCredential> rows = new ArrayList<>();
    private PasskeyService service;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doAnswer(inv -> redisStore.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.getAndDelete(anyString())).thenAnswer(inv -> redisStore.remove(inv.<String>getArgument(0)));
        when(ops.get(anyString())).thenAnswer(inv -> redisStore.get(inv.<String>getArgument(0)));

        PasskeyCredentialRepository repo = mock(PasskeyCredentialRepository.class);
        AtomicLong ids = new AtomicLong();
        when(repo.save(any())).thenAnswer(inv -> {
            PasskeyCredential c = inv.getArgument(0);
            if (c.getPasskeyId() == null) {
                c.setPasskeyId(ids.incrementAndGet());
                rows.add(c);
            }
            return c;
        });
        when(repo.findByCredentialId(anyString())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getCredentialId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByUserIdOrderByCreatedAtAsc(anyLong())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getUserId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparing(PasskeyCredential::getPasskeyId)).toList());
        when(repo.countByUserId(anyLong())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getUserId().equals(inv.getArgument(0))).count());
        when(repo.findByPasskeyIdAndUserId(anyLong(), anyLong())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getPasskeyId().equals(inv.getArgument(0)) && r.getUserId().equals(inv.getArgument(1)))
                .findFirst());
        org.mockito.Mockito.doAnswer(inv -> rows.remove(inv.<PasskeyCredential>getArgument(0))).when(repo).delete(any());

        service = new PasskeyService(repo, redis, new SimpleMeterRegistry(), ORIGIN, "", "", "Shop");
        user = User.builder().build();
        user.setUserId(7L);
        user.setEmail("a@example.com");
    }

    private SoftwareAuthenticator device() throws Exception {
        return new SoftwareAuthenticator(ORIGIN, RP_ID);
    }

    @SuppressWarnings("unchecked")
    private String challengeOf(Map<String, Object> options) {
        return (String) options.get("challenge");
    }

    private PasskeyCredential enroll(SoftwareAuthenticator device) throws Exception {
        return service.register(7L, device.register(challengeOf(service.registrationOptions(user))), "내 노트북");
    }

    private Optional<Long> login(SoftwareAuthenticator device, String userHandle) throws Exception {
        var issued = service.authenticationOptions();
        return service.authenticate(issued.pending(), device.assertion(challengeOf(issued.options()), userHandle));
    }

    @Test
    @DisplayName("RP ID · 출처는 frontend.url 에서 — 운영은 공개 도메인")
    void rpFromFrontendUrl() {
        assertThat(service.rpId()).isEqualTo(RP_ID);
        assertThat(service.registrationOptions(user)).containsKey("authenticatorSelection");
    }

    @Test
    @DisplayName("등록하고 그 패스키로 로그인한다 — 공개 키 · 서명 횟수 · 마지막 사용 시각이 남는다")
    void registerThenLogin() throws Exception {
        SoftwareAuthenticator device = device();
        PasskeyCredential saved = enroll(device);

        assertThat(saved.getCredentialId()).isEqualTo(device.credentialIdB64());
        assertThat(saved.getName()).isEqualTo("내 노트북");
        assertThat(saved.getTransports()).isEqualTo("hybrid,internal");
        assertThat(login(device, saved.getUserHandle())).contains(7L);
        assertThat(saved.getSignCount()).isEqualTo(1);
        assertThat(saved.getLastUsedAt()).isNotNull();
        assertThat(service.list(7L)).extracting(PasskeyService.PasskeyView::name).containsExactly("내 노트북");
    }

    @Test
    @DisplayName("🔴 피싱 — 가짜 사이트(다른 출처)에서 받은 서명은 통과하지 못한다")
    void phishingOriginRejected() throws Exception {
        SoftwareAuthenticator device = device();
        PasskeyCredential saved = enroll(device);
        device.origin = "https://shop-example.com.evil.test";
        assertThat(login(device, saved.getUserHandle())).isEmpty();
    }

    @Test
    @DisplayName("🔴 다른 RP ID 로 만든 서명 · 등록은 거절")
    void otherRpIdRejected() throws Exception {
        SoftwareAuthenticator stranger = device();
        stranger.rpId = "evil.test";
        assertThatThrownBy(() -> enroll(stranger)).isInstanceOf(PasskeyException.class);

        SoftwareAuthenticator device = device();
        PasskeyCredential saved = enroll(device);
        device.rpId = "evil.test";
        assertThat(login(device, saved.getUserHandle())).isEmpty();
    }

    @Test
    @DisplayName("🔴 재전송 — 같은 서명은 두 번 통과하지 못하고, 옛 서명은 새 challenge 로도 통과하지 못한다(동기화 패스키 — 서명 횟수 0)")
    void replayRejected() throws Exception {
        // 서명 횟수가 늘 0 인 동기화 패스키 — 복제 검사가 건너뛰어지므로 재전송을 막는 것은 challenge 의 일회성뿐이다.
        SoftwareAuthenticator device = device();
        device.counterless = true;
        PasskeyCredential saved = enroll(device);
        var issued = service.authenticationOptions();
        String assertion = device.assertion(challengeOf(issued.options()), saved.getUserHandle());

        assertThat(service.authenticate(issued.pending(), assertion)).contains(7L);
        assertThat(service.authenticate(issued.pending(), assertion)).isEmpty();
        assertThat(service.authenticate(service.authenticationOptions().pending(), assertion)).isEmpty();
    }

    @Test
    @DisplayName("🔴 사용자 확인(UV) 없는 서명 · 등록은 거절 — 기기만 훔친 사람은 못 들어온다")
    void userVerificationRequired() throws Exception {
        SoftwareAuthenticator noUv = device();
        noUv.userVerified = false;
        assertThatThrownBy(() -> enroll(noUv)).isInstanceOf(PasskeyException.class);

        SoftwareAuthenticator device = device();
        PasskeyCredential saved = enroll(device);
        device.userVerified = false;
        assertThat(login(device, saved.getUserHandle())).isEmpty();
    }

    @Test
    @DisplayName("🔴 user handle 이 다르거나 없으면 거절 · 모르는 패스키는 거절")
    void userHandleAndUnknownCredential() throws Exception {
        SoftwareAuthenticator device = device();
        enroll(device);
        assertThat(login(device, "b3RoZXItYWNjb3VudA")).isEmpty();
        assertThat(login(device, null)).isEmpty();
        assertThat(login(device(), "b3RoZXItYWNjb3VudA")).isEmpty();
    }

    @Test
    @DisplayName("🔴 서명 횟수가 줄면 복제된 키 — 거절")
    void clonedAuthenticatorRejected() throws Exception {
        SoftwareAuthenticator device = device();
        PasskeyCredential saved = enroll(device);
        device.signCount = 5;
        assertThat(login(device, saved.getUserHandle())).contains(7L);   // 6

        var issued = service.authenticationOptions();
        String cloned = device.assertionWithCount(challengeOf(issued.options()), saved.getUserHandle(), 3);
        assertThat(service.authenticate(issued.pending(), cloned)).isEmpty();
    }

    @Test
    @DisplayName("등록 — 옵션 없이(또는 5분 뒤) 오면 거절 · 같은 패스키 두 번 거절 · 한 사람의 패스키는 같은 user handle")
    void registrationRules() throws Exception {
        SoftwareAuthenticator device = device();
        assertThatThrownBy(() -> service.register(7L, device.register("Zm9v"), "x"))
                .isInstanceOf(PasskeyException.class).hasMessageContaining("시간이 지났습니다");

        PasskeyCredential first = enroll(device);
        assertThatThrownBy(() -> enroll(device)).isInstanceOf(PasskeyException.class).hasMessageContaining("이미 등록");

        PasskeyCredential second = enroll(device());
        assertThat(second.getUserHandle()).isEqualTo(first.getUserHandle());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exclude = (List<Map<String, Object>>) service.registrationOptions(user).get("excludeCredentials");
        assertThat(exclude).hasSize(2);
    }

    @Test
    @DisplayName("10개까지 · 이름은 정리해서 저장")
    void limitsAndNames() throws Exception {
        for (int i = 0; i < PasskeyService.MAX_PER_USER; i++) {
            enroll(device());
        }
        assertThatThrownBy(() -> service.registrationOptions(user)).isInstanceOf(PasskeyException.class);
        assertThat(PasskeyService.cleanName("  \u0000\n ")).isEqualTo("패스키");
        assertThat(PasskeyService.cleanName("a".repeat(100))).hasSize(PasskeyService.NAME_MAX);
    }

    @Test
    @DisplayName("🔴 삭제는 내 것만 — 남의 id 는 false(404) · 지운 패스키로는 로그인 못 한다")
    void deleteOwnOnly() throws Exception {
        SoftwareAuthenticator device = device();
        PasskeyCredential saved = enroll(device);
        assertThat(service.delete(8L, saved.getPasskeyId())).isFalse();
        assertThat(service.delete(7L, saved.getPasskeyId())).isTrue();
        assertThat(login(device, saved.getUserHandle())).isEmpty();
    }
}
