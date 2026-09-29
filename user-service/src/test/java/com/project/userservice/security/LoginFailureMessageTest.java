package com.project.userservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.userservice.security.AuthLoginFilter.Failure;
import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.service.DeviceRecognitionService;
import com.project.userservice.service.NotificationService;
import com.project.userservice.service.RefreshTokenService;

/**
 * 🐛 회귀 대상: <b>로그인이 왜 실패했는지 사용자에게 닿지 않았다.</b>
 *
 * <p>화면에는 어떤 이유로 실패하든 "Login Failed." 한 줄만 떴다. 원인은
 * {@link AuthLoginFilter#attemptAuthentication} 의 {@code catch (Exception e)} 였다 —
 * {@link AuthenticationException} 까지 잡아 <b>본문 없는 400</b> 을 내보내고 null 을
 * 반환했는데, {@code AbstractAuthenticationProcessingFilter} 는 null 을 "아직 인증이
 * 끝나지 않았다"로 보고 그 자리에서 반환한다. 그래서
 * {@code unsuccessfulAuthentication} 이 <b>한 번도 불리지 않았다.</b>
 *
 * <p>잠금까지 남은 시간도, 메일 인증 안내도 전부 만들어져 있었지만 죽은 코드였다.
 *
 * <p>여기서는 두 가지를 나눠 본다:
 * <ol>
 *   <li>인증 예외가 <b>밖으로 나가는가</b> — 나가야 스프링이 실패 처리로 넘긴다</li>
 *   <li>사유별로 <b>무엇을 응답하는가</b> — 상태 코드와 문구</li>
 * </ol>
 */
@DisplayName("로그인 실패 사유")
class LoginFailureMessageTest {

