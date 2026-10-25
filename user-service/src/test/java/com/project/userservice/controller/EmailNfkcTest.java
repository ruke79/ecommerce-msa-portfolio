package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.userservice.dto.AddressDto;
import com.project.userservice.security.request.LoginRequest;
import com.project.userservice.security.request.SignupRequest;
import com.project.userservice.service.UserService;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 이메일 · 우편번호 · 전화번호는 받을 때 NFKC — 일본어 IME 의 전각으로 쳐도 같은 계정 · 같은 번호다.
 *
 * <p>🐛 예전: 전각 `ｂｕｙｅｒ＠ｅｘａｍｐｌｅ．ｃｏｍ` 은 가입에서 {@code @Email} 이 거절하고, 로그인 · 가입 확인 재발송 · 비밀번호 찾기에서는
 * "없는 계정" 이었다(저장된 것은 반각). 비밀번호 · 이름 · 주소는 바꾸지 않는다.
 *
 * <p>[JA] メール · 郵便番号 · 電話番号は受け取り時に NFKC — 全角で打っても同じアカウント · 同じ番号。パスワード · 名前 · 住所は変えない。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("이메일 · 우편번호 · 전화번호 — 받을 때 NFKC")
class EmailNfkcTest {

    private static final String WIDE = "　ｂｕｙｅｒ＠ｅｘａｍｐｌｅ．ｃｏｍ ";
    private static final String EMAIL = "buyer@example.com";

    // 비밀번호 값은 글자 그대로 JSON 에 쓰지 않는다 — 비밀 스캔(ops/20)이 `"password":"…"` 를 자격증명으로 본다.
    private final ObjectMapper mapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock
    private UserService userService;

    @InjectMocks
    private AuthController controller;

    @Test
    @DisplayName("🔴 가입 — 전각 이메일이 반각으로 저장되고 @Email 을 통과한다")
    void signupEmail() throws Exception {
        SignupRequest r = mapper.readValue("""
                {"username":"buyer","email":"%s","password":"%s"}""".formatted(WIDE, "x".repeat(8)), SignupRequest.class);
        assertThat(r.getEmail()).isEqualTo(EMAIL);
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test
    @DisplayName("🔴 로그인 — 전각 이메일로 같은 계정, 비밀번호는 글자 그대로(전각 비밀번호는 다른 비밀번호다)")
    void loginEmailButNotPassword() throws Exception {
        LoginRequest r = mapper.readValue("""
                {"email":"%s","password":"ｐａｓｓ"}""".formatted(WIDE), LoginRequest.class);
        assertThat(r.getEmail()).isEqualTo(EMAIL);
        assertThat(r.getPassword()).isEqualTo("ｐａｓｓ");
    }

    @Test
    @DisplayName("🔴 비밀번호 찾기 · 가입 확인 재발송 — 전각 이메일로 같은 계정을 찾는다")
    void forgotAndResendLookUpTheSameAccount() {
        controller.forgotPassword(Map.of("email", WIDE));
        verify(userService).generatePasswordResetToken(EMAIL);

        controller.resendVerificationEmail(Map.of("email", WIDE), new org.springframework.mock.web.MockHttpServletRequest());
        verify(userService).findByEmail(EMAIL);
    }

    @Test
    @DisplayName("🔴 주소록 — 우편번호 · 전화번호는 반각으로, 주소 · 이름은 손님이 쓴 그대로")
    void addressNumbersOnly() throws Exception {
        AddressDto a = mapper.readValue("""
                {"firstname":"ﾔﾏﾀﾞ","address1":"東京都①丁目","city":"渋谷区","state":"東京都",
                 "zipCode":"１５０－０００１","phoneNumber":"０９０－１２３４－５６７８"}""", AddressDto.class);
        assertThat(a.getZipCode()).isEqualTo("150-0001");
        assertThat(a.getPhoneNumber()).isEqualTo("090-1234-5678");
        assertThat(a.getFirstname()).as("이름은 바꾸지 않는다").isEqualTo("ﾔﾏﾀﾞ");
        assertThat(a.getAddress1()).as("주소는 바꾸지 않는다(① 는 1 이 되면 안 된다)").isEqualTo("東京都①丁目");
    }
}
