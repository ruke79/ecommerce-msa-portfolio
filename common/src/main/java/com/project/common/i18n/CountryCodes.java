package com.project.common.i18n;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 나라 표기를 <b>ISO 3166-1 alpha-2</b> 로 바꾼다.
 *
 * <h3>🐛 결제가 통째로 DLT 로 갔다 — 두 글자 컬럼에 "Korea, Republic of" 를 넣어서</h3>
 *
 * <p>주소 입력 폼은 나라를 <b>이름</b>으로 저장한다({@code SingularSelect} 가
 * {@code option.name} 을 값으로 쓴다). 배송 테이블의 {@code country} 는 반대로
 * <b>ISO 코드</b>라 {@code varchar(2)} 다. 결제가 끝나고 배송을 만들려는 순간 이렇게 죽었다:
 *
 * <pre>
 *   ERROR: value too long for type character varying(2)
 * </pre>
 *
 * <p>그리고 그 예외는 배송 하나가 아니라 <b>결제 이벤트 전체</b>를 재시도 끝에 DLT 로 보냈다.
 * 화면에는 "배송 준비 중" 만 떠 있어서, 나라 이름 한 칸 때문에 배송이 영영 안 생긴다는 것을
 * 아무도 알 수 없었다. 같은 이름을 쓰는 두 값이 사실은 다른 것이었다 — 하나는 <b>사람이 읽는
 * 표기</b>, 하나는 <b>코드</b>.
 *
 * <h3>어떻게 푸는가</h3>
 *
 * <p>JDK 의 {@link Locale} 데이터를 색인으로 만든다 — alpha-2, alpha-3, 영문·한글 표시명.
 * 그것만으로는 부족하다. JDK 는 CLDR 기준이라 {@code KR} 을 "South Korea" 라 부르는데, 폼의
 * 목록은 ISO 공식 표기인 "Korea, Republic of" 를 쓴다. 그래서 <b>실측으로</b> 안 풀리는 것만
 * 별칭으로 채웠다({@code CountryCodesTest} 가 폼의 목록 전체를 실제로 대조한다).
 *
 * <p>대조는 문장부호·공백·대소문자·악센트를 지운 뒤에 한다. "AndorrA", "Viet Nam",
 * "Cote D'Ivoire" 같은 표기 흔들림이 이 단계에서 흡수된다.
 *
 * <p><b>모르면 null 이다.</b> 부르는 쪽이 정한다 — 배송은 발송국으로 보고 계속 만든다.
 * 나라 이름 한 칸 때문에 주문이 멈추는 일은 다시 없어야 한다.
 */
public final class CountryCodes {

    /**
     * JDK 의 CLDR 표기와 다른 이름들. <b>손으로 고른 것이 아니라</b>, 주소 폼의 목록
     * (frontend/src/components/checkout/countries.jsx) 245개를 전부 돌려 안 풀린 것만 적었다.
     * 목록이 바뀌면 {@code CountryCodesTest} 가 먼저 빨개진다.
     */
    private static final String[][] ALIASES = {
        {"Korea, Republic of", "KR"},
        {"Korea, Democratic People's Republic of", "KP"},
        {"Viet Nam", "VN"},
        {"Russian Federation", "RU"},
        {"Iran, Islamic Republic Of", "IR"},
        {"Syrian Arab Republic", "SY"},
        {"Lao People's Democratic Republic", "LA"},
        {"Tanzania, United Republic of", "TZ"},
        {"Moldova, Republic of", "MD"},
        {"Macedonia, The Former Yugoslav Republic of", "MK"},
        {"Palestinian Territory, Occupied", "PS"},
        {"Holy See (Vatican City State)", "VA"},
        {"Libyan Arab Jamahiriya", "LY"},
        {"Brunei Darussalam", "BN"},
        {"Cape Verde", "CV"},
        {"Congo, The Democratic Republic of the", "CD"},
        {"Cote D'Ivoire", "CI"},
        {"Falkland Islands (Malvinas)", "FK"},
        {"Micronesia, Federated States of", "FM"},
        {"Heard Island and Mcdonald Islands", "HM"},
        {"Macao", "MO"},
        {"Burma", "MM"},
        {"Myanmar", "MM"},
        {"Saint Helena", "SH"},
        {"Svalbard and Jan Mayen", "SJ"},
        {"Taiwan, Province of China", "TW"},
        {"Timor-Leste", "TL"},
        {"Virgin Islands, British", "VG"},
        {"Virgin Islands, U.S.", "VI"},
        {"Wallis and Futuna", "WF"},
        {"United Kingdom", "GB"},
        {"United States", "US"},
        {"South Korea", "KR"},
        {"North Korea", "KP"},
        {"대한민국", "KR"},
        {"한국", "KR"},
        // 사람이 손으로 쓸 때의 표기. 폼 목록에는 없지만 옛 주소·다른 경로로 들어온다.
        {"Republic of Korea", "KR"},
        {"Korea, South", "KR"},
        {"Korea (South)", "KR"},
        {"S. Korea", "KR"},
        {"Democratic People's Republic of Korea", "KP"},
        {"Korea, North", "KP"},
        {"Korea (North)", "KP"},
        {"DPRK", "KP"},

        // JDK 가 다르게 부르는 것들
        {"Congo", "CG"},                                    // CLDR: Congo - Brazzaville
        {"Czech Republic", "CZ"},                           // CLDR: Czechia
        {"Hong Kong", "HK"},                                // CLDR: Hong Kong SAR China
        {"Pitcairn", "PN"},                                 // CLDR: Pitcairn Islands
        {"Swaziland", "SZ"},                                // CLDR: Eswatini
        {"United States Minor Outlying Islands", "UM"},     // CLDR: U.S. Outlying Islands
        {"South Georgia and the South Sandwich Islands", "GS"},

        // "Saint" 와 "St." — 줄임말을 일반 규칙으로 풀면 다른 이름까지 건드린다. 나라는 몇 개뿐이니 적어 둔다.
        {"Saint Kitts and Nevis", "KN"},
        {"Saint Lucia", "LC"},
        {"Saint Pierre and Miquelon", "PM"},
        {"Saint Vincent and the Grenadines", "VC"},

        // 폐지된 코드. ISO 목록에는 없지만 폼에 남아 있고, 옛 주소에도 저장돼 있다.
        // 두 글자라 컬럼에는 들어간다 — 알아보지 못해 발송국으로 떨어지는 것보다 낫다.
        {"Netherlands Antilles", "AN"},
        {"Serbia and Montenegro", "CS"},
    };

