package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.project.userservice.model.PasswordResetToken;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.PasswordResetTokenRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.service.impl.UserServiceImpl;
import com.project.userservice.service.impl.UserServiceImpl.InvalidCurrentPasswordException;

/**
 * 비밀번호 변경·재설정.
 *
 * <p>여기서 고정하는 것은 넷이고, 넷 다 실제로 깨져 있었다:
 *
 * <ol>
 *   <li>🐛 현재 비밀번호가 틀려도 <b>성공으로 보고</b>했다 (조건문에 else 가 없고 반환형이 void)</li>
 *   <li>🔒 재설정 토큰을 <b>평문</b>으로 저장했다 (가입 확인 토큰은 해시인데)</li>
 *   <li>🔒 미가입 주소는 예외 → 500, 가입 주소는 200 — <b>응답이 곧 가입 여부</b>였다</li>
 *   <li>🔒 비밀번호를 바꿔도 <b>기존 세션이 살아 있었다</b></li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("비밀번호 변경·재설정")
class PasswordChangeTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private com.project.userservice.security.RedisSessionManager sessionManager;

    @InjectMocks private UserServiceImpl userService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().email("buyer@example.com").username("buyer").build();
        user.setUserId(7L);
        user.setPassword("$2a$old");

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("buyer@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode(any())).thenReturn("$2a$new");
    }

    // ── 로그인 상태에서 변경 ──────────────────────────────────────────────

    /**
     * 🔴 이 파일에서 가장 중요한 테스트다. 예전에는 조건문에 else 가 없어 그냥 빠져나갔고,
     * 반환형이 void 라 컨트롤러는 200 "Password updated" 를 돌려줬다. 사용자는 비밀번호가
     * 바뀐 줄 알고 다음 로그인에서 새 비밀번호를 넣다 실패한다.
     */
    @Test
    @DisplayName("🐛 현재 비밀번호가 틀리면 실패한다 — 조용히 성공으로 보고하지 않는다")
    void wrongCurrentPasswordFails() {
        when(passwordEncoder.matches("wrong", "$2a$old")).thenReturn(false);

        assertThatThrownBy(() -> userService.updatePassword(7L, "wrong", "newpw123"))
                .isInstanceOf(InvalidCurrentPasswordException.class);

        verify(userRepository, never()).save(any(User.class));
        assertThat(user.getPassword()).isEqualTo("$2a$old");
    }

    @Test
    @DisplayName("현재 비밀번호가 맞으면 바꾼다")
    void correctCurrentPasswordSucceeds() {
        when(passwordEncoder.matches("old", "$2a$old")).thenReturn(true);

        userService.updatePassword(7L, "old", "newpw123");

        assertThat(user.getPassword()).isEqualTo("$2a$new");
    }

    /**
     * 🔒 세션을 끊지 않으면, 계정이 털려서 비밀번호를 바꾼 경우에도 공격자는 최대
     * 14일(리프레시 수명) 동안 로그인 상태로 남는다 — 바꾼 의미가 사라진다.
     * 이 시각이 {@code RefreshTokenController} 의 거절 기준이다.
     */
    @Test
    @DisplayName("🔒 변경 시각을 남긴다 — 이게 없으면 기존 세션이 그대로 살아 있다")
    void recordsPasswordChangedAt() {
        when(passwordEncoder.matches("old", "$2a$old")).thenReturn(true);

        userService.updatePassword(7L, "old", "newpw123");

        assertThat(user.getPasswordChangedAt()).isNotNull();
    }

    /**
     * 🐛 세션 <b>기록</b>도 함께 지운다. passwordChangedAt 이 그 세션을 이미 쓸 수 없게
     * 만들지만, 기록이 남아 있으면 다음 로그인이 "다중 로그인" 으로 보인다 — 혼자
     * 쓰는데도 경고가 뜬다. 비밀번호를 재설정한 사용자가 실제로 그 경고를 봤다.
     */
    @Test
    @DisplayName("🐛 옛 세션 기록을 지운다 — 남으면 다음 로그인이 '다중 로그인'이 된다")
    void clearsSessionRecord() {
        when(passwordEncoder.matches("old", "$2a$old")).thenReturn(true);

        userService.updatePassword(7L, "old", "newpw123");

        verify(sessionManager).removeSession("buyer@example.com");
    }

    // ── 비밀번호 찾기 ────────────────────────────────────────────────────

    /**
     * 🔒 예전에는 미가입 주소면 예외를 던졌고 컨트롤러가 그걸 500 으로 바꿨다. 가입된
     * 주소는 200 — <b>응답 코드만 보면 그 주소의 가입 여부를 알 수 있었다.</b> 같은
     * 저장소의 resend-verification 은 이 누설을 막고 있는데 여기가 뚫려 있었다.
     */
    @Test
    @DisplayName("🔒 가입되지 않은 주소에도 조용히 성공한다 — 가입 여부가 새면 안 된다")
    void unknownEmailDoesNotThrow() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatCode(() -> userService.generatePasswordResetToken("nobody@example.com"))
                .doesNotThrowAnyException();

        verify(emailService, never()).sendPasswordResetEmail(any(), any(), any());
    }

    /**
     * 🔒 저장되는 것은 해시여야 한다. 가입 확인 토큰은 SHA-256 으로 저장하면서 계정을
     * 통째로 가져갈 수 있는 이 토큰은 평문이었다 — 보호 수준이 거꾸로였다.
     */
    @Test
    @DisplayName("🔒 재설정 토큰을 평문으로 저장하지 않는다")
    void resetTokenIsHashed() {
        when(passwordResetTokenRepository.findByUser(user)).thenReturn(Optional.empty());

        userService.generatePasswordResetToken("buyer@example.com");

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(saved.capture());

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetEmail(any(), any(), url.capture());

        String rawToken = url.getValue().substring(url.getValue().indexOf("token=") + 6);
        assertThat(saved.getValue().getToken())
                .as("저장된 값이 링크의 원문과 같으면 평문 저장이다")
                .isNotEqualTo(rawToken)
                .isEqualTo(PasswordResetToken.hash(rawToken))
                .hasSize(64);
    }

    /**
     * 🔒 예전에는 요청할 때마다 새 행이 쌓였고 이전 링크는 아무도 무효화하지 않았다.
     * 열 번 누르면 24시간짜리 유효 링크가 열 개 살아 있었다.
     */
    @Test
    @DisplayName("🔒 다시 요청하면 같은 행을 재사용한다 — 유효 링크가 쌓이지 않는다")
    void reusesExistingTokenRow() {
        PasswordResetToken existing = new PasswordResetToken(
                "old-raw", Instant.now().plus(1, ChronoUnit.HOURS), user);
        String before = existing.getToken();
        when(passwordResetTokenRepository.findByUser(user)).thenReturn(Optional.of(existing));

        userService.generatePasswordResetToken("buyer@example.com");

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(saved.capture());

        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(saved.getValue().getToken()).isNotEqualTo(before);
    }

    // ── 재설정 ───────────────────────────────────────────────────────────

    /**
     * 🔴 저장이 해시인데 조회를 원문으로 하면 아무것도 걸리지 않는다 — 해싱을 넣으면서
     * 이 줄을 빠뜨리면 재설정이 통째로 죽는다.
     */
    @Test
    @DisplayName("🔴 원문 토큰으로 요청하면 해시로 찾는다")
    void resetLooksUpByHash() {
        PasswordResetToken token = new PasswordResetToken(
                "raw-token", Instant.now().plus(1, ChronoUnit.HOURS), user);
        when(passwordResetTokenRepository.findByToken(PasswordResetToken.hash("raw-token")))
                .thenReturn(Optional.of(token));

        userService.resetPassword("raw-token", "newpw123");

        assertThat(user.getPassword()).isEqualTo("$2a$new");
        assertThat(token.isUsed()).isTrue();
        assertThat(user.getPasswordChangedAt())
                .as("재설정하러 오는 사람의 절반은 계정을 도둑맞아서 온다")
                .isNotNull();
    }

    @Test
    @DisplayName("이미 쓴 링크는 거절한다")
    void usedTokenRejected() {
        PasswordResetToken token = new PasswordResetToken(
                "raw", Instant.now().plus(1, ChronoUnit.HOURS), user);
        token.setUsed(true);
        when(passwordResetTokenRepository.findByToken(any())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> userService.resetPassword("raw", "newpw123"))
                .hasMessageContaining("이미 사용된");
    }

    @Test
    @DisplayName("만료된 링크는 거절한다")
    void expiredTokenRejected() {
        PasswordResetToken token = new PasswordResetToken(
                "raw", Instant.now().minus(1, ChronoUnit.HOURS), user);
        when(passwordResetTokenRepository.findByToken(any())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> userService.resetPassword("raw", "newpw123"))
                .hasMessageContaining("유효기간");
    }

    @Test
    @DisplayName("모르는 링크는 거절한다")
    void unknownTokenRejected() {
        when(passwordResetTokenRepository.findByToken(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resetPassword("raw", "newpw123"))
                .hasMessageContaining("유효하지 않은");
    }

    /** 🔒 해시라 해도 토큰 값을 로그·문자열 표현에 남기지 않는다. */
    @Test
    @DisplayName("🔒 toString 에 토큰이 들어가지 않는다")
    void toStringHidesToken() {
        PasswordResetToken token = new PasswordResetToken(
                "raw", Instant.now().plus(1, ChronoUnit.HOURS), user);

        assertThat(token.toString())
                .doesNotContain("raw")
                .doesNotContain(PasswordResetToken.hash("raw"));
    }

    @Test
    @DisplayName("같은 원문은 같은 해시가 된다 — 조회가 되려면 당연하지만 고정해 둔다")
    void hashIsStable() {
        assertThat(PasswordResetToken.hash("abc")).isEqualTo(PasswordResetToken.hash("abc"));
        assertThat(PasswordResetToken.hash("abc")).isNotEqualTo(PasswordResetToken.hash("abd"));
    }
}
