package com.project.common.text;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 손님이 입력한 <b>찾기 · 식별용 값</b>을 같은 글자로 맞춘다 — 유니코드 NFKC + 앞뒤 공백 제거.
 *
 * <h3>왜</h3>
 *
 * <p>일본어 IME 는 전각 영숫자(`ＴＳＨＩＲＴ` · `１２３`) · 반각 카나(`ｼｬﾂ`)를 쉽게 낸다. 예전에는 아무 곳도 정규화하지 않아
 * 전각으로 친 검색어는 반각으로 저장된 상품을 찾지 못했고, 전각으로 친 이메일은 로그인 · 가입 확인 재발송 · 비밀번호 찾기에서
 * "없는 계정" 이 됐다(가입은 {@code @Email} 이 거절). NFKC 는 이런 호환 문자를 표준 글자로 바꾼다:
 * {@code ＴＳＨＩＲＴ → TSHIRT}, {@code ｼｬﾂ → シャツ}, {@code １２３－４５６７ → 123-4567}, {@code ＠ → @}.
 *
 * <h3>어디에만</h3>
 *
 * <p>검색어 · 이메일 · 전화번호 · 우편번호처럼 <b>비교에 쓰는 값</b>에만 쓴다. 이름 · 주소 · 리뷰 · 채팅처럼 <b>보여 주는 글</b>에는
 * 쓰지 않는다 — NFKC 는 손님이 쓴 글자를 바꾼다({@code ① → 1}, {@code ㈱ → (株)}, {@code ﬁ → fi}).
 * 상품 이름은 원문을 두고, 검색 비교만 DB 함수 {@code product.search_key}(같은 NFKC + 소문자, catalog V23)로 한다.
 *
 * <p>[JA] 客が入力した<b>検索 · 識別用の値</b>を同じ文字に揃える — Unicode NFKC + 前後の空白除去。日本語 IME は全角英数 · 半角カナを
 * 出しやすく、以前はどこも正規化しておらず、全角の検索語は半角で保存された商品を見つけられず、全角のメールはログイン等で
 * 「ない口座」になった。比較に使う値だけに使い、表示する文(名前 · 住所 · レビュー · チャット)には使わない(NFKC は文字を変える)。
 */
public final class Nfkc {

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Nfkc() {
    }

    /** NFKC + 앞뒤 공백 제거. null 은 null. ／ [JA] NFKC + 前後の空白除去。 */
    public static String of(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.isNormalized(value, Normalizer.Form.NFKC)
                ? value : Normalizer.normalize(value, Normalizer.Form.NFKC);
        return normalized.strip();
    }

    /** 검색어 — {@link #of} + 연속 공백(전각 공백 포함)을 하나로. 대소문자는 비교하는 쪽(DB)이 맞춘다. */
    // [JA] 検索語 — of + 連続空白(全角空白を含む)を一つに。大文字小文字は比較する側(DB)が揃える。
    public static String searchKey(String value) {
        String normalized = of(value);
        return normalized == null ? null : SPACES.matcher(normalized).replaceAll(" ");
    }
}
