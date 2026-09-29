package com.project.notification_service.mail;

import java.util.Locale;
import java.util.Map;

import com.project.notification_service.domain.MailKind;
import com.project.notification_service.sms.PhoneNumbers.Region;

/**
 * 메일 문구. 한국어·일본어. {@link com.project.notification_service.sms.SmsTemplates} 의 메일판이다.
 *
 * <p><b>왜 문구를 여기 모으나.</b> 예전에는 확인 메일 HTML 이 user-service 의
 * {@code RegistrationListener} 안에, 비밀번호 재설정 문구가 {@code EmailService} 안에 각각
 * 박혀 있었다. 브랜딩을 바꾸려면 두 파일을 고쳐야 했고, 실제로 두 메일의 모양이 서로 달랐다
 * (한쪽은 HTML, 한쪽은 평문 한 줄). 서비스가 늘면 그만큼 더 흩어진다.
 *
 * <p><b>SMS 와 달리 길이 제한이 사실상 없다.</b> {@code SmsMessage} 가 70자·90바이트를 세는 것은
 * 그게 곧 요금이기 때문인데, 메일은 그렇지 않다. 대신 메일에는 다른 제약이 있다 —
 * <b>HTML 을 못 보거나 안 보는 클라이언트</b>가 여전히 많고, 텍스트 대안이 없는 메일은 스팸
 * 점수가 올라간다. 그래서 모든 문구가 {@link Rendered#text()} 와 {@link Rendered#html()} 을
 * 함께 낸다.
 *
 * <p>⚠️ <b>모든 삽입값은 이스케이프한다.</b> {@code subject} 같은 값은 Kafka 이벤트에서 온다 —
 * 즉 우리가 만든 문자열이 아니다. 이스케이프 없이 HTML 에 넣으면 메일 본문에 임의의 마크업을
 * 심을 수 있고, 링크를 바꿔치기해 피싱에 쓸 수 있다. URL 은 추가로 스킴까지 검사한다
 * ({@link #safeUrl}) — {@code javascript:} 로 시작하는 href 를 넣지 못하게.
 */
public final class MailTemplates {

    private static final String BRAND_KO = "쇼핑몰";
    private static final String BRAND_JA = "ショップ";

    /** 버튼을 렌더링하지 못하는 클라이언트를 위한 안내. 언어별로 다르므로 상수로 둔다. */
    private static final String KO_LINK_HINT = "버튼이 동작하지 않으면 아래 주소를 브라우저에 붙여넣어 주세요.";
    private static final String JA_LINK_HINT = "ボタンが動作しない場合は、以下のURLをブラウザに貼り付けてください。";

    /** 보안 알림 — 무엇을 해야 하는지, 왜 설정과 무관하게 왔는지. */
    private static final String KO_SECURITY_ADVICE = "본인이 한 일이 아니라면 비밀번호를 바로 변경해 주세요.";
    private static final String KO_SECURITY_FOOTER = "보안 알림은 계정 보호를 위해 수신 설정과 관계없이 발송됩니다.";
    private static final String JA_SECURITY_ADVICE = "お心当たりがない場合は、すぐにパスワードを変更してください。";
    private static final String JA_SECURITY_FOOTER = "セキュリティ通知は、アカウント保護のため受信設定にかかわらず送信されます。";

    private MailTemplates() {
    }

    /** 렌더링 결과. 제목과 두 가지 본문. */
    public record Rendered(String subject, String text, String html) {
    }

    /**
     * 로케일 문자열을 지역으로 바꾼다. 모르는 값이면 한국어.
     *
     * <p>{@code ja}, {@code ja-JP}, {@code ja_JP} 를 모두 받는다 — 어디서 오느냐에 따라 표기가
     * 달라서(HTTP {@code Accept-Language} 는 하이픈, 자바 {@code Locale#toString} 은 밑줄)
     * 한쪽만 받으면 조용히 한국어로 떨어진다.
     */
    public static Region regionOf(String locale) {
        if (locale == null) {
            return Region.KR;
        }
        String lang = locale.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return lang.startsWith("ja") ? Region.JP : Region.KR;
    }

