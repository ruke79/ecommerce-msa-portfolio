package com.project.userservice.handler;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.project.userservice.constants.TokenType;
import com.project.userservice.dto.CustomOAuth2User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.service.RefreshTokenService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 소셜 로그인 마무리 — 세션을 심고 프론트엔드로 돌려보낸다.
 *
 * <p>핵심 결정: <b>정상 로그인과 같은 REFRESH 쿠키 하나만 심는다.</b> 액세스 토큰은
 * 만들지 않는다. 이 앱의 세션 복구 경로가 이미 그렇게 생겼기 때문이다 — SPA 는 처음
 * 뜰 때(STATUS.PENDING) {@code GET /api/token/refresh} 를 부르고, REFRESH 쿠키가
 * 유효하면 access 토큰을 헤더로 받아 세션을 복구한다(TokenRefresher.js). 리다이렉트로
 * 돌아온 페이지도 "처음 뜬 페이지"라 같은 경로가 그대로 동작한다.
 *
 * <p>예전 버전은 세 가지가 어긋나 있었다:
 * <ul>
 *   <li>{@code findByEmail(customUserDetails.getUsername())} — getUsername() 은
 *       "google 12345" 를 돌려주므로 이메일 조회가 <b>항상 실패</b>했다. 모든 소셜
 *       로그인이 user_not_found 로 끝났다
 *   <li>"OAUTH2" 라는 이름의 쿠키에 액세스 토큰을 담았다 — 게이트웨이도 프론트도
 *       그 쿠키를 읽지 않는다. 심은 사람만 아는 쿠키였다
 *   <li>만료가 60*60*60 = <b>216,000초(2.5일)</b> — 60분을 의도한 오타로 보인다
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final com.project.userservice.security.PendingTwoFactor pendingTwoFactor;

    @Value("${frontend.url}")
    private String frontendUrl;

    /** 2FA 확인 대기 쿠키 — verify 엔드포인트(AuthController)와 이름을 공유한다. */
    public static final String PENDING_2FA_COOKIE = com.project.userservice.security.PendingTwoFactor.COOKIE;
    /** 5분 — TOTP 한두 번 넣어 볼 시간이면 충분하고, 방치된 브라우저에 오래 남지 않는다. */
    public static final long PENDING_2FA_TTL_MS = com.project.userservice.security.PendingTwoFactor.TTL_MS;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

        CustomOAuth2User principal = (CustomOAuth2User) authentication.getPrincipal();

        User user = userRepository.findByEmail(principal.getEmail()).orElse(null);
        if (user == null) {
            // CustomOAuth2UserService 가 방금 만들었거나 찾았어야 한다. 여기 왔다는 것은
            // 트랜잭션·타이밍 문제라는 뜻이므로 명확히 실패시킨다.
            log.error("소셜 인증은 통과했는데 사용자 레코드가 없다 — email 기준 조회 실패");
            response.sendRedirect(frontendUrl + "/signin?error=oauth_user_not_found");
            return;
        }

        if (user.isTwoFactorEnabled()) {
            // 2FA 계정은 여기서 세션을 주지 않는다 — 주면 소셜 버튼이 2FA 우회로가 된다.
            // 대신 5분짜리 보류 토큰을 httpOnly 쿠키로 심고 2FA 입력 화면으로 보낸다.
            // 코드 확인(/api/auth/public/oauth2/verify-2fa)이 통과해야 REFRESH 가 발급된다.
            //
            // 토큰을 URL 이 아니라 쿠키로 나르는 이유: URL 의 토큰은 브라우저 히스토리·
            // 리퍼러·프록시 로그에 남는다. httpOnly 라 JS 도 못 읽는다 — 프론트는
            // 쿠키의 존재조차 모른 채 코드만 보내면 된다.
            //
            // 🔴 예전에는 이 값이 액세스 토큰과 같은 키로 서명한 보통 JWT(uid 포함)였다 — 게이트웨이에 Bearer 로
            //    보내면 통과해 2FA 가 무의미했다. 이제 별도 키로 서명한다(PendingTwoFactor 주석).
            String pending = pendingTwoFactor.issue(user.getUserId());
            response.addCookie(jwtUtils.createCookie(PENDING_2FA_COOKIE, pending,
                    java.time.Duration.ofMillis(PENDING_2FA_TTL_MS)));
            log.info("소셜 로그인 — 2FA 확인 대기. userId={}", user.getUserId());
            response.sendRedirect(frontendUrl + "/signin?oauth2fa=1");
            return;
        }

        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getUserId());
        response.addCookie(jwtUtils.createCookie(
                TokenType.REFRESH.getType(), refreshToken.getToken(), jwtUtils.refreshTokenLifetime()));

        log.info("소셜 로그인 성공. userId={}, method={}", user.getUserId(), user.getSignUpMethod());
        response.sendRedirect(frontendUrl);
    }
}
