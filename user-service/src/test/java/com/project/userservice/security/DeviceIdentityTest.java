package com.project.userservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.Cookie;

/**
 * 기기 식별에 쓰는 순수 계산.
 *
 * <p>여기가 틀리면 <b>조용히</b> 틀린다 — 알림이 안 나가거나(계정이 털려도 모른다),
 * 매번 나가거나(소음이 되어 진짜 경고까지 무시된다), 남의 IP 가 필요 이상으로 노출된다.
 * 셋 다 예외도 로그도 없이 일어난다.
 */
@DisplayName("기기 식별")
class DeviceIdentityTest {

    @Nested
    @DisplayName("토큰")
    class Tokens {

        @Test
        @DisplayName("부를 때마다 다른 값이다 — 같으면 모든 브라우저가 한 기기가 된다")
        void tokensAreUnique() {
            assertThat(DeviceIdentity.newToken()).isNotEqualTo(DeviceIdentity.newToken());
        }

        /** 쿠키 값이라 URL 안전 문자만 써야 한다. `+` `/` `=` 가 섞이면 값이 잘린다. */
        @Test
        @DisplayName("쿠키에 그대로 담을 수 있는 문자만 쓴다")
        void tokenIsCookieSafe() {
            assertThat(DeviceIdentity.newToken()).matches("[A-Za-z0-9_-]+");
        }

        @Test
        @DisplayName("추측하기 어려울 만큼 길다")
        void tokenIsLongEnough() {
            // 32바이트 → base64url 43자.
            assertThat(DeviceIdentity.newToken().length()).isGreaterThanOrEqualTo(40);
        }

        /**
         * 🔒 이 값은 인증 수단이 아니지만 <b>보안 알림을 끌 수 있는 값</b>이다. DB 가
         * 새더라도 그 능력이 함께 새지 않게 해시만 저장한다.
         */
        @Test
        @DisplayName("해시는 원문을 담지 않는다")
        void hashDoesNotContainTheToken() {
            String token = DeviceIdentity.newToken();

            assertThat(DeviceIdentity.hash(token)).doesNotContain(token);
        }

        @Test
        @DisplayName("같은 토큰은 항상 같은 해시 — 아니면 매 로그인이 새 기기다")
        void hashIsStable() {
            assertThat(DeviceIdentity.hash("abc")).isEqualTo(DeviceIdentity.hash("abc"));
        }

        @Test
        @DisplayName("다른 토큰은 다른 해시")
        void differentTokensDiffer() {
            assertThat(DeviceIdentity.hash("abc")).isNotEqualTo(DeviceIdentity.hash("abd"));
        }

        @Test
        @DisplayName("SHA-256 hex 64자다")
        void hashIsSha256Hex() {
            assertThat(DeviceIdentity.hash("abc")).hasSize(64).matches("[0-9a-f]+");
        }

        @Test
        @DisplayName("빈 값은 해시하지 않는다")
        void blankHashesToNull() {
            assertThat(DeviceIdentity.hash(null)).isNull();
            assertThat(DeviceIdentity.hash("  ")).isNull();
        }
    }

    @Nested
    @DisplayName("쿠키 읽기")
    class ReadingTheCookie {

        @Test
        @DisplayName("기기 쿠키를 찾아낸다")
        void findsTheCookie() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("REFRESH", "r"), new Cookie(DeviceIdentity.COOKIE_NAME, "d"));