    /**
     * 종류별 문구를 만든다.
     *
     * @param params 없는 키는 빈 문자열로 채운다 — 문구가 조금 어색해질지언정
     *               {@code NullPointerException} 으로 발송 전체가 멈추면 안 된다(SmsTemplates 와 같은 규칙)
     */
    public static Rendered render(Region region, MailKind kind, Map<String, String> params) {
        Map<String, String> p = params == null ? Map.of() : params;
        return region == Region.JP ? renderJa(kind, p) : renderKo(kind, p);
    }

    // ────────────────────────────────────────────────────────────── 한국어

    private static Rendered renderKo(MailKind kind, Map<String, String> p) {
        String brand = BRAND_KO;

        return switch (kind) {
            case REGISTRATION_CONFIRM -> {
                String url = safeUrl(p.get("confirmationUrl"));
                yield new Rendered(
                        "[" + brand + "] 회원가입 확인",
                        body("회원가입을 완료하려면 아래 주소를 열어 주세요.", url,
                             "이 링크는 24시간 동안 유효합니다.",
                             "본인이 가입한 것이 아니라면 이 메일을 무시하셔도 됩니다."),
                        html("회원가입 확인", "회원가입을 완료하려면 아래 버튼을 눌러 주세요.",
                             "이메일 인증하기", url, KO_LINK_HINT,
                             "이 링크는 24시간 동안 유효합니다. 본인이 가입한 것이 아니라면 이 메일을 무시하셔도 됩니다."));
            }
            case PASSWORD_RESET -> {
                String url = safeUrl(p.get("resetUrl"));
                yield new Rendered(
                        "[" + brand + "] 비밀번호 재설정",
                        body("비밀번호를 재설정하려면 아래 주소를 열어 주세요.", url,
                             "이 링크는 한 번만 사용할 수 있습니다.",
                             "본인이 요청하지 않았다면 이 메일을 무시하고, 계정 비밀번호를 그대로 두셔도 됩니다."),
                        html("비밀번호 재설정", "아래 버튼을 눌러 새 비밀번호를 설정해 주세요.",
                             "비밀번호 재설정", url, KO_LINK_HINT,
                             "이 링크는 한 번만 사용할 수 있습니다. 본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다."));
            }
            case SECURITY_ALERT -> {
                // 보안 알림은 사건이 여럿이다(새 기기 · 로그인 해제, docs/68) — 제목은 종류가 아니라 알림 제목을 따른다.
                // 예전에는 종류로 고정("새로운 기기에서 로그인되었습니다")이라 다른 사건도 새 기기 메일로 나갈 뻔했다.
                // 끝 문장은 수신 설정 안내가 아니다 — 보안 알림은 설정으로 끌 수 없다(NotificationPreference).
                String subject = oneLine(p.getOrDefault("subject", ""));
                String headline = subject.isBlank() ? headlineKo(kind) : subject;
                String url = safeUrl(p.get("url"));
                yield new Rendered(
                        "[" + brand + "] " + headline,
                        body(headline, url, KO_SECURITY_ADVICE, KO_SECURITY_FOOTER),
                        html(headline, KO_SECURITY_ADVICE, "내 계정 확인", url, KO_LINK_HINT, KO_SECURITY_FOOTER));
            }
            default -> {
                String headline = headlineKo(kind);
                String detail = p.getOrDefault("subject", "");
                String url = safeUrl(p.get("url"));
                yield new Rendered(
                        "[" + brand + "] " + headline,
                        body(headline + (detail.isBlank() ? "" : " — " + detail), url,
                             "", "알림 수신 설정은 마이페이지에서 바꿀 수 있습니다."),
                        html(headline, detail.isBlank() ? headline : detail,
                             "자세히 보기", url, KO_LINK_HINT,
                             "알림 수신 설정은 마이페이지에서 바꿀 수 있습니다."));
            }
        };
    }

