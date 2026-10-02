package com.project.userservice.security.passkey;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.common.exception.ClientMessage;
import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.DeviceIdentity;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.service.DeviceRecognitionService;
import com.project.userservice.service.RefreshTokenService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 패스키 등록 · 관리(로그인 필요)와 패스키 로그인(공개) — docs/68 §20.
 *
 * <p>신원은 토큰에서만 온다 — userId 를 경로 · 본문으로 받지 않는다. 남의 패스키 id 는 404(존재 여부를 흘리지 않는다).
 */
// [JA] パスキーの登録 · 管理(要ログイン)とパスキーログイン(公開)。身元はトークンからだけ。他人のパスキー id は 404。
@Slf4j
@RestController
@RequiredArgsConstructor
public class PasskeyController {

    private final PasskeyService passkeyService;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtUtils jwtUtils;
    private final DeviceRecognitionService deviceRecognitionService;
    private final SecurityAlertProducer securityAlertProducer;

    /** 등록 전 "최근 로그인" 창 — 탈퇴(§16)와 같은 생각. */
    @Value("${auth.passkey.reauth-window:PT10M}")
    private Duration reauthWindow = Duration.ofMinutes(10);

    // ── 관리(로그인 필요) ────────────────────────────────────────────────

    @GetMapping("/api/user/passkeys")
    ResponseEntity<?> list(@AuthenticationPrincipal UserDetails principal) {
        Long userId = idOf(principal);
        if (userId == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(passkeyService.list(userId));
    }

    /**
     * 등록 옵션. 🔒 최근 로그인을 요구한다 — 훔친 세션(열려 있는 공용 PC · 탈취된 쿠키)으로 공격자가 <b>자기</b> 패스키를 심으면,
     * 주인이 비밀번호를 바꾸고 모든 기기를 로그아웃시켜도 그 패스키로 다시 들어온다. 비밀번호 변경이 지우지 못하는 뒷문이다.
     */
    @PostMapping("/api/user/passkeys/options")
    ResponseEntity<?> registrationOptions(@AuthenticationPrincipal UserDetails principal,
            @CookieValue(name = "REFRESH", required = false) String refresh) {   // = TokenType.REFRESH
        Long id = idOf(principal);
        User user = id == null ? null : userRepository.findById(id).orElse(null);
        if (user == null) {
            return unauthorized();
        }
        if (!recentlyAuthenticated(user, refresh)) {
            return reauthRequired();
        }
        try {
            return ResponseEntity.ok(passkeyService.registrationOptions(user));
        } catch (PasskeyException e) {
            return ResponseEntity.badRequest().body(Map.of("message", ClientMessage.of(e)));
        }
    }

    /** 등록 — 본문 {@code {"name": "...", "credential": PublicKeyCredential JSON}}. */
    @PostMapping("/api/user/passkeys")
    ResponseEntity<?> register(@AuthenticationPrincipal UserDetails principal,
            @CookieValue(name = "REFRESH", required = false) String refresh,
            @RequestBody JsonNode body) {
        Long id = idOf(principal);
        User user = id == null ? null : userRepository.findById(id).orElse(null);
        if (user == null) {
            return unauthorized();
        }
        // 옵션과 등록 사이(최대 5분)에 창이 닫혔을 수도 있다 — 저장하는 순간에 다시 본다.
        if (!recentlyAuthenticated(user, refresh)) {
            return reauthRequired();
        }
        try {
            var saved = passkeyService.register(user.getUserId(), body.path("credential").toString(),
                    body.path("name").asText(""));
            // 본인이 한 일이 아니면 누군가 이 계정에 들어올 새 열쇠를 만든 것이다 — 수신 설정과 무관하게 알린다.
            securityAlertProducer.publishAccountEvent(user.getUserId(), user.getEmail(),
                    SecurityAlertProducer.ALERT_PASSKEY_ADDED, null);
            return ResponseEntity.status(HttpStatus.CREATED).body(new PasskeyService.PasskeyView(saved.getPasskeyId(),
                    saved.getName(), saved.getCreatedAt(), saved.getLastUsedAt(), Boolean.TRUE.equals(saved.getBackupEligible())));
        } catch (PasskeyException e) {
            return ResponseEntity.badRequest().body(Map.of("message", ClientMessage.of(e)));
        }
    }

    @DeleteMapping("/api/user/passkeys/{passkeyId}")
    ResponseEntity<?> delete(@AuthenticationPrincipal UserDetails principal, @PathVariable("passkeyId") Long passkeyId) {
        Long id = idOf(principal);
        User user = id == null ? null : userRepository.findById(id).orElse(null);
        if (user == null) {
            return unauthorized();
        }
        if (!passkeyService.delete(user.getUserId(), passkeyId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "패스키를 찾을 수 없습니다."));
        }
        securityAlertProducer.publishAccountEvent(user.getUserId(), user.getEmail(),
                SecurityAlertProducer.ALERT_PASSKEY_REMOVED, null);
        return ResponseEntity.noContent().build();
    }