    /** 정규화한 표기 → alpha-2. 불변이며 클래스 로딩 때 한 번만 만든다. */
    private static final Map<String, String> INDEX = buildIndex();

    private CountryCodes() {
    }

    private static Map<String, String> buildIndex() {
        Map<String, String> index = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            @SuppressWarnings("deprecation")   // Locale.of 는 Java 19+ 다. 이 모듈은 17 을 쓴다.
            Locale locale = new Locale("", code);
            put(index, code, code);
            try {
                put(index, locale.getISO3Country(), code);
            } catch (Exception ignored) {
                // alpha-3 이 없는 코드가 있다. 그 항목만 건너뛴다.
            }
            put(index, locale.getDisplayCountry(Locale.ENGLISH), code);
            put(index, locale.getDisplayCountry(Locale.KOREAN), code);
            put(index, locale.getDisplayCountry(Locale.JAPANESE), code);
        }
        // 별칭이 우선한다 — 표시명이 겹칠 때 ISO 공식 표기 쪽을 믿는다.
        for (String[] alias : ALIASES) {
            index.put(normalize(alias[0]), alias[1]);
        }
        return Map.copyOf(index);
    }

    private static void put(Map<String, String> index, String label, String code) {
        String key = normalize(label);
        if (!key.isEmpty()) {
            index.putIfAbsent(key, code);
        }
    }

    /**
     * @param raw 코드("KR"·"KOR")든 이름("Korea, Republic of"·"대한민국")이든
     * @return ISO 3166-1 alpha-2, 알아보지 못하면 {@code null}
     */
    public static String toAlpha2(String raw) {
        if (raw == null) {
            return null;
        }
        String key = normalize(raw);
        return key.isEmpty() ? null : INDEX.get(key);
    }

    /**
     * 알아보지 못하면 {@code fallback} 을 돌려준다.
     *
     * <p>배송처럼 <b>멈추면 안 되는</b> 흐름이 쓴다. 나라를 잘못 짚으면 통관 단계가 어긋나는
     * 정도지만, 여기서 예외를 던지면 결제 이벤트가 통째로 DLT 로 간다 — 실제로 그랬다.
     */
    public static String toAlpha2OrDefault(String raw, String fallback) {
        String code = toAlpha2(raw);
        return code != null ? code : fallback;
    }

    /**
     * 대소문자·공백·문장부호·악센트를 지운 비교용 열쇠. "Cote D'Ivoire" 와 "Côte d'Ivoire" 가 같아진다.
     *
     * <p>{@code &} 는 지우기 전에 {@code and} 로 바꾼다. JDK 는 "Antigua &amp; Barbuda" 라 쓰고 폼의
     * 목록은 "Antigua and Barbuda" 라 쓰는데, 그냥 지우면 두 글자가 달라져 안 맞는다 —
     * 실측에서 이 한 가지로 18개 중 12개가 걸렸다.
     */
    private static String normalize(String label) {
        if (label == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(label.replace("&", " and "), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.isLetterOrDigit(c) && Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