    private static String headlineKo(MailKind kind) {
        return switch (kind) {
            case ORDER_PLACED      -> "주문이 접수되었습니다";
            case PAYMENT_COMPLETED -> "결제가 완료되었습니다";
            case PAYMENT_FAILED    -> "결제에 실패했습니다";
            case PAYMENT_REFUNDED  -> "환불이 완료되었습니다";
            case SHIPPING_UPDATED  -> "배송 상태가 변경되었습니다";
            case ORDER_COMPLETED   -> "구매가 확정되었습니다";
            case RESTOCK_AVAILABLE -> "신청하신 상품이 재입고되었습니다";
            case COUPON_ISSUED     -> "쿠폰이 발급되었습니다";
            case SECURITY_ALERT    -> "새로운 기기에서 로그인되었습니다";
            default                -> "알림";
        };
    }

    // ────────────────────────────────────────────────────────────── 일본어

    private static Rendered renderJa(MailKind kind, Map<String, String> p) {
        String brand = BRAND_JA;

        return switch (kind) {
            case REGISTRATION_CONFIRM -> {
                String url = safeUrl(p.get("confirmationUrl"));
                yield new Rendered(
                        "[" + brand + "] 会員登録の確認",
                        body("会員登録を完了するには、以下のURLを開いてください。", url,
                             "このリンクは24時間有効です。",
                             "お心当たりのない場合は、このメールを破棄してください。"),
                        html("会員登録の確認", "会員登録を完了するには、以下のボタンを押してください。",
                             "メールアドレスを認証する", url, JA_LINK_HINT,
                             "このリンクは24時間有効です。お心当たりのない場合は、このメールを破棄してください。"));
            }
            case PASSWORD_RESET -> {
                String url = safeUrl(p.get("resetUrl"));
                yield new Rendered(
                        "[" + brand + "] パスワードの再設定",
                        body("パスワードを再設定するには、以下のURLを開いてください。", url,
                             "このリンクは一度だけ使用できます。",
                             "お心当たりのない場合は、このメールを破棄してください。パスワードは変更されません。"),
                        html("パスワードの再設定", "以下のボタンから新しいパスワードを設定してください。",
                             "パスワードを再設定する", url, JA_LINK_HINT,
                             "このリンクは一度だけ使用できます。お心当たりのない場合は、このメールを破棄してください。"));
            }
            case SECURITY_ALERT -> {
                // 제목은 알림 제목을 따른다(한국어 분기 주석). subject 는 이미 일본어로 바뀌어 온다(NotificationService.enqueueMail).
                // [JA] セキュリティ通知は出来事が複数ある — 件名は種類ではなく通知タイトルに従う。
                String subject = oneLine(p.getOrDefault("subject", ""));
                String headline = subject.isBlank() ? headlineJa(kind) : subject;
                String url = safeUrl(p.get("url"));
                yield new Rendered(
                        "[" + brand + "] " + headline,
                        body(headline, url, JA_SECURITY_ADVICE, JA_SECURITY_FOOTER),
                        html(headline, JA_SECURITY_ADVICE, "アカウントを確認する", url, JA_LINK_HINT, JA_SECURITY_FOOTER));
            }
            default -> {
                String headline = headlineJa(kind);
                String detail = p.getOrDefault("subject", "");
                String url = safeUrl(p.get("url"));
                yield new Rendered(
                        "[" + brand + "] " + headline,
                        body(headline + (detail.isBlank() ? "" : " — " + detail), url,
                             "", "通知の受信設定はマイページから変更できます。"),
                        html(headline, detail.isBlank() ? headline : detail,
                             "詳細を見る", url, JA_LINK_HINT,
                             "通知の受信設定はマイページから変更できます。"));
            }
        };
    }

    private static String headlineJa(MailKind kind) {
        return switch (kind) {
            case ORDER_PLACED      -> "ご注文を受け付けました";
            case PAYMENT_COMPLETED -> "お支払いが完了しました";
            case PAYMENT_FAILED    -> "お支払いに失敗しました";
            case PAYMENT_REFUNDED  -> "返金が完了しました";
            case SHIPPING_UPDATED  -> "配送状況が更新されました";
            case ORDER_COMPLETED   -> "購入が確定しました";
            case RESTOCK_AVAILABLE -> "ご登録の商品が再入荷しました";
            case COUPON_ISSUED     -> "クーポンが発行されました";
            case SECURITY_ALERT    -> "アカウントに新しいログインがありました";
            default                -> "お知らせ";
        };
    }