    // ── 로그인(공개) ─────────────────────────────────────────────────────

    /** 로그인 옵션 — challenge 를 PENDING_PASSKEY(HttpOnly) 쿠키로 이 브라우저에 묶는다. */
    @PostMapping("/api/auth/public/passkey/options")
    ResponseEntity<?> loginOptions(HttpServletResponse response) {
        var issued = passkeyService.authenticationOptions();
        response.addCookie(jwtUtils.createCookie(PasskeyService.COOKIE, issued.pending(), PasskeyService.CHALLENGE_TTL));
        return ResponseEntity.ok(issued.options());
    }

    /**
     * 패스키 로그인 — 본문은 navigator.credentials.get() 결과의 JSON.
     *
     * <p>통과하면 비밀번호 로그인의 끝과 같다: 기기 기록(새 기기 알림) · REFRESH 쿠키. 2FA(TOTP)와 위험 기반 확인(§19)은 거치지 않는다 —
     * 기기 소유 + 사용자 확인(UV)이 이미 2단계이고, 피싱 · 스터핑이 통하지 않는 수단이다.
     */
    @PostMapping("/api/auth/public/passkey/login")
    ResponseEntity<?> login(@RequestBody String credentialJson,
            @CookieValue(name = PasskeyService.COOKIE, required = false) String pending,
            HttpServletRequest request, HttpServletResponse response) {
        response.addCookie(jwtUtils.createCookie(PasskeyService.COOKIE, null, Duration.ZERO));
        var userId = passkeyService.authenticate(pending, credentialJson);
        if (userId.isEmpty()) {
            return failed();
        }
        User user = userRepository.findById(userId.get()).orElse(null);
        // 정지 · 탈퇴 계정은 패스키가 있어도 들어오지 못한다. 실패 사유는 구분하지 않는다.
        if (user == null || !user.isEnabled() || !user.isAccountNonLocked()) {
            return failed();
        }

        try {
            var recognition = deviceRecognitionService.recognize(user.getUserId(), DeviceIdentity.tokenFrom(request),
                    request.getHeader("User-Agent"), DeviceIdentity.clientIp(request));
            if (recognition.cookieToken() != null) {
                response.addCookie(jwtUtils.createCookie(DeviceIdentity.COOKIE_NAME, recognition.cookieToken(),
                        Duration.ofSeconds(DeviceIdentity.COOKIE_MAX_AGE_SECONDS)));
            }
            if (recognition.shouldAlert()) {
                securityAlertProducer.publishNewDevice(user.getUserId(), user.getEmail(), recognition.device());
            }
        } catch (RuntimeException e) {
            log.error("기기 기록에 실패했습니다 — 로그인은 계속합니다: {}", e.getClass().getSimpleName());
        }

        var refreshToken = refreshTokenService.createRefreshToken(user.getUserId());
        response.addCookie(jwtUtils.createCookie(com.project.userservice.constants.TokenType.REFRESH.getType(),
                refreshToken.getToken(), jwtUtils.refreshTokenLifetime()));
        log.info("패스키 로그인 — userId={}", user.getUserId());
        return ResponseEntity.ok(Map.of("message", "ok"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private boolean recentlyAuthenticated(User user, String refresh) {
        var authTime = refreshTokenService.authTimeOf(refresh, user);
        long now = java.time.Instant.now().getEpochSecond();
        return authTime.isPresent() && now - authTime.getAsLong() <= reauthWindow.toSeconds();
    }

    private ResponseEntity<?> reauthRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", "reauth_required",
                "message", "보안을 위해 다시 로그인한 뒤 " + reauthWindow.toMinutes() + "분 안에 등록해 주세요."));
    }

    private static ResponseEntity<?> failed() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "패스키로 로그인하지 못했습니다. 다시 시도하거나 다른 방법으로 로그인해 주세요."));
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "로그인이 필요합니다."));
    }

    // 엔티티(User)를 돌려주는 도우미는 두지 않는다 — 컨트롤러 메서드의 반환형을 검사하는 가드(TotpSecretExposureTest)가 있다.
    private static Long idOf(UserDetails principal) {
        return principal instanceof UserDetailsImpl impl ? impl.getId() : null;
    }
}
