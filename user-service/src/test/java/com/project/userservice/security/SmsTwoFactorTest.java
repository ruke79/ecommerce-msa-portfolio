package com.project.userservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.project.userservice.client.NotificationServiceClient;
import com.project.userservice.client.NotificationServiceClient.TwoFactorSmsRequest;
import com.project.userservice.client.NotificationServiceClient.TwoFactorSmsResponse;
import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.security.totp.TotpReplayGuard;
import com.project.userservice.service.UserService.TwoFactorChange;

/**
 * 로그인 2단계 인증의 SMS 수단(docs/24 §10) — 시도 한도 공유 · 번호 결속 · 닿지 못하면 막기.
 *
 * <p>[JA] ログイン二段階認証の SMS 手段 — 試行上限の共有 · 番号の結束 · 届かなければ止める。
 */
@DisplayName("2단계 인증 SMS 수단 — user-service")
class SmsTwoFactorTest {

    private NotificationServiceClient client;
    private UserRepository users;
    private TotpReplayGuard attempts;
    private SecurityAlertProducer alerts;
    private SmsTwoFactor sms;
    private User user;

    @BeforeEach
    void setUp() {
        client = mock(NotificationServiceClient.class);
        users = mock(UserRepository.class);
        attempts = mock(TotpReplayGuard.class);
        alerts = mock(SecurityAlertProducer.class);
        when(attempts.maxFailures()).thenReturn(5);
        when(attempts.reserveAttempt(7L)).thenReturn(1L);
        sms = new SmsTwoFactor(client, users, attempts, alerts);
        user = User.builder().build();
        user.setUserId(7L);
        user.setEmail("u@example.com");
        when(users.findById(7L)).thenReturn(Optional.of(user));
    }

    private static TwoFactorSmsResponse result(String result, String binding) {
        return new TwoFactorSmsResponse(result, "+8210****5678", binding, 5, 0, 0);
    }

    @Test
    @DisplayName("🔴 SMS 만 켠 계정도 로그인에 2단계가 필요하다 — 예전 값(TOTP)만 보면 비밀번호로 끝난다")
    void smsOnlyAccountRequiresSecondStep() {
        user.setSmsTwoFactorEnabled(true);
        assertThat(user.requiresTwoFactor()).isTrue();
        assertThat(UserDetailsImpl.build(withRole(user)).is2faEnabled()).isTrue();
    }

    private static User withRole(User u) {
        com.project.userservice.model.Role role = new com.project.userservice.model.Role(
                com.project.userservice.constants.AppRole.ROLE_USER);
        u.setRole(role);
        return u;
    }

    @Test
    @DisplayName("🔴 켜면 notification 이 준 결속값을 저장하고, 그 뒤 발송 · 확인 · 끄기에는 늘 그 결속값을 싣는다")
    void enableStoresBindingAndLaterCallsCarryIt() {
        when(client.verifyTwoFactorSms(any())).thenReturn(result("VERIFIED", "b-123"));
        assertThat(sms.enable(7L, "123456")).isEqualTo(TwoFactorChange.OK);
        assertThat(user.isSmsTwoFactorEnabled()).isTrue();
        assertThat(user.getSmsTwoFactorBinding()).isEqualTo("b-123");
        verify(alerts).publishTwoFactorChanged(7L, "u@example.com", true);

        ArgumentCaptor<TwoFactorSmsRequest> sent = ArgumentCaptor.forClass(TwoFactorSmsRequest.class);
        when(client.sendTwoFactorSms(any())).thenReturn(result("SENT", "b-123"));
        assertThat(sms.send(user).status()).isEqualTo(SmsTwoFactor.SendStatus.SENT);
        verify(client).sendTwoFactorSms(sent.capture());
        assertThat(sent.getValue().binding()).isEqualTo("b-123");

        ArgumentCaptor<TwoFactorSmsRequest> checked = ArgumentCaptor.forClass(TwoFactorSmsRequest.class);
        sms.verifyLogin(user, "654321");
        verify(client, org.mockito.Mockito.times(2)).verifyTwoFactorSms(checked.capture());
        assertThat(checked.getAllValues().get(0).binding()).as("켜기는 결속값 없이").isNull();
        assertThat(checked.getAllValues().get(1).binding()).isEqualTo("b-123");
    }

