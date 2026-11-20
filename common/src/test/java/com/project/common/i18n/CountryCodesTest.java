package com.project.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🐛 <b>회귀 대상 — 결제 이벤트가 통째로 DLT 로 갔다.</b>
 *
 * <p>주소 폼은 나라를 이름으로 저장하는데 배송 테이블의 {@code country} 는 {@code varchar(2)} 라,
 * 첫 배송을 만들려는 순간 {@code value too long for type character varying(2)} 로 죽었다.
 * 그 예외가 결제 레코드를 재시도 끝에 DLT 로 보냈고, 화면에는 "배송 준비 중" 만 떠 있었다.
 *
 * <p>[JA] 🐛 <b>回帰対象 — 決済イベントが丸ごと DLT に行った。</b>
 * 住所フォームは国を名前で保存するのに配送テーブルの {@code country} は {@code varchar(2)} なので、
 * 最初の配送を作ろうとした瞬間 {@code value too long for type character varying(2)} で死んだ。
 * その例外が決済レコードをリトライの末に DLT に送り、画面には「配送準備中」だけが表示されていた。
 */
@DisplayName("나라 표기 → ISO alpha-2")
class CountryCodesTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    /**
     * 🔴 <b>가장 중요한 테스트.</b> 주소 폼이 실제로 보낼 수 있는 값은 프론트의 목록이 전부다.
     * 그 245개를 <b>파일에서 읽어</b> 하나하나 대조한다 — 손으로 고른 표본이 아니라 전수다.
     *
     * <p>목록에 나라가 추가되거나 표기가 바뀌면 여기가 먼저 빨개진다. 운영에서
     * "그 나라로 주문하면 배송이 안 생긴다" 로 드러나는 것보다 낫다.
     *
     * <p>[JA] 🔴 <b>最も重要なテスト。</b>住所フォームが実際に送り得る値はフロントのリストがすべてだ。
     * その 245 個を<b>ファイルから読んで</b>一つずつ突き合わせる — 手で選んだ標本ではなく全数だ。
     * リストに国が追加されたり表記が変わったりすると、ここが先に赤くなる。運用で「その国で注文すると配送が作られない」として表に出るよりよい。
     */
    @Test
    @DisplayName("🔴 주소 폼의 나라 목록 전부가 자기 코드로 풀린다")
    void everyCountryTheFormCanSubmitResolves() throws IOException {
        Path list = repoRoot().resolve("frontend/src/components/checkout/countries.jsx");
        assertThat(list).as("주소 폼의 나라 목록").exists();

        Matcher m = Pattern.compile("\\{\\s*name:\\s*'((?:[^'\\\\]|\\\\.)*)'\\s*,\\s*code:\\s*'([A-Z]{2})'\\s*\\}")
                .matcher(Files.readString(list, StandardCharsets.UTF_8));

        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        while (m.find()) {
            String name = m.group(1).replace("\\'", "'");
            String expected = m.group(2);
            checked++;
            String actual = CountryCodes.toAlpha2(name);
            if (!expected.equals(actual)) {
                mismatches.add(name + " → " + actual + " (목록은 " + expected + ")");
            }
        }

        assertThat(checked).as("목록에서 읽은 나라 수").isGreaterThan(200);
        assertThat(mismatches)
                .as("이 나라로 주문하면 배송 생성이 조용히 발송국으로 떨어진다 — CountryCodes.ALIASES 에 더할 것")
                .isEmpty();
    }

    @Test
    @DisplayName("코드로 줘도 코드로 받는다 — alpha-2·alpha-3")
    void codesPassThrough() {
        assertThat(CountryCodes.toAlpha2("KR")).isEqualTo("KR");
        assertThat(CountryCodes.toAlpha2("kr")).isEqualTo("KR");
        assertThat(CountryCodes.toAlpha2("KOR")).isEqualTo("KR");
        assertThat(CountryCodes.toAlpha2("JPN")).isEqualTo("JP");
    }

    /**
     * 목포 주소의 배송이 {@code KP} 로 찍힌 적이 있다. 변환기는 맞았고 저장된 값이 목록의
     * 바로 위 항목("Korea, Democratic People'S Republic of")이었다 — 두 한국이 나란히 있어
     * 잘못 고르기 쉽다. 그래서 두 값이 서로 섞이지 않는지, 그리고 사람이 손으로 쓰는
     * 표기들이 제대로 갈리는지를 따로 고정한다.
     *
     * <p>[JA] 木浦の住所の配送が {@code KP} で記録されたことがある。変換器は正しく、保存された値がリストのすぐ上の項目("Korea, Democratic People'S Republic of")だった —
     * 2 つの韓国が並んでいて選び間違えやすい。そこで 2 つの値が互いに混ざらないか、そして人が手で書く表記が正しく分かれるかを別に固定する。
     */
    @Test
    @DisplayName("두 한국은 절대 섞이지 않는다 — 손으로 쓴 표기까지")
    void theTwoKoreasNeverMix() {
        for (String south : new String[] {"Korea, Republic of", "Republic of Korea", "South Korea",
                "Korea, South", "Korea (South)", "S. Korea", "대한민국", "한국", "KR", "KOR"}) {
            assertThat(CountryCodes.toAlpha2(south)).as(south).isEqualTo("KR");
        }
        for (String north : new String[] {"Korea, Democratic People'S Republic of",
                "Democratic People's Republic of Korea", "North Korea", "Korea, North", "DPRK", "KP", "PRK"}) {
            assertThat(CountryCodes.toAlpha2(north)).as(north).isEqualTo("KP");
        }
        // "Korea" 만으로는 어느 쪽인지 알 수 없다 — 짐작하지 않는다.
        // [JA] "Korea" だけではどちらか分からない — 推測しない。
        assertThat(CountryCodes.toAlpha2("Korea")).isNull();
    }

    @Test
    @DisplayName("한글·일본어 표기도 받는다 — 화면 언어가 둘이다")
    void localizedNames() {
        assertThat(CountryCodes.toAlpha2("대한민국")).isEqualTo("KR");
        assertThat(CountryCodes.toAlpha2("일본")).isEqualTo("JP");
        assertThat(CountryCodes.toAlpha2("韓国")).isEqualTo("KR");
    }

    @Test
    @DisplayName("문장부호·악센트·대소문자가 달라도 같은 나라다")
    void punctuationAndAccentsAreIgnored() {
        assertThat(CountryCodes.toAlpha2("Cote D'Ivoire")).isEqualTo("CI");
        assertThat(CountryCodes.toAlpha2("Côte d'Ivoire")).isEqualTo("CI");
        assertThat(CountryCodes.toAlpha2("  korea, republic OF  ")).isEqualTo("KR");
    }

    /** 🔴 모르는 값에 예외를 던지면 그 순간 결제 이벤트가 DLT 로 간다 — 그게 이 버그였다. */
    // [JA] 🔴 未知の値に例外を投げると、その瞬間に決済イベントが DLT に行く — それがこのバグだった。
    @Test
    @DisplayName("🔴 알아보지 못하면 null 이고, 기본값을 주면 그것을 쓴다 — 예외는 던지지 않는다")
    void unknownFallsBackInsteadOfThrowing() {
        assertThat(CountryCodes.toAlpha2("Neverland")).isNull();
        assertThat(CountryCodes.toAlpha2("")).isNull();
        assertThat(CountryCodes.toAlpha2(null)).isNull();
        assertThat(CountryCodes.toAlpha2OrDefault("Neverland", "KR")).isEqualTo("KR");
        assertThat(CountryCodes.toAlpha2OrDefault(null, "KR")).isEqualTo("KR");
        assertThat(CountryCodes.toAlpha2OrDefault("Japan", "KR")).isEqualTo("JP");
    }

    /** 어떤 값이 들어와도 두 글자거나 null 이다 — 컬럼이 varchar(2) 라는 사실을 여기서 고정한다. */
    // [JA] どんな値が来ても 2 文字か null だ — カラムが varchar(2) だという事実をここで固定する。
    @Test
    @DisplayName("결과는 언제나 두 글자다")
    void resultAlwaysFitsTheColumn() {
        for (String raw : new String[] {"KR", "KOR", "Korea, Republic of", "대한민국", "United States",
                "Holy See (Vatican City State)", "Neverland", "", "   "}) {
            String code = CountryCodes.toAlpha2(raw);
            assertThat(code == null || code.length() == 2).as(raw + " → " + code).isTrue();
        }
    }
}
