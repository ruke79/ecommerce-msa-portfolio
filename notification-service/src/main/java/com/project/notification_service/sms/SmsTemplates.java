package com.project.notification_service.sms;

import java.util.Map;

import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.sms.PhoneNumbers.Region;

/**
 * SMS 문구. 한국어·일본어.
 *
 * <p><b>왜 앱 안 문구를 그대로 쓰지 않나.</b> 화면에 보여줄 본문은 길어도 되지만 SMS 는 길이가
 * 곧 돈이다 — 한글·일본어가 섞이면 UCS-2 로 인코딩되어 <b>70자</b>가 한 건의 한계이고, 한국
 * 사업자는 90바이트를 넘으면 LMS 로 전환해 <b>요금이 3~4배</b>가 된다({@link SmsMessage}).
 * 그래서 SMS 전용으로 짧게 다시 쓴다.
 *
 * <p><b>왜 발신자 이름을 넣나.</b> 한국에서는 광고성이 아닌 정보성 문자라도 발신 주체가
 * 불분명하면 스팸으로 신고·차단되기 쉽다. 앞머리에 서비스명을 둔다.
 *
 * <p>⚠️ 광고·마케팅 문자는 이 클래스에 넣지 말 것. 한국은 <b>사전 수신동의와 (광고) 표기,
 * 야간 발송 제한</b>이 법으로 요구된다(정보통신망법). 여기 있는 것은 전부 사용자가 일으킨
 * 행위에 대한 <b>정보성</b> 통지다.
 *
 * <p>[JA] SMS 文言。韓国語・日本語。<b>なぜアプリ内の文言をそのまま使わないのか。</b>画面に見せる本文は長くてもよい
 * が、SMS は長さがすなわちお金だ — ハングル・日本語が混じると UCS-2 でエンコードされ<b>70
 * 字</b>が一通の限界で、韓国の事業者は 90 バイトを超えると LMS に切り替えて<b>料金が 3~4
 * 倍</b>になる({@link SmsMessage})。そこで SMS 専用に短く書き直す。
 * <b>なぜ送信者名を入れるのか。</b>韓国では広告でない情報 SMS でも送信主体が不明確だとスパムとして通報・遮
 * 断されやすい。先頭にサービス名を置く。⚠️ 広告・マーケティング SMS はこのクラスに入れないこと。
 * 韓国は<b>事前の受信同意と(広告)表記、夜間送信制限</b>が法律で求められる(情報通信網法)。
 * ここにあるのはすべてユーザーが起こした行為に対する<b>情報</b>通知だ。
 */
public final class SmsTemplates {

    private static final String BRAND_KO = "[쇼핑몰]";
    private static final String BRAND_JA = "[ショップ]";

    private SmsTemplates() {
    }

    /** 인증번호 문구. 두 나라 모두 "코드는 누구에게도 알려주지 말라"를 넣는다 — 보이스피싱 방어. */
    // [JA] 認証番号の文言。両国とも「コードは誰にも教えるな」を入れる — ボイスフィッシング対策。
    public static String verificationCode(Region region, String code) {
        return switch (region) {
            case JP -> BRAND_JA + " 認証番号 " + code + "（" + PhoneVerificationTtl.MINUTES
                    + "分間有効）他人に教えないでください。";
            case KR -> BRAND_KO + " 인증번호 " + code + " (" + PhoneVerificationTtl.MINUTES
                    + "분 유효) 타인에게 알려주지 마세요.";
        };
    }

