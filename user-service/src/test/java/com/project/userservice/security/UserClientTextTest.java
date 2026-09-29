package com.project.userservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.project.common.i18n.ClientText;

/**
 * user-service 의 서버 문구가 일본어 카탈로그({@code i18n/client-text.ja.txt})로 바뀌는가 (docs/60 §4).
 *
 * <p>로그인 실패 둘("없는 계정"·"틀린 비밀번호")은 한국어에서 같은 문구다(user enumeration 방지). 일본어에서도 같아야 한다 —
 * 번역이 갈라지면 언어만 바꿔 가입 여부를 알아낼 수 있다.
 */
@DisplayName("user-service 서버 문구 일본어")
class UserClientTextTest {

    private static final Pattern HANGUL = Pattern.compile("[\\uac00-\\ud7a3]");

    private static String ja(String message) {
        return ClientText.localize(message, Locale.JAPANESE);
    }

    @Test
    @DisplayName("정확히 같은 문구")
    void exactMessage() {
        assertThat(ja("로그인이 필요합니다.")).isEqualTo("ログインが必要です。");
        assertThat(ja("비밀번호가 변경되었습니다. 새 비밀번호로 로그인해 주세요."))
                .isEqualTo("パスワードを変更しました。新しいパスワードでログインしてください。");
    }

    @Test
    @DisplayName("바뀌는 부분이 있는 문구 — 잠금 남은 시간, 검증 오류의 \"필드: 문구\"")
    void patternMessage() {
        assertThat(ja("로그인이 잠시 잠겼습니다. 약 15분 후 다시 시도해 주세요."))
                .isEqualTo("ログインが一時的にロックされています。約15分後に再度お試しください。");
        assertThat(ja("address1: 주소1은 적어도 2자 이상이어야 합니다."))
                .isEqualTo("address1: 住所1は2文字以上で入力してください。");
    }

    @Test
    @DisplayName("🔒 없는 계정과 틀린 비밀번호는 일본어에서도 같은 문구다")
    void loginFailuresStayIndistinguishable() {
        String noSuchAccount = AuthLoginFilter.describe(new UsernameNotFoundException("User not found with email: x@y.z")).message();
        String wrongPassword = AuthLoginFilter.describe(new BadCredentialsException("Bad Credentials")).message();
        assertThat(noSuchAccount).isEqualTo(wrongPassword);

        String jaNoSuchAccount = ja(noSuchAccount);
        String jaWrongPassword = ja(wrongPassword);
        assertThat(jaNoSuchAccount).isEqualTo(jaWrongPassword)
                .isEqualTo("メールアドレスまたはパスワードが正しくありません。ソーシャルアカウントで登録された場合は、下のソーシャルログインをご利用ください。");
        assertThat(HANGUL.matcher(jaNoSuchAccount).find()).isFalse();
    }

    @Test
    @DisplayName("한국어 요청은 그대로")
    void koreanUntouched() {
        assertThat(ClientText.localize("로그인이 필요합니다.", Locale.KOREAN)).isEqualTo("로그인이 필요합니다.");
    }
}
