package com.project.userservice.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.Cookie;

/**
 * 🔒 인증 쿠키는 {@code SameSite=Lax} — 다른 사이트에서 시작한 POST 에 쿠키가 실리지 않아 리프레시 회전·2FA 확인·로그아웃을
 * 남의 사이트가 대신 보내는 CSRF 가 막힌다.
 *
 * <p>🐛 예전: 실제로 쓰이는 {@code createCookie} 는 SameSite 를 적지 않아 브라우저 기본값에 맡겼고(Safari·Firefox 는 None),
 * 부르는 곳 없는 {@code ResponseCookie} 메서드들만 {@code SameSite=None} 을 적고 있었다.
 */
// [JA] 🔒 認証 Cookie は SameSite=Lax — 他サイト発の POST に Cookie が載らず、リフレッシュ・2FA 確認・ログアウトを代行させる CSRF を止める。
@DisplayName("인증 쿠키 SameSite=Lax")
class CookieSameSiteTest {

    @Test
    @DisplayName("🔴 발급하는 쿠키에 SameSite=Lax 가 붙는다")
    void issuedCookieIsLax() {
        Cookie cookie = new JwtUtils(null).createCookie("REFRESH", "token", Duration.ofDays(14));

        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    @DisplayName("🔴 지우는 쿠키도 같은 속성 — 속성이 다르면 브라우저가 다른 쿠키로 볼 수 있다")
    void clearingCookieIsLax() {
        Cookie cookie = new JwtUtils(null).createCookie("REFRESH", null, Duration.ZERO);

        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.getMaxAge()).isZero();
    }

    @Test
    @DisplayName("🔴 Tomcat 이 실제로 내보내는 Set-Cookie 헤더에 SameSite=Lax 가 있다")
    void setCookieHeaderCarriesLax() {
        Cookie cookie = new JwtUtils(null).createCookie("REFRESH", "token", Duration.ofDays(14));

        String header = new Rfc6265CookieProcessor().generateHeader(cookie, null);

        assertThat(header)
                .contains("SameSite=Lax")
                .contains("Secure")
                .contains("HttpOnly")
                .doesNotContain("SameSite=None");
    }

    @Test
    @DisplayName("🔴 쿠키는 JwtUtils.createCookie 한 곳에서만 만든다 — 다른 곳에서 new Cookie 나 SameSite=None 을 쓰지 않는다")
    void cookiesAreBuiltInOnePlace() throws IOException {
        Path main = Path.of("src/main/java");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(main)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.endsWith(Path.of("security/jwt/JwtUtils.java")))
                    .filter(p -> {
                        try {
                            String src = Files.readString(p);
                            return src.contains("new Cookie(") || src.matches("(?s).*sameSite\\(\\s*\"None\".*");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> main.relativize(p).toString())
                    .toList();
        }

        assertThat(offenders).as("쿠키는 jwtUtils.createCookie(…) 로 만든다").isEmpty();
    }
}