    /**
     * 알림 종류별 문구.
     *
     * <p>[JA] 通知種類別の文言。
     *
     * @param params 종류마다 필요한 값. 없으면 그 자리는 비워 둔다 — 문구가 깨질지언정
     *               NullPointerException 으로 알림 처리 전체가 멈추면 안 된다.
     *               [JA] 種類ごとに必要な値。なければその箇所は空けておく — 文言が崩れても
     *                    NullPointerException で通知処理全体が止まってはならない。
     */
    public static String forNotification(Region region, NotificationType type, Map<String, String> params) {
        String brand = region == Region.JP ? BRAND_JA : BRAND_KO;
        String v = params == null ? "" : params.getOrDefault("subject", "");
        if (region == Region.JP) {
            // "주문 123" 처럼 서버가 한국어로 만든 제목 — 일본 번호로 가는 문자에는 바꿔 넣는다(docs/60 §9). 상품·쿠폰 이름은 그대로.
            // [JA] 「注文 123」のようにサーバーが韓国語で作った件名 — 日本の番号へ行く SMS
            //      には置き換えて入れる(docs/60 §9)。商品・クーポン名はそのまま。
            v = com.project.common.i18n.ClientText.localize(v, java.util.Locale.JAPANESE);
        }

        if (region == Region.JP) {
            return brand + " " + switch (type) {
                case ORDER_PLACED       -> "ご注文を受け付けました。" + v;
                case PAYMENT_COMPLETED  -> "お支払いが完了しました。" + v;
                case PAYMENT_FAILED     -> "お支払いに失敗しました。ご確認ください。" + v;
                case PAYMENT_REFUNDED   -> "返金が完了しました。" + v;
                case SHIPPING_UPDATED   -> "配送状況が更新されました。" + v;
                case ORDER_COMPLETED    -> "購入が確定しました。" + v;
                case RESTOCK_AVAILABLE  -> "ご登録の商品が再入荷しました。" + v;
                case COUPON_ISSUED      -> "クーポンが発行されました。" + v;
                // 보안 알림은 사건이 여럿이다(새 기기 · 로그인 해제) — 알림 제목을 앞에 쓰고, 할 일은 같다.
                // [JA] セキュリティ通知は事象が複数ある(新しい端末・ログイン解除) — 通知件名を先頭に書き、
                //      すべきことは同じ。
                case SECURITY_ALERT     -> (v.isBlank() ? "アカウントに新しいログインがありました" : v)
                        + "。心当たりがない場合はパスワードを変更してください。";
                case CHAT_MESSAGE       -> "新しいメッセージがあります。";
                case FRIEND_ONLINE      -> "友達がオンラインになりました。";
                // 공지·관리자 안내는 SMS 로 보내지 않는다(아래 한국어 분기 주석 참조).
                // 여기 오지 않지만 switch 는 모든 값을 덮어야 한다.
                // [JA] お知らせ・管理者案内は SMS で送らない(下の韓国語分岐のコメント参照)。ここには来ないが switch
                //      はすべての値を網羅する必要がある。
                case NOTICE_PUBLISHED   -> "新しいお知らせがあります。" + v;
                case ANNOUNCEMENT       -> v.isEmpty() ? "お知らせがあります。" : v;
                case EVENT_STARTED      -> "セールが始まりました。" + v;
            };
        }

        return brand + " " + switch (type) {
            case ORDER_PLACED       -> "주문이 접수되었습니다. " + v;
            case PAYMENT_COMPLETED  -> "결제가 완료되었습니다. " + v;
            case PAYMENT_FAILED     -> "결제에 실패했습니다. 확인해 주세요. " + v;
            case PAYMENT_REFUNDED   -> "환불이 완료되었습니다. " + v;
            case SHIPPING_UPDATED   -> "배송 상태가 변경되었습니다. " + v;
            case ORDER_COMPLETED    -> "구매가 확정되었습니다. " + v;
            case RESTOCK_AVAILABLE  -> "신청하신 상품이 재입고되었습니다. " + v;
            case COUPON_ISSUED      -> "쿠폰이 발급되었습니다. " + v;
            // 보안 알림은 사건이 여럿이다(새 기기 · 로그인 해제, docs/68) — 예전에는 종류로 고정이라 어떤 사건이든 "새 기기" 문자였다.
            // [JA] セキュリティ通知は事象が複数ある(新しい端末・ログイン解除、docs/68) — 以前は種類で固定だったため、
            //      どんな事象でも「新しい端末」の SMS だった。
            case SECURITY_ALERT     -> (v.isBlank() ? "새로운 기기에서 로그인되었습니다" : v)
                    + ". 본인이 아니면 비밀번호를 변경하세요.";
            case CHAT_MESSAGE       -> "새 메시지가 도착했습니다.";
            case FRIEND_ONLINE      -> "친구가 접속했습니다.";

            /*
             * 공지·관리자 안내는 **SMS 로 나가지 않는다.** 두 종류 모두 smsWorthy=false 이고
             * (NotificationType), 무엇보다 전체 사용자 대상이라 문자를 태우면 사용자 수만큼
             * 요금이 나가는 사고다. 개인 알림 테이블에 들어가지도 않으므로 SMS 발송 경로가
             * 애초에 이 값을 만나지 않는다.
             *
             * 그래도 문구를 둔다 — switch 가 모든 값을 덮어야 컴파일되고, 여기서 예외를
             * 던지면 "언젠가 한 번" 알림 처리 전체가 멈춘다. 빠뜨린 것이 아니라 보내지
             * 않기로 한 것임을 코드로 남긴다.
             *
             * <p>[JA] お知らせ・管理者案内は**SMS で出ない。**どちらの種類も smsWorthy=false で(NotificationType)、
             * 何より全ユーザー向けなので SMS に乗せるとユーザー数分の料金がかかる事故だ。
             * 個人通知テーブルにも入らないので、SMS 送信経路がそもそもこの値に出会わない。
             * それでも文言を置く — switch がすべての値を網羅しないとコンパイルできず、
             * ここで例外を投げると「いつか一度」通知処理全体が止まる。
             * 漏らしたのではなく送らないと決めたことをコードで残す。
             */
            case NOTICE_PUBLISHED   -> "새 공지사항이 등록되었습니다. " + v;
            case ANNOUNCEMENT       -> v.isEmpty() ? "안내 사항이 있습니다." : v;
            case EVENT_STARTED      -> "기획전이 시작되었습니다. " + v;
        };
    }

    /** TTL 을 문구에 넣기 위한 상수 참조. 엔티티 상수와 어긋나지 않게 한 곳에서만 읽는다. */
    // [JA] TTL を文言に入れるための定数参照。エンティティの定数とずれないよう一か所でのみ読む。
    static final class PhoneVerificationTtl {
        static final int MINUTES = com.project.notification_service.domain.PhoneVerification.TTL_MINUTES;
        private PhoneVerificationTtl() {}
    }
}