    @Test
    @DisplayName("통과 응답에 결속값이 없으면 켜지 않는다 — 켜면 그 뒤 번호 검사 없이 보내게 된다")
    void noBindingNoEnable() {
        when(client.verifyTwoFactorSms(any())).thenReturn(result("VERIFIED", null));
        assertThat(sms.enable(7L, "123456")).isEqualTo(TwoFactorChange.UNAVAILABLE);
        assertThat(user.isSmsTwoFactorEnabled()).isFalse();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("🔴 notification 에 닿지 못하면 통과가 아니라 UNAVAILABLE — 시도 한도를 태우지도 않는다(clear 없음)")
    void unreachableIsNotAPass() {
        user.setSmsTwoFactorEnabled(true);
        user.setSmsTwoFactorBinding("b");
        when(client.verifyTwoFactorSms(any())).thenThrow(new RuntimeException("connect timed out"));
        when(client.sendTwoFactorSms(any())).thenThrow(new RuntimeException("connect timed out"));

        assertThat(sms.verifyLogin(user, "123456")).isEqualTo(TwoFactorChange.UNAVAILABLE);
        assertThat(sms.send(user).status()).isEqualTo(SmsTwoFactor.SendStatus.UNAVAILABLE);
        verify(attempts, never()).clearFailures(7L);
    }

    @Test
    @DisplayName("🔴 시도는 TOTP 와 같은 사용자별 칸에서 센다 — 한도를 넘으면 notification 에 묻지도 않는다")
    void sharedAttemptBudget() {
        user.setSmsTwoFactorEnabled(true);
        when(attempts.reserveAttempt(7L)).thenReturn(6L);
        assertThat(sms.verifyLogin(user, "123456")).isEqualTo(TwoFactorChange.TOO_MANY_ATTEMPTS);
        verify(client, never()).verifyTwoFactorSms(any());
    }

    @Test
    @DisplayName("틀린 코드가 한도에 닿는 순간 주인에게 한 번 알린다(TOTP 와 같다)")
    void alertAtTheLimit() {
        user.setSmsTwoFactorEnabled(true);
        when(attempts.reserveAttempt(7L)).thenReturn(5L);
        when(client.verifyTwoFactorSms(any())).thenReturn(result("INVALID", null));
        assertThat(sms.verifyLogin(user, "123456")).isEqualTo(TwoFactorChange.INVALID_CODE);
        verify(alerts).publishAccountEvent(eq(7L), eq("u@example.com"), eq(SecurityAlertProducer.ALERT_2FA_FAILURES), any());
    }

    @Test
    @DisplayName("번호가 바뀌었으면 PHONE_CHANGED — 끌 수도 없다(그때 SMS 수단은 이미 멈춰 있다)")
    void phoneChanged() {
        user.setSmsTwoFactorEnabled(true);
        user.setSmsTwoFactorBinding("b");
        when(client.verifyTwoFactorSms(any())).thenReturn(result("PHONE_CHANGED", null));
        assertThat(sms.disable(7L, "123456")).isEqualTo(TwoFactorChange.PHONE_CHANGED);
        assertThat(user.isSmsTwoFactorEnabled()).isTrue();
        verify(alerts, never()).publishTwoFactorChanged(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("끄면 결속값도 지우고 알린다")
    void disableClearsBinding() {
        user.setSmsTwoFactorEnabled(true);
        user.setSmsTwoFactorBinding("b");
        when(client.verifyTwoFactorSms(any())).thenReturn(result("VERIFIED", "b"));
        assertThat(sms.disable(7L, "123456")).isEqualTo(TwoFactorChange.OK);
        assertThat(user.isSmsTwoFactorEnabled()).isFalse();
        assertThat(user.getSmsTwoFactorBinding()).isNull();
        verify(alerts).publishTwoFactorChanged(7L, "u@example.com", false);
    }

    @Test
    @DisplayName("요청의 문자열 표현에 코드 · 결속값이 없다")
    void requestToStringHidesSecrets() {
        assertThat(new TwoFactorSmsRequest(7L, "bind-xyz", "987654").toString()).doesNotContain("987654", "bind-xyz");
    }
}
