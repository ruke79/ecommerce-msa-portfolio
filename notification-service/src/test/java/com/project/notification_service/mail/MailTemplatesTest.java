package com.project.notification_service.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.notification_service.domain.MailKind;
import com.project.notification_service.sms.PhoneNumbers.Region;

/**
 * 메일 문구.
 *
 * <p>가장 중요한 검사는 <b>이스케이프</b>다. {@code subject} 는 Kafka 이벤트에서 오는 값이라
 * 우리가 만든 문자열이 아니다. 그대로 HTML 에 넣으면 우리 브랜드로 도착한 메일 안에 공격자가
 * 가짜 로그인 링크를 심을 수 있다 — 피싱으로서는 최상의 조건이다.
 */
@DisplayName("메일 문구")
class MailTemplatesTest {

    @Test
    @DisplayName("가입 확인 메일에 확인 링크가 평문과 HTML 양쪽에 들어간다")
    void 가입_확인() {
        var r = MailTemplates.render(Region.KR, MailKind.REGISTRATION_CONFIRM,
                Map.of("confirmationUrl", "https://shop.example.com/confirm?token=abc"));

        assertThat(r.subject()).contains("회원가입");
        // HTML 을 표시하지 않는 클라이언트에서도 링크를 볼 수 있어야 한다.
        assertThat(r.text()).contains("https://shop.example.com/confirm?token=abc");
        assertThat(r.html()).contains("https://shop.example.com/confirm?token=abc");
    }

    @Test
    @DisplayName("텍스트 대안이 항상 있다 — 없으면 스팸 점수가 오른다")
    void 텍스트_대안() {
        for (MailKind kind : MailKind.values()) {
            var r = MailTemplates.render(Region.KR, kind, Map.of());
            assertThat(r.text()).as("%s 의 평문 본문", kind).isNotBlank();
            assertThat(r.html()).as("%s 의 HTML 본문", kind).isNotBlank();
            assertThat(r.subject()).as("%s 의 제목", kind).isNotBlank();
        }
    }

    @Test
    @DisplayName("🐛 제목에 섞인 HTML 을 이스케이프한다 — 안 하면 메일이 피싱 도구가 된다")
    void 제목_이스케이프() {
        var r = MailTemplates.render(Region.KR, MailKind.ORDER_PLACED,
                Map.of("subject", "<a href=\"https://evil.example\">여기를 누르세요</a>"));

        // 태그가 살아 있으면 수신자에게는 우리가 보낸 정상 링크로 보인다.
        assertThat(r.html()).doesNotContain("<a href=\"https://evil.example\"");
        assertThat(r.html()).contains("&lt;a href=");
    }

    @Test
    @DisplayName("작은따옴표도 이스케이프한다 — 속성 인용부호가 바뀌어도 안전하도록")
    void 작은따옴표_이스케이프() {
        var r = MailTemplates.render(Region.KR, MailKind.ORDER_PLACED,
                Map.of("subject", "it's a test"));
        assertThat(r.html()).contains("&#39;");
        assertThat(r.html()).doesNotContain("it's");
    }

    @Test
    @DisplayName("🐛 javascript: URL 은 href 에 들어가지 않는다 — 이스케이프만으로는 못 막는다")
    void 위험한_스킴() {
        // 특수문자가 없어 이스케이프를 그대로 통과한다. 스킴 허용 목록이 유일한 방어다.
        var r = MailTemplates.render(Region.KR, MailKind.REGISTRATION_CONFIRM,
                Map.of("confirmationUrl", "javascript:alert(1)"));

        assertThat(r.html()).doesNotContain("javascript:");
        assertThat(r.text()).doesNotContain("javascript:");
    }

    @Test
    @DisplayName("data: URL 도 막는다 — 임의 문서를 열 수 있다")
    void data_스킴() {
        assertThat(MailTemplates.safeUrl("data:text/html,<script>alert(1)</script>")).isEmpty();
    }

    @Test
    @DisplayName("정상 URL 은 통과한다")
    void 정상_url() {
        assertThat(MailTemplates.safeUrl("https://shop.example.com/x")).isEqualTo("https://shop.example.com/x");
        assertThat(MailTemplates.safeUrl("http://localhost:8000/x")).isEqualTo("http://localhost:8000/x");
        assertThat(MailTemplates.safeUrl("HTTPS://SHOP.EXAMPLE.COM")).isEqualTo("HTTPS://SHOP.EXAMPLE.COM");
    }

    @Test
    @DisplayName("URL 이 없으면 버튼을 넣지 않는다 — 빈 href 는 현재 페이지로 가는 이상한 링크가 된다")
    void 빈_url이면_버튼_없음() {
        var r = MailTemplates.render(Region.KR, MailKind.ORDER_PLACED, Map.of("subject", "주문 1234"));
        assertThat(r.html()).doesNotContain("href=\"\"");
    }

    @Test
    @DisplayName("params 가 없어도 죽지 않는다 — 문구가 어색해질지언정 발송은 계속돼야 한다")
    void params_없음() {
        for (MailKind kind : MailKind.values()) {
            var withNull = MailTemplates.render(Region.KR, kind, null);
            var withEmpty = MailTemplates.render(Region.JP, kind, Map.of());
            assertThat(withNull.subject()).isNotBlank();
            assertThat(withEmpty.subject()).isNotBlank();
        }
    }

