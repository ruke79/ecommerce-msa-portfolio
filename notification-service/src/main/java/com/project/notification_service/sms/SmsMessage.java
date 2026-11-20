package com.project.notification_service.sms;

import java.nio.charset.StandardCharsets;

/**
 * SMS 본문 길이 규칙. 한국·일본 문자를 다루므로 바이트 계산이 필요하다.
 *
 * <p><b>왜 문자 수로 세면 안 되나.</b> SMS 는 GSM-7 인코딩이면 160자지만, 한글·일본어가 하나라도
 * 섞이면 UCS-2 로 바뀌어 <b>70자</b>가 한계다. 한국 사업자는 여기에 더해 EUC-KR 기준
 * <b>90바이트</b>를 SMS/LMS 경계로 삼는 관행이 있다 — 넘으면 LMS 로 자동 전환되고 <b>요금이
 * 3~4배</b>가 된다.
 *
 * <p>즉 "짧게 쓰세요"는 미학이 아니라 비용이다. 문구가 조용히 경계를 넘으면 청구서에서야 안다.
 * 그래서 경계를 코드로 못박고, 넘으면 자르되 잘렸다는 사실을 로그로 남긴다.
 *
 * <p>[JA] SMS 本文の長さ規則。韓国語・日本語の文字を扱うのでバイト計算が必要だ。
 * <b>なぜ文字数で数えてはいけないのか。</b>SMS は GSM-7 エンコーディングなら 160 字だが、
 * ハングル・日本語が一つでも混じると UCS-2 に変わり<b>70 字</b>が限界になる。韓国の事業者はさらに EUC-KR
 * 基準の<b>90 バイト</b>を SMS/LMS の境界とする慣行がある — 超えると LMS に自動切り替えされ<b>料金が 3~4
 * 倍</b>になる。つまり「短く書け」は美学ではなくコストだ。文言が黙って境界を越えると請求書で初めて知る。
 * そこで境界をコードで固定し、超えたら切るが、切ったという事実はログに残す。
 */
public final class SmsMessage {

    /** 한국 사업자들이 SMS/LMS 를 가르는 관행적 경계(EUC-KR 바이트). */
    // [JA] 韓国の事業者が SMS/LMS を分ける慣行的な境界(EUC-KR バイト)。
    public static final int SMS_BYTE_LIMIT = 90;

    /** UCS-2 로 인코딩될 때의 문자 수 한계. 한글/일본어가 섞이면 이쪽이 먼저 걸린다. */
    // [JA] UCS-2 でエンコードされるときの文字数の限界。ハングル/日本語が混じるとこちらが先に引っかかる。
    public static final int UCS2_CHAR_LIMIT = 70;

    private SmsMessage() {
    }

    /** ASCII 가 아닌 문자가 하나라도 있으면 UCS-2 로 인코딩된다. */
    // [JA] ASCII でない文字が一つでもあれば UCS-2 でエンコードされる。
    public static boolean isUnicode(String text) {
        return !StandardCharsets.US_ASCII.newEncoder().canEncode(text);
    }

    /** 이 문구가 SMS 한 건에 들어가는지. 넘으면 LMS 요금이 적용된다. */
    // [JA] この文言が SMS 一通に収まるか。超えると LMS 料金が適用される。
    public static boolean fitsInSingleSms(String text) {
        if (text == null) {
            return true;
        }
        if (isUnicode(text)) {
            return text.length() <= UCS2_CHAR_LIMIT;
        }
        return text.getBytes(StandardCharsets.US_ASCII).length <= 160;
    }

    /**
     * 한 건에 들어가도록 자른다. 자를 때는 말줄임표를 붙여 잘렸음을 수신자도 알 수 있게 한다.
     *
     * <p><b>단위가 코드포인트가 아니라 UTF-16 이다.</b> 처음엔 코드포인트로 셌는데, 그러면
     * 이모지가 섞였을 때 자르고 나서도 여전히 한 건을 넘는다 — 테스트가 그걸 잡았다.
     * UCS-2 로 인코딩되는 SMS 는 BMP 밖 문자(이모지 등)를 <b>2 단위로</b> 센다. 즉 과금
     * 기준은 코드포인트가 아니라 UTF-16 길이이고, {@link #fitsInSingleSms} 가 처음부터
     * 그렇게 세고 있었다. 둘이 어긋나 있었던 것이다.
     *
     * <p>다만 {@code substring} 으로 그냥 자르면 서로게이트 쌍을 반으로 쪼개 깨진 문자가
     * 전송된다. 경계가 쌍 한가운데면 한 칸 물러선다.
     *
     * <p>[JA] 一通に収まるよう切る。切るときは省略記号を付けて、切れたことを受信者にもわかるようにする。
     * <b>単位はコードポイントではなく UTF-16 だ。</b>最初はコードポイントで数えたが、
     * そうすると絵文字が混じったとき切った後もまだ一通を超える — テストがそれを捉えた。UCS-2
     * でエンコードされる SMS は BMP 外の文字(絵文字など)を<b>2 単位で</b>数える。
     * つまり課金基準はコードポイントではなく UTF-16 長であり、{@link #fitsInSingleSms}
     * は最初からそう数えていた。二つがずれていたのだ。ただし {@code substring}
     * で単純に切るとサロゲートペアを半分に割って壊れた文字が送信される。境界がペアの真ん中なら一つ下がる。
     */
    public static String truncate(String text) {
        if (text == null || fitsInSingleSms(text)) {
            return text;
        }
        int limit = isUnicode(text) ? UCS2_CHAR_LIMIT : 160;
        int end = Math.max(1, limit - 1);   // 말줄임표 한 자리 ／ [JA] 省略記号の一文字分

        // 서로게이트 쌍을 쪼개지 않는다. 경계 직전이 상위 서로게이트면 그 문자를 통째로 뺀다.
        // [JA] サロゲートペアを割らない。境界の直前が上位サロゲートならその文字ごと抜く。
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end -= 1;
        }
        return text.substring(0, end) + "…";
    }
}
