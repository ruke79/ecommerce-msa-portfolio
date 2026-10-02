package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.project.common.response.GenericResponse;
import com.project.userservice.model.User;
import com.project.userservice.security.request.SignupRequest;
import com.project.userservice.service.UserService;

/**
 * 🐛 <b>중복 가입이 {@code 200 OK} 로 나가고 있었다.</b>
 *
 * <p>프론트의 {@code useMutation} 은 2xx 를 성공으로 보므로 {@code onSuccess} 가 실행됐고,
 * 화면에는 <b>"인증 메일을 보냈습니다"</b> 토스트가 뜬 뒤 로그인 화면으로 넘어갔다. 본문에
 * 담긴 "이미 존재한다"는 아무도 보지 못했다 — 사용자는 오지 않을 메일을 기다렸고, 그 사이
 * 진짜 계정은 그대로 있었다.
 *
 * <p>이건 상태 코드 하나가 <b>사용자에게 거짓말을 시킨</b> 경우다. 서버는 사실을 말했지만
 * 그 사실이 닿을 수 없는 자리에 놓여 있었다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("회원가입 중복 처리")
class RegisterDuplicateTest {

    @Mock private UserService userService;
    @InjectMocks private AuthController controller;

    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("POST", "/api/auth/public/register");
        request.setServerName("shop.example.com");
    }

    private SignupRequest signup() {
        SignupRequest r = new SignupRequest();
        r.setUsername("tester");
        r.setEmail("tester@example.com");
        r.setPassword("pw12345678");
        r.setRole(Set.of("user"));
        return r;
    }

    private String messageOf(ResponseEntity<?> response) {
        return ((GenericResponse) response.getBody()).getMessage();
    }

    /**
     * 🔴 이 파일의 핵심. 200 이면 프론트는 성공 경로를 탄다 — 본문에 무엇이 들어 있든
     * 상관없다.
     */
    @Test
    @DisplayName("🔴 이미 가입된 이메일은 2xx 가 아니다 — 2xx 면 화면이 성공으로 처리한다")
    void duplicateEmailIsNotSuccess() throws Exception {
        when(userService.findByEmail("tester@example.com")).thenReturn(mock(User.class));

        ResponseEntity<?> response = controller.registerUserAccount(signup(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    @DisplayName("이미 사용 중인 이름도 마찬가지다")
    void duplicateUsernameIsNotSuccess() throws Exception {
        when(userService.findByEmail(any())).thenReturn(null);
        when(userService.findByUsername("tester")).thenReturn(mock(User.class));

        ResponseEntity<?> response = controller.registerUserAccount(signup(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * 문구가 실제로 사용자에게 보이게 됐으므로, 영어 진단문이 아니라 <b>다음에 뭘 해야
     * 하는지</b>가 적혀 있어야 한다. 예전 문구는 "The same user Id exists" 였다.
     */
    @Test
    @DisplayName("무엇을 하면 되는지 알려준다 — 로그인, 그리고 확인 메일 재발송")
    void messageTellsWhatToDoNext() throws Exception {
        when(userService.findByEmail(any())).thenReturn(mock(User.class));

        String message = messageOf(controller.registerUserAccount(signup(), request));

        assertThat(message).contains("이미 가입된");
        assertThat(message).contains("재발송");
    }

    /**
     * 🔒 미인증 계정인지 아닌지를 응답으로 구분하면, 이 엔드포인트가 <b>계정 상태 확인
     * 도구</b>가 된다. 가입 여부가 드러나는 것은 가입 엔드포인트의 피할 수 없는 성질이지만,
     * 인증 여부까지 얹을 이유는 없다.
     */
    @Test
    @DisplayName("🔒 인증된 계정과 미인증 계정을 구분해 답하지 않는다")
    void doesNotRevealVerificationState() throws Exception {
        User verified = mock(User.class);
        when(verified.isEnabled()).thenReturn(true);
        User unverified = mock(User.class);
        when(unverified.isEnabled()).thenReturn(false);

        when(userService.findByEmail(any())).thenReturn(verified);
        ResponseEntity<?> forVerified = controller.registerUserAccount(signup(), request);

        when(userService.findByEmail(any())).thenReturn(unverified);
        ResponseEntity<?> forUnverified = controller.registerUserAccount(signup(), request);

        assertThat(forVerified.getStatusCode()).isEqualTo(forUnverified.getStatusCode());
        assertThat(messageOf(forVerified)).isEqualTo(messageOf(forUnverified));
    }

    /** 중복이면 계정을 만들지 않는다 — 당연해 보이지만 고정해 둔다. */
    @Test
    @DisplayName("중복이면 가입 절차로 넘어가지 않는다")
    void doesNotRegisterOnDuplicate() throws Exception {
        when(userService.findByEmail(any())).thenReturn(mock(User.class));

        controller.registerUserAccount(signup(), request);

        verify(userService, never()).registerNewUserAccount(any());
    }
    /** 🐛 예전: 400 의 본문이 문자열 하나라 화면이 이유를 읽지 못했다(docs/68 §18 — 유출 비밀번호를 고칠 수 없었다). */
    @Test
    @DisplayName("🔴 400 도 message 를 담는다 — 유출 비밀번호면 화면이 그 이유를 보여 줄 수 있다")
    void badRequestCarriesMessage() throws Exception {
        org.mockito.Mockito.when(userService.registerNewUserAccount(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.project.userservice.security.password.BreachedPasswordException());

        ResponseEntity<?> response = controller.registerUserAccount(signup(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(messageOf(response)).contains("유출");
    }
}