    @Test
    @DisplayName("일본어 메일에 한국어가 섞이지 않는다")
    void 일본어_문구() {
        var r = MailTemplates.render(Region.JP, MailKind.REGISTRATION_CONFIRM,
                Map.of("confirmationUrl", "https://shop.example.com/confirm"));

        assertThat(r.subject()).contains("会員登録");
        assertThat(r.html()).contains("メールアドレスを認証する");
        // 버튼 아래 안내문까지 일본어여야 한다 — 여기가 한국어로 남아 있던 적이 있다.
        assertThat(r.html()).contains("ブラウザに貼り付け");
        assertThat(r.html()).doesNotContain("브라우저");
    }

    @Test
    @DisplayName("로케일 표기가 어떻게 오든 일본어를 알아본다")
    void 로케일_해석() {
        // HTTP Accept-Language 는 하이픈, Locale#toString 은 밑줄을 쓴다.
        assertThat(MailTemplates.regionOf("ja")).isEqualTo(Region.JP);
        assertThat(MailTemplates.regionOf("ja-JP")).isEqualTo(Region.JP);
        assertThat(MailTemplates.regionOf("ja_JP")).isEqualTo(Region.JP);
        assertThat(MailTemplates.regionOf("JA")).isEqualTo(Region.JP);

        assertThat(MailTemplates.regionOf("ko")).isEqualTo(Region.KR);
        assertThat(MailTemplates.regionOf(null)).isEqualTo(Region.KR);
        assertThat(MailTemplates.regionOf("xx")).isEqualTo(Region.KR);
    }

    @Test
    @DisplayName("평문 줄바꿈은 CRLF 다 — 일부 MTA 가 LF 만 있는 본문을 재작성한다")
    void crlf() {
        var r = MailTemplates.render(Region.KR, MailKind.PASSWORD_RESET,
                Map.of("resetUrl", "https://shop.example.com/reset"));
        assertThat(r.text()).contains("\r\n");
    }

    @Test
    @DisplayName("🔴 보안 메일의 제목은 알림 제목을 따른다 — 로그인 해제가 '새 기기' 메일로 나가면 안 된다")
    void 보안_메일_제목() {
        var revoked = MailTemplates.render(Region.KR, MailKind.SECURITY_ALERT,
                Map.of("subject", "보안을 위해 로그인이 해제되었습니다", "url", "https://shop.example.com/account/"));
        assertThat(revoked.subject()).isEqualTo("[쇼핑몰] 보안을 위해 로그인이 해제되었습니다");
        assertThat(revoked.subject()).doesNotContain("새로운 기기");
        assertThat(revoked.text()).contains("비밀번호").contains("수신 설정과 관계없이");
        // 수신 설정으로 끌 수 없는 메일에 "설정에서 바꿀 수 있다" 고 쓰면 거짓말이다
        assertThat(revoked.text()).doesNotContain("마이페이지에서 바꿀 수");

        var device = MailTemplates.render(Region.KR, MailKind.SECURITY_ALERT,
                Map.of("subject", "새로운 기기에서 로그인되었습니다"));
        assertThat(device.subject()).isEqualTo("[쇼핑몰] 새로운 기기에서 로그인되었습니다");
        // 예전에는 "새로운 기기에서 로그인되었습니다 — 새로운 기기에서 로그인되었습니다" 로 두 번 찍혔다
        assertThat(device.text()).containsOnlyOnce("새로운 기기에서 로그인되었습니다");
    }

    @Test
    @DisplayName("보안 메일 — 일본어, 제목이 비면 종류의 기본 문구")
    void 보안_메일_일본어() {
        var r = MailTemplates.render(Region.JP, MailKind.SECURITY_ALERT,
                Map.of("subject", "セキュリティ保護のためログインを解除しました"));
        assertThat(r.subject()).isEqualTo("[ショップ] セキュリティ保護のためログインを解除しました");
        assertThat(r.html()).contains("パスワードを変更").doesNotContain("비밀번호");

        assertThat(MailTemplates.render(Region.KR, MailKind.SECURITY_ALERT, Map.of()).subject())
                .isEqualTo("[쇼핑몰] 새로운 기기에서 로그인되었습니다");
    }

    @Test
    @DisplayName("보안 메일 제목의 줄바꿈은 없앤다 — 제목 헤더에 들어가는 값이다")
    void 보안_메일_제목_한_줄() {
        var r = MailTemplates.render(Region.KR, MailKind.SECURITY_ALERT,
                Map.of("subject", "로그인\r\nBcc: evil@example.com"));
        assertThat(r.subject()).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    @DisplayName("로그인 확인 코드 — 숫자 6자리만 싣고 링크가 없다(docs/68 §19)")
    void 로그인_확인_코드() {
        var ko = MailTemplates.render(Region.KR, MailKind.LOGIN_CHALLENGE,
                Map.of("code", "123456", "url", "https://evil.example.com"));
        assertThat(ko.subject()).isEqualTo("[쇼핑몰] 로그인 확인 코드 123456");
        assertThat(ko.text()).contains("확인 코드: 123456");
        assertThat(ko.html()).contains("123456").doesNotContain("href").doesNotContain("evil");

        var ja = MailTemplates.render(Region.JP, MailKind.LOGIN_CHALLENGE, Map.of("code", "654321"));
        assertThat(ja.subject()).isEqualTo("[ショップ] ログイン確認コード 654321");
        assertThat(ja.html()).doesNotContain("href").doesNotContain("로그인");

        // 코드 자리에 다른 것이 와도 숫자만, 6자리까지만 — 제목 헤더 주입도 막힌다.
        var odd = MailTemplates.render(Region.KR, MailKind.LOGIN_CHALLENGE,
                Map.of("code", "12<b>34\r\nBcc: x@y.z 5678"));
        assertThat(odd.subject()).endsWith(" 123456").doesNotContain("<").doesNotContain("Bcc");
    }
}
