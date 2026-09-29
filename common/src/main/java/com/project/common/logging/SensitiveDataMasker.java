package com.project.common.logging;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그의 민감정보를 가리는 규칙 — <b>출력 직전에</b> 모든 서비스의 로그에 적용된다.
 *
 * <h3>왜 출력 단계인가</h3>
 *
 * <p>예전에는 부르는 쪽이 기억해서 가렸다({@code EmailAddresses.mask}, {@code PhoneNumbers.mask},
 * {@code DeviceIdentity.maskIp}). 알림 서비스는 잘 지켰지만 cart·order·user 서비스에는 이메일을
 * 그대로 찍는 로그가 스무 곳 가까이 있었다. <b>"기억해서 부르는" 방식은 반드시 한 곳에서 샌다.</b>
 * 그래서 logback 이 줄을 쓰기 직전에 강제로 거른다 — 콘솔은 {@link MaskingPatternConverter}
 * ({@code %mask(...)}), JSON 은 {@link SensitiveValueMasker} 가 이 클래스를 부른다.
 * 부르는 쪽의 마스킹은 그대로 둔다(겹쳐도 해가 없다).
 *
 * <h3>무엇을 가리나</h3>
 *
 * <ol>
 *   <li>Bearer/Basic 토큰 — key=value 규칙보다 <b>먼저</b>. 아니면 "Authorization: Bearer xxx" 에서
 *       "Bearer" 만 가려지고 토큰이 남는다.</li>
 *   <li>JWT</li>
 *   <li>password·secret·api_key·access_token·refresh_token·authorization·credential 의 값</li>
 *   <li>주민등록번호</li>
 *   <li>카드번호(16자리) — 앞 6 · 뒤 4 만 남기는 PCI-DSS 관례</li>
 *   <li>국제 표기 휴대폰(E.164, {@code +8210…}·{@code +8190…}) — 이 프로젝트가 번호를 저장하는 형식이다</li>
 *   <li>한국 휴대폰(010…)·일본 휴대폰(070/080/090…)</li>
 *   <li>이메일 — 첫 글자와 도메인만</li>
 * </ol>
 *
 * <h3>가리지 않는 것 — 일부러</h3>
 *
 * <ul>
 *   <li><b>인증번호.</b> 여섯 자리 숫자는 주문 번호·금액·수량과 구별이 안 된다. 인증번호는 애초에
 *       로그로 넘기지 않는다({@code LoggingSmsSender}).</li>
 *   <li><b>IP.</b> Kafka·DB 접속 주소처럼 운영자가 봐야 하는 주소와 구별이 안 된다. 사용자 IP 는
 *       저장할 때 이미 가린다({@code DeviceIdentity.maskIp}).</li>
 *   <li><b>주문 번호(TSID 18~19자리)·UUID·금액·시각.</b> 오탐하면 장애 추적이 막힌다.
 *       {@code SensitiveDataMaskerTest} 가 실제 로그 모양으로 고정한다.</li>
 * </ul>
 *
 * <p>logback 이 스프링 컨테이너 밖에서 직접 부르므로 순수 static 유틸이다. 정규식은 전부 미리
 * 컴파일한다 — 줄마다 컴파일하면 그 자체가 성능 장애다. 가리다가 예외가 나면 원문 대신
 * {@link #MASKING_FAILED} 를 돌려준다 — 원문을 흘리는 것보다 한 줄을 잃는 편이 낫다.
 *
 * <p>규칙의 뼈대는 ruke79/facebook 의 로깅 실습 서비스(backend-spring)에서 옮겼다. E.164 와
 * 일본 번호, 검사 길이 상한은 이 프로젝트에 맞춰 더하고 바꿨다.
 *
 * <p>[JA] ログの機微情報を隠すルール — <b>出力の直前に</b>全サービスのログへ適用される。
 *
 * <h3>なぜ出力段階なのか</h3>
 *
 * <p>以前は呼び出し側が覚えていて隠していた({@code EmailAddresses.mask}, {@code PhoneNumbers.mask},
 * {@code DeviceIdentity.maskIp})。通知サービスはきちんと守っていたが、cart・order・user サービスにはメールアドレスを
 * そのまま出力するログが二十箇所近くあった。<b>「覚えていて呼ぶ」方式は必ずどこか一箇所で漏れる。</b>
 * そこで logback が行を書き込む直前に強制的にフィルタリングする — コンソールは {@link MaskingPatternConverter}
 * ({@code %mask(...)})、JSON は {@link SensitiveValueMasker} がこのクラスを呼ぶ。
 * 呼び出し側のマスキングはそのまま残す(重なっても害はない)。
 *
 * <h3>何を隠すか</h3>
 *
 * <ol>
 *   <li>Bearer/Basic トークン — key=value ルールより<b>先に</b>。そうしないと "Authorization: Bearer xxx" で
 *       "Bearer" だけが隠れてトークンが残る。</li>
 *   <li>JWT</li>
 *   <li>password・secret・api_key・access_token・refresh_token・authorization・credential の値</li>
 *   <li>住民登録番号(韓国)</li>
 *   <li>カード番号(16桁) — 先頭 6・末尾 4 だけを残す PCI-DSS の慣例</li>
 *   <li>国際表記の携帯電話番号(E.164, {@code +8210…}・{@code +8190…}) — このプロジェクトが番号を保存する形式である</li>
 *   <li>韓国の携帯電話番号(010…)・日本の携帯電話番号(070/080/090…)</li>
 *   <li>メールアドレス — 先頭の一文字とドメインだけ</li>
 * </ol>
 *
 * <h3>隠さないもの — 意図的に</h3>
 *
 * <ul>
 *   <li><b>認証番号。</b> 六桁の数字は注文番号・金額・数量と区別がつかない。認証番号はそもそも
 *       ログに渡さない({@code LoggingSmsSender})。</li>
 *   <li><b>IP。</b> Kafka・DB の接続先アドレスのように運用者が見る必要のあるアドレスと区別がつかない。ユーザーの IP は
 *       保存する時点ですでに隠している({@code DeviceIdentity.maskIp})。</li>
 *   <li><b>注文番号(TSID 18~19桁)・UUID・金額・時刻。</b> 誤検知すると障害のトレースができなくなる。
 *       {@code SensitiveDataMaskerTest} が実際のログの形で固定している。</li>
 * </ul>
 *
 * <p>logback が Spring コンテナの外から直接呼ぶので、純粋な static ユーティリティである。正規表現はすべて事前に
 * コンパイルする — 行ごとにコンパイルすると、それ自体が性能障害になる。隠す途中で例外が起きたら原文の代わりに
 * {@link #MASKING_FAILED} を返す — 原文を漏らすより一行を失う方がましである。
 *
 * <p>ルールの骨格は ruke79/facebook のロギング演習サービス(backend-spring)から移した。E.164 と
 * 日本の番号、検査長の上限はこのプロジェクトに合わせて追加・変更した。
 */
public final class SensitiveDataMasker {

    /** 가리다가 예외가 났을 때 원문 대신 남기는 값. */
    // [JA] 隠す途中で例外が起きたときに、原文の代わりに残す値。
    public static final String MASKING_FAILED = "[MASKING_ERROR]";

    /**
     * 이보다 긴 문자열은 잘라서 검사한다. 정규식 비용이 길이에 비례해서다.
     *
     * <p>facebook 쪽은 16KB 였다. 콘솔은 스택트레이스도 이 필터를 지나는데, 스프링의 깊은
     * 스택은 16KB 를 쉽게 넘어 <b>정작 원인(Caused by)이 잘려 나간다.</b> 64KB 로 올렸다.
     *
     * <p>[JA] これより長い文字列は切り詰めて検査する。正規表現のコストが長さに比例するためである。
     *
     * <p>facebook 側は 16KB だった。コンソールはスタックトレースもこのフィルターを通るが、Spring の深い
     * スタックは 16KB を簡単に超え、<b>肝心の原因(Caused by)が切り落とされる。</b> 64KB に引き上げた。
     */
    static final int MAX_SCAN_LENGTH = 65_536;

    static final String TRUNCATED_SUFFIX = "...(truncated)";

    private static final List<MaskRule> RULES = List.of(
            // 1) Authorization 헤더의 토큰.
            // [JA] 1) Authorization ヘッダーのトークン。
            new MaskRule(Pattern.compile("(?i)\\b(Bearer|Basic)\\s+([A-Za-z0-9._~+/=-]{8,})"),
                    m -> m.group(1) + " ****"),

            // 2) JWT (헤더.페이로드.서명)
            // [JA] 2) JWT (ヘッダー.ペイロード.署名)
            new MaskRule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b"),
                    m -> "eyJ****.****.****"),

            // 3) key=value / "key":"value" 의 비밀값. 닫는 따옴표는 매치 밖이라 원문에 남는다 —
            //    여기서 따옴표를 덧붙이면 "password":"****"" 처럼 JSON 이 깨진다.
            // [JA] 3) key=value / "key":"value" の秘密値。閉じ引用符はマッチの外なので原文に残る —
            //      ここで引用符を付け足すと "password":"****"" のように JSON が壊れる。
            new MaskRule(Pattern.compile(
                    "(?i)([\"']?\\b(?:password|passwd|pwd|secret|client[_-]?secret|api[_-]?key|"
                            + "access[_-]?token|refresh[_-]?token|authorization|credential)\\b[\"']?\\s*[:=]\\s*)"
                            + "([\"']?)([^\"',;}\\s]+)",
                    Pattern.UNICODE_CASE),
                    m -> m.group(1) + m.group(2) + "****"),

            // 4) 주민등록번호
            // [JA] 4) 住民登録番号(韓国)
            new MaskRule(Pattern.compile("\\b(\\d{6})-([1-8])\\d{6}\\b"),
                    m -> m.group(1) + "-" + m.group(2) + "******"),

            // 5) 카드번호 16자리(구분자 허용). 양끝이 단어 경계라 18~19자리 TSID 안에서는 걸리지 않는다.
            // [JA] 5) カード番号 16桁(区切り文字を許容)。両端が単語境界なので、18~19桁の TSID の中では引っかからない。
            new MaskRule(Pattern.compile("\\b(\\d{4})[- ]?(\\d{4})[- ]?(\\d{2})(\\d{2})[- ]?(\\d{4})\\b"),
                    m -> m.group(1) + "-" + m.group(2) + "-" + m.group(3) + "**-****"),

            // 6) E.164 휴대폰 — 국가번호 두 자리 + 가운데 + 끝 네 자리. 국내 규칙보다 먼저 둔다.
            //    PhoneNumbers.mask 가 이미 가린 값(+8210****5678)은 가운데가 숫자가 아니라 걸리지 않는다.
            // [JA] 6) E.164 携帯電話番号 — 国番号二桁 + 中間 + 末尾四桁。国内ルールより先に置く。
            //      PhoneNumbers.mask がすでに隠した値(+8210****5678)は中間が数字ではないので引っかからない。
            new MaskRule(Pattern.compile("(\\+\\d{2})\\d{4,9}(\\d{4})\\b"),
                    m -> m.group(1) + "****" + m.group(2)),

            // 7) 한국 휴대폰
            // [JA] 7) 韓国の携帯電話番号
            new MaskRule(Pattern.compile("\\b(01[016789])[- ]?(\\d{3,4})[- ]?(\\d{4})\\b"),
                    m -> m.group(1) + "-****-" + m.group(3)),

            // 8) 일본 휴대폰(070/080/090)
            // [JA] 8) 日本の携帯電話番号(070/080/090)
            new MaskRule(Pattern.compile("\\b(0[789]0)[- ]?(\\d{4})[- ]?(\\d{4})\\b"),
                    m -> m.group(1) + "-****-" + m.group(3)),

            // 9) 이메일 — 첫 글자와 도메인만 남긴다.
            // [JA] 9) メールアドレス — 先頭の一文字とドメインだけを残す。
            new MaskRule(Pattern.compile("\\b([A-Za-z0-9._%+-])([A-Za-z0-9._%+-]*)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b"),
                    m -> m.group(1) + "****@" + m.group(3))
    );

    private SensitiveDataMasker() {
    }

    /**
     * @return 가린 문자열. 비었으면 그대로, 가리다 실패하면 {@link #MASKING_FAILED}
     *         [JA] 隠した文字列。空ならそのまま、隠すのに失敗したら {@link #MASKING_FAILED}
     */
    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        try {
            String target = input.length() > MAX_SCAN_LENGTH
                    ? input.substring(0, MAX_SCAN_LENGTH) + TRUNCATED_SUFFIX
                    : input;
            for (MaskRule rule : RULES) {
                target = rule.apply(target);
            }
            return target;
        } catch (RuntimeException e) {
            return MASKING_FAILED;
        }
    }

    /** 가릴 것이 있는가. JSON 쪽에서 가릴 것이 없는 값에 새 문자열을 만들지 않으려고 쓴다. */
    // [JA] 隠すものがあるか。JSON 側で、隠すもののない値に対して新しい文字列を作らないために使う。
    public static boolean containsSensitiveData(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        if (input.length() > MAX_SCAN_LENGTH) {
            return true;   // mask() 가 자른다 — 그 결과를 써야 한다 ／ [JA] mask() が切り詰める — その結果を使う必要がある
        }
        for (MaskRule rule : RULES) {
            if (rule.pattern().matcher(input).find()) {
                return true;
            }
        }
        return false;
    }

    private record MaskRule(Pattern pattern, Function<Matcher, String> replacer) {

        String apply(String input) {
            Matcher matcher = pattern.matcher(input);
            if (!matcher.find()) {
                return input;
            }
            StringBuilder sb = new StringBuilder(input.length());
            do {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(matcher)));
            } while (matcher.find());
            matcher.appendTail(sb);
            return sb.toString();
        }
    }
}