            assertThat(DeviceIdentity.tokenFrom(request)).isEqualTo("d");
        }

        @Test
        @DisplayName("쿠키가 없으면 null — 새 기기로 본다")
        void noCookieMeansNull() {
            assertThat(DeviceIdentity.tokenFrom(new MockHttpServletRequest())).isNull();
            assertThat(DeviceIdentity.tokenFrom(null)).isNull();
        }

        @Test
        @DisplayName("빈 쿠키도 없는 것으로 본다")
        void blankCookieMeansNull() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie(DeviceIdentity.COOKIE_NAME, ""));

            assertThat(DeviceIdentity.tokenFrom(request)).isNull();
        }
    }

    /**
     * User-Agent → 표시용 라벨.
     *
     * <p>원문을 저장하지 않는 이유: 지문 가치가 있는 값이고(확장·버전까지 담긴다),
     * 사용자에게 필요한 것은 "어느 브라우저, 어느 운영체제였나" 뿐이다.
     */
    @Nested
    @DisplayName("기기 라벨")
    class Labels {

        private String of(String ua) {
            return DeviceIdentity.label(ua);
        }

        @Test
        @DisplayName("Chrome · Windows")
        void chromeOnWindows() {
            assertThat(of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"))
                    .isEqualTo("Chrome · Windows");
        }

        /**
         * 🔴 순서가 중요하다. Edge 의 UA 에는 "Chrome" 이 들어 있어서, Chrome 을 먼저
         * 보면 모든 Edge 가 Chrome 으로 보인다 — 사용자는 자기 기기를 못 알아본다.
         */
        @Test
        @DisplayName("Edge 를 Chrome 으로 읽지 않는다 — Edge 의 UA 에 Chrome 이 들어 있다")
        void edgeIsNotChrome() {
            assertThat(of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36 Edg/120.0"))
                    .isEqualTo("Edge · Windows");
        }

        @Test
        @DisplayName("Chrome 을 Safari 로 읽지 않는다 — Chrome 의 UA 에 Safari 가 들어 있다")
        void chromeIsNotSafari() {
            assertThat(of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"))
                    .isEqualTo("Chrome · macOS");
        }

        @Test
        @DisplayName("진짜 Safari 는 Safari 로 읽는다")
        void safariIsSafari() {
            assertThat(of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Safari/605.1.15"))
                    .isEqualTo("Safari · macOS");
        }

        @Test
        @DisplayName("iPhone")
        void iphone() {
            assertThat(of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"))
                    .isEqualTo("Safari · iOS");
        }

        @Test
        @DisplayName("Android 의 삼성 브라우저")
        void samsungOnAndroid() {
            assertThat(of("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "SamsungBrowser/23.0 Chrome/115.0 Mobile Safari/537.36"))
                    .isEqualTo("Samsung Internet · Android");
        }

        @Test
        @DisplayName("Firefox")
        void firefox() {
            assertThat(of("Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0"))
                    .isEqualTo("Firefox · Linux");
        }

        /** 모르는 UA 때문에 목록에 빈 줄이 생기면 안 된다. */
        @Test
        @DisplayName("모르는 UA 도 읽을 수 있는 문자열을 준다")
        void unknownUaStillGetsALabel() {
            assertThat(of("curl/8.0")).isNotBlank();
        }

        @Test
        @DisplayName("UA 가 없으면 null — 라벨 없는 기기로 남는다")
        void missingUaIsNull() {
            assertThat(of(null)).isNull();
            assertThat(of("  ")).isNull();
        }

        /** label 컬럼은 varchar(120) 이다. 넘치면 insert 가 통째로 실패한다. */
        @Test
        @DisplayName("컬럼 길이를 넘지 않는다")
        void labelFitsTheColumn() {
            assertThat(of("Chrome ".repeat(200)).length()).isLessThanOrEqualTo(120);
        }
    }

    /**
     * IP 마스킹.
     *
     * <p>전체를 보여 주면 사용자 자신에게도, 그 화면을 어깨 너머로 보는 사람에게도 필요
     * 이상의 정보가 된다. 가린 값만으로도 "집에서 들어온 것 같다 / 모르는 곳이다" 는
     * 판단이 된다.
     */
    @Nested
    @DisplayName("IP 마스킹")
    class MaskingIp {

        @Test
        @DisplayName("IPv4 는 마지막 옥텟을 가린다")
        void ipv4() {
            assertThat(DeviceIdentity.maskIp("203.0.113.42")).isEqualTo("203.0.113.xxx");
        }

        @Test
        @DisplayName("마지막 옥텟이 결과에 남지 않는다")
        void lastOctetIsGone() {
            assertThat(DeviceIdentity.maskIp("203.0.113.42")).doesNotContain("42");
        }

        @Test
        @DisplayName("IPv6 는 앞 두 그룹만 남긴다")
        void ipv6() {
            assertThat(DeviceIdentity.maskIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
                    .isEqualTo("2001:0db8:xxxx");
        }

        /** 형식을 모르겠으면 통째로 가린다 — 반쯤 아는 값을 흘리는 것보다 낫다. */
        @Test
        @DisplayName("형식을 모르면 통째로 가린다")
        void unknownFormatIsFullyMasked() {
            assertThat(DeviceIdentity.maskIp("not-an-ip")).isEqualTo("xxx");
            assertThat(DeviceIdentity.maskIp("1.2.3")).isEqualTo("xxx");
        }

        @Test
        @DisplayName("없으면 null")
        void blankIsNull() {
            assertThat(DeviceIdentity.maskIp(null)).isNull();
            assertThat(DeviceIdentity.maskIp("  ")).isNull();
        }
    }

    @Nested
    @DisplayName("클라이언트 IP")
    class ClientIp {

        /** 게이트웨이를 거치면 getRemoteAddr() 은 게이트웨이의 주소다. */
        @Test
        @DisplayName("X-Forwarded-For 의 첫 항목을 쓴다 — 클라이언트에 가장 가까운 값")
        void prefersForwardedFor() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Forwarded-For", "203.0.113.42, 10.0.0.1");
            request.setRemoteAddr("10.0.0.1");

            assertThat(DeviceIdentity.clientIp(request)).isEqualTo("203.0.113.42");
        }

        @Test
        @DisplayName("헤더가 없으면 원격 주소")
        void fallsBackToRemoteAddr() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.1");

            assertThat(DeviceIdentity.clientIp(request)).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("빈 헤더도 원격 주소로 떨어진다")
        void blankHeaderFallsBack() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Forwarded-For", "  ");
            request.setRemoteAddr("10.0.0.1");

            assertThat(DeviceIdentity.clientIp(request)).isEqualTo("10.0.0.1");
        }
    }
}