    private AuthenticationManager authenticationManager;
    private AuthLoginFilter filter;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        filter = new AuthLoginFilter(authenticationManager, mock(JwtUtils.class),
                mock(RefreshTokenService.class), mock(NotificationService.class),
                // 기기 기록은 로그인 성공 뒤에만 불린다 — 이 테스트는 실패 경로만 본다.
                mock(DeviceRecognitionService.class), mock(SecurityAlertProducer.class),
                mock(RedisSessionManager.class), mock(PendingTwoFactor.class));
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requestWith(String json) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/public/signin");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private void attempt(String json) {
        filter.attemptAuthentication(requestWith(json), new MockHttpServletResponse());
    }

    private static String credentials() {
        return "{\"email\":\"tester@example.com\",\"password\":\"pw123456\"}";
    }

    @Nested
    @DisplayName("인증 예외가 밖으로 나간다")
    class ExceptionsPropagate {

        /**
         * 🔴 이것이 버그의 핵심이다. 예외를 삼키면 스프링이 실패 처리를 부르지 않아
         * 사유가 담긴 본문이 만들어지지 않는다.
         */
        @Test
        @DisplayName("BadCredentials 를 삼키지 않는다 — 삼키면 실패 처리가 불리지 않는다")
        void badCredentialsPropagates() {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("Bad Credentials"));

            assertThatThrownBy(() -> attempt(credentials()))
                    .isInstanceOf(BadCredentialsException.class);
        }

        @Test
        @DisplayName("잠금 예외도 그대로 나간다 — 남은 시간이 담긴 문구가 여기 있다")
        void lockedPropagates() {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new LockedException("약 12분 후 다시 시도해 주세요."));

            assertThatThrownBy(() -> attempt(credentials()))
                    .isInstanceOf(LockedException.class)
                    .hasMessageContaining("12분");
        }

        @Test
        @DisplayName("미인증 계정 예외도 그대로 나간다")
        void disabledPropagates() {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new DisabledException("메일 인증이 완료되지 않은 계정입니다."));

            assertThatThrownBy(() -> attempt(credentials()))
                    .isInstanceOf(DisabledException.class);
        }

        /**
         * null 을 돌려주면 스프링은 "다단계 인증이 진행 중"으로 보고 조용히 반환한다 —
         * 응답 본문이 없는 채로 끝난다. 어떤 경로에서도 null 이 나오면 안 된다.
         */
        @Test
        @DisplayName("본문이 JSON 이 아니어도 null 을 돌려주지 않는다")
        void malformedBodyThrowsInsteadOfReturningNull() {
            assertThatThrownBy(() -> attempt("이건 JSON 이 아니다"))
                    .isInstanceOf(AuthenticationException.class);
        }

        @Test
        @DisplayName("이메일·비밀번호가 비면 인증기를 부르지 않고 거절한다")
        void emptyCredentialsAreRejectedBeforeAuthenticating() {
            assertThatThrownBy(() -> attempt("{\"email\":\"\",\"password\":\"\"}"))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessageContaining("입력해 주세요");

            verify(authenticationManager, never()).authenticate(any());
        }

        /**
         * 빈 값으로 인증기에 들어가면 "Bad Credentials" 가 되어 사용자는 비밀번호가
         * 틀린 줄 안다. 실제로는 아무것도 보내지 않은 것이다.
         */
        @Test
        @DisplayName("비밀번호만 비어도 '비밀번호가 틀렸다'로 보이지 않는다")
        void missingPasswordIsNotABadCredentialError() {
            assertThatThrownBy(() -> attempt("{\"email\":\"a@b.c\",\"password\":null}"))
                    .isInstanceOf(AuthLoginFilter.BadRequestAuthenticationException.class);
        }

        @Test
        @DisplayName("정상 요청이면 인증 결과를 그대로 돌려준다")
        void successReturnsTheAuthentication() {
            var authentication = new UsernamePasswordAuthenticationToken("tester@example.com", null);
            when(authenticationManager.authenticate(any())).thenReturn(authentication);

            assertThat(filter.attemptAuthentication(
                    requestWith(credentials()), new MockHttpServletResponse()))
                    .isSameAs(authentication);
        }

        @Test
        @DisplayName("이메일 앞뒤 공백은 지운다 — 복사·붙여넣기가 실패 원인이 되면 안 된다")
        void emailIsTrimmed() {
            when(authenticationManager.authenticate(any()))
                    .thenReturn(new UsernamePasswordAuthenticationToken("x", null));

            attempt("{\"email\":\"  tester@example.com  \",\"password\":\"pw\"}");

            var captor = org.mockito.ArgumentCaptor.forClass(
                    UsernamePasswordAuthenticationToken.class);
            verify(authenticationManager).authenticate(captor.capture());
            assertThat(captor.getValue().getPrincipal()).isEqualTo("tester@example.com");
        }
    }

    @Nested
    @DisplayName("사유별 응답")
    class Responses {

        @Test
        @DisplayName("잠금 — 남은 시간을 그대로 전한다. 기다려야 하는 상황이다")
        void locked() {
            Failure failure = AuthLoginFilter.describe(
                    new LockedException("로그인이 잠시 잠겼습니다. 약 12분 후 다시 시도해 주세요."));

            assertThat(failure.message()).contains("12분");
            assertThat(failure.status()).isEqualTo(401);
        }

        /**
         * 여기에 "비밀번호가 틀렸다"를 주면 사용자는 맞는 비밀번호를 계속 다시 치다가
         * 결국 잠긴다. 정작 필요한 것은 메일함을 여는 것이다.
         */
        @Test
        @DisplayName("미인증 — 메일함을 보라고 한다. 비밀번호를 의심하게 두지 않는다")
        void disabled() {
            Failure failure = AuthLoginFilter.describe(
                    new DisabledException("메일 인증이 완료되지 않은 계정입니다. 링크를 눌러 주세요."));

            assertThat(failure.message()).contains("메일 인증");
            assertThat(failure.message()).doesNotContain("비밀번호가 올바르지");
        }

        /**
         * 🔒 "그런 계정이 없습니다"와 "비밀번호가 틀렸습니다"를 구분해 주면, 주소 목록을
         * 넣어 가며 어떤 주소가 가입돼 있는지 알아내는 도구가 된다.
         */
        @Test
        @DisplayName("없는 계정과 틀린 비밀번호가 같은 문구다 — 가입 여부가 새면 안 된다")
        void enumerationIsNotPossible() {
            Failure noSuchUser = AuthLoginFilter.describe(
                    new UsernameNotFoundException("User not found with email: victim@example.com"));
            Failure wrongPassword = AuthLoginFilter.describe(new BadCredentialsException("Bad Credentials"));

            assertThat(noSuchUser.message()).isEqualTo(wrongPassword.message());
            assertThat(noSuchUser.status()).isEqualTo(wrongPassword.status());
        }

        /** 예외 메시지에 들어 있던 이메일이 응답으로 새어 나가면 안 된다. */
        @Test
        @DisplayName("예외 메시지의 이메일이 응답에 실리지 않는다")
        void emailNeverLeaksIntoTheResponse() {
            Failure failure = AuthLoginFilter.describe(
                    new UsernameNotFoundException("User not found with email: victim@example.com"));

            assertThat(failure.message()).doesNotContain("victim@example.com");
        }

        /**
         * 소셜로 가입한 사람이 비밀번호 로그인을 반복하는 것이 흔한 막다른 길이다.
         * 이 문장은 계정이 있든 없든 <b>똑같이</b> 나가므로 아무것도 흘리지 않는다.
         */
        @Test
        @DisplayName("소셜 로그인 안내를 덧붙인다 — 모두에게 같은 문구라 아무것도 흘리지 않는다")
        void hintsAtSocialLogin() {
            assertThat(AuthLoginFilter.describe(new BadCredentialsException("x")).message())
                    .contains("소셜");
        }

        @Test
        @DisplayName("실패 횟수나 잠금까지 남은 횟수를 알려주지 않는다")
        void neverRevealsAttemptCounts() {
            String message = AuthLoginFilter.describe(new BadCredentialsException("x")).message();

            assertThat(message).doesNotContainPattern("\\d+\\s*회");
        }

        @Test
        @DisplayName("요청 오류는 400 이다 — 자격 증명 문제가 아니다")
        void badRequest() {
            Failure failure = AuthLoginFilter.describe(
                    new AuthLoginFilter.BadRequestAuthenticationException("이메일과 비밀번호를 모두 입력해 주세요."));

            assertThat(failure.status()).isEqualTo(400);
            assertThat(failure.message()).contains("입력해 주세요");
        }

        /**
         * DB·Redis 가 잠깐 안 뜨는 배포 중에 실제로 일어난다. "비밀번호가 틀렸다"로
         * 보이면 사용자는 자기 자격 증명을 의심하며 반복한다.
         */
        @Test
        @DisplayName("인증 절차 자체의 실패는 503 — 내 잘못이 아니라고 말해 준다")
        void infrastructureFailure() {
            Failure failure = AuthLoginFilter.describe(
                    new AuthenticationServiceException("could not open JPA connection"));

            assertThat(failure.status()).isEqualTo(503);
            assertThat(failure.message()).contains("일시적인 오류");
            // 내부 오류 문구를 그대로 내보내면 스택·드라이버 정보가 샌다.
            assertThat(failure.message()).doesNotContain("JPA");
        }

        /**
         * 🔴 화면이 "확인 메일 재발송" 폼을 띄울지 결정하는 신호다.
         *
         * <p>이 코드가 없던 동안 화면은 한국어 문구를 비교할 수밖에 없었고, 실제로는
         * 그마저 하지 않아 <b>재발송 경로가 아예 없었다.</b> 문구를 다듬는 순간 조용히
         * 깨지는 방식이라 코드로 내려보낸다.
         */
        @Test
        @DisplayName("🔴 메일 미인증 거부에는 code 를 붙인다 — 화면이 문구를 비교하지 않게")
        void emailNotVerifiedCarriesCode() {
            Failure failure = AuthLoginFilter.describe(
                    new DisabledException("메일 인증이 완료되지 않은 계정입니다."));

            assertThat(failure.status()).isEqualTo(401);
            assertThat(failure.code()).isEqualTo("email_not_verified");
        }

        /**
         * 🔒 code 가 다른 실패에도 붙으면, 비밀번호를 모르는 사람이 그 값 하나로
         * "이 주소는 가입돼 있고 아직 미인증" 을 알아낼 수 있게 된다.
         * DisabledException 은 비밀번호가 맞은 뒤에만 나온다(CustomAuthenticationProvider).
         */
        @Test
        @DisplayName("🔒 다른 실패에는 code 를 붙이지 않는다 — 가입 여부 확인 도구가 된다")
        void otherFailuresCarryNoCode() {
            assertThat(AuthLoginFilter.describe(new BadCredentialsException("x")).code()).isNull();
            assertThat(AuthLoginFilter.describe(
                    new UsernameNotFoundException("no such user")).code()).isNull();
            assertThat(AuthLoginFilter.describe(
                    new AuthenticationServiceException("db down")).code()).isNull();
            assertThat(AuthLoginFilter.describe(new LockedException("잠김")).code()).isNull();
        }
    }

    @Nested
    @DisplayName("응답 본문")
    class ResponseBody {

        private MockHttpServletResponse reject(AuthenticationException failed) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.unsuccessfulAuthentication(
                    new MockHttpServletRequest("POST", "/api/auth/public/signin"), response, failed);
            return response;
        }

        @Test
        @DisplayName("사유를 JSON 본문으로 내보낸다 — 빈 응답이면 화면이 기본 문구만 띄운다")
        void writesTheReasonAsJson() throws Exception {
            MockHttpServletResponse response = reject(
                    new LockedException("로그인이 잠시 잠겼습니다. 약 12분 후 다시 시도해 주세요."));

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentType()).contains("application/json");

            var body = new ObjectMapper().readTree(response.getContentAsString());
            assertThat(body.get("message").asText()).contains("12분");
        }

        @Test
        @DisplayName("한글이 깨지지 않는다")
        void koreanSurvivesTheEncoding() throws Exception {
            MockHttpServletResponse response = reject(new BadCredentialsException("x"));

            assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
            assertThat(new ObjectMapper().readTree(response.getContentAsString())
                    .get("message").asText()).contains("이메일 또는 비밀번호");
        }

        @Test
        @DisplayName("요청 오류는 400 으로 나간다")
        void badRequestStatus() {
            assertThat(reject(new AuthLoginFilter.BadRequestAuthenticationException("빈 입력")).getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("인프라 실패는 503 으로 나간다")
        void serviceUnavailableStatus() {
            assertThat(reject(new AuthenticationServiceException("db down")).getStatus())
                    .isEqualTo(503);
        }
    }

}