    // ────────────────────────────────────────────────────────────── 조립

    /**
     * 평문 본문. 줄바꿈은 {@code \r\n} 이다 — RFC 5322 가 요구하고, 일부 MTA 는 {@code \n} 만
     * 있는 본문을 거부하거나 재작성한다.
     */
    private static String body(String intro, String url, String note1, String note2) {
        StringBuilder sb = new StringBuilder(intro);
        if (!url.isBlank()) {
            sb.append("\r\n\r\n").append(url);
        }
        if (!note1.isBlank()) {
            sb.append("\r\n\r\n").append(note1);
        }
        if (!note2.isBlank()) {
            sb.append("\r\n").append(note2);
        }
        return sb.toString();
    }

    /**
     * HTML 본문.
     *
     * <p>인라인 스타일만 쓴다 — 메일 클라이언트 상당수가 {@code <style>} 블록과 외부 CSS 를
     * 제거한다. 그리고 URL 이 없으면 버튼 자체를 넣지 않는다(빈 {@code href} 는 클릭하면
     * 현재 페이지로 가는 이상한 링크가 된다).
     */
    private static String html(String title, String intro, String buttonLabel, String url,
                               String fallbackHint, String footer) {
        String button = url.isBlank() ? "" : """
                  <p style="margin:28px 0">
                    <a href="%s"
                       style="background:#f0c14b;border:1px solid #a88734;border-radius:4px;
                              color:#111;display:inline-block;padding:12px 28px;
                              text-decoration:none;font-weight:600">%s</a>
                  </p>
                  <p style="color:#666;font-size:13px">
                    %s<br>
                    <span style="word-break:break-all">%s</span>
                  </p>
                """.formatted(escape(url), escape(buttonLabel), escape(fallbackHint), escape(url));

        return """
                <div style="font-family:sans-serif;max-width:520px;margin:0 auto;padding:24px">
                  <h2 style="margin:0 0 16px">%s</h2>
                  <p style="color:#333;line-height:1.6">%s</p>
                %s  <hr style="border:none;border-top:1px solid #eee;margin:24px 0">
                  <p style="color:#888;font-size:12px">%s</p>
                </div>
                """.formatted(escape(title), escape(intro), button, escape(footer));
    }

    /** 메일 제목 헤더에 들어가는 값 — 줄바꿈을 없앤다(헤더 주입 · 접힘 방지). */
    private static String oneLine(String v) {
        return v == null ? "" : v.replaceAll("[\\r\\n]+", " ").strip();
    }

    /**
     * HTML 특수문자를 이스케이프한다.
     *
     * <p>삽입값 중 {@code subject} 는 Kafka 이벤트에서 온다 — 우리가 만든 문자열이 아니다.
     * 그대로 넣으면 메일 본문에 임의 마크업을 심을 수 있고, 그중에서도 실제 피해가 되는 것은
     * <b>링크 바꿔치기</b>다(우리 브랜드로 온 메일 안의 가짜 로그인 링크).
     *
     * <p>작은따옴표까지 처리하는 이유: 속성값을 큰따옴표로 감싸고 있지만, 나중에 누가 작은따옴표로
     * 바꿔도 안전하도록. 이스케이프는 문맥에 덜 의존할수록 오래 간다.
     */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * href 에 넣어도 되는 URL 인지 확인한다. 아니면 빈 문자열.
     *
     * <p><b>왜 스킴을 검사하나.</b> 이스케이프만으로는 {@code javascript:alert(1)} 같은 값을
     * 막지 못한다 — 특수문자가 없어서 그대로 통과하고, 메일 클라이언트에 따라 실행된다.
     * {@code data:} 도 마찬가지로 임의 문서를 열 수 있다. 허용 목록으로 막는 것이 맞다.
     *
     * <p>URL 은 우리가 만든 값이라 지금은 이 검사에 걸릴 일이 없다. 그래도 두는 이유는, 이
     * 파라미터가 Kafka 를 건너 오기 때문이다 — 언젠가 다른 서비스가 이 토픽에 쓰게 되면
     * 그때는 우리가 만든 값이 아니게 된다.
     */
    static String safeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return trimmed;
        }
        return "";
    }
}
