package com.project.notification_service.sms;

import java.util.Locale;
import java.util.Optional;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * 전화번호를 저장·발송에 쓸 수 있는 형태로 정규화한다.
 *
 * <p><b>왜 라이브러리인가.</b> "010 으로 시작하는 11자리" 같은 정규식으로 시작했다가는 반드시
 * 틀린다. 한국과 일본은 규칙이 다르고, 둘 다 함정이 있다:
 *
 * <ul>
 *   <li><b>트렁크 프리픽스 0.</b> 국내 표기 {@code 010-1234-5678} 의 맨 앞 0 은 국내 통화용
 *       접두사라 국제 형식에서는 <b>빠진다</b> — {@code +82 10 1234 5678}. 일본도 같다
 *       ({@code 090-1234-5678} → {@code +81 90 1234 5678}). 이 0 을 그대로 붙여
 *       {@code +820101234568} 로 보내면 게이트웨이가 조용히 실패하거나 엉뚱한 번호로 간다.</li>
 *   <li><b>대역이 계속 바뀐다.</b> 통신사 사정으로 유효 번호 대역은 갱신된다. 직접 관리할
 *       성격의 데이터가 아니다.</li>
 *   <li><b>휴대폰인지 구분해야 한다.</b> SMS 는 유선번호로 보낼 수 없다. 형식이 맞아도
 *       {@code 02-1234-5678} 같은 서울 유선번호면 발송이 실패한다 — 보내기 전에 걸러야 한다.</li>
 * </ul>
 *
 * <p>libphonenumber(구글, Apache-2.0)가 그 데이터를 담고 있다. 여기서는 그 위에
 * <b>이 서비스가 지원하는 두 나라</b>로 범위를 좁히는 얇은 층만 얹는다.
 *
 * <p>저장은 항상 <b>E.164</b>({@code +821012345678})로 한다. 사용자가 어떤 표기로 입력했든
 * 한 가지 형태로 모이므로 중복 등록을 막을 수 있고, 게이트웨이가 그대로 받는다.
 */
public final class PhoneNumbers {

    /** 지원 국가. 늘리려면 여기에 추가하고 문구(SmsTemplates)도 함께 준비해야 한다. */
    public enum Region {
        KR("KR", "82", "한국"),
        JP("JP", "81", "일본");

        private final String code;
        private final String dialCode;
        private final String label;

        Region(String code, String dialCode, String label) {
            this.code = code;
            this.dialCode = dialCode;
            this.label = label;
        }

        public String code() { return code; }
        public String dialCode() { return dialCode; }
        public String label() { return label; }
    }

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private PhoneNumbers() {
    }

    /**
     * 입력을 E.164 로 정규화한다. 지원 국가의 <b>휴대폰</b> 번호가 아니면 비어 있는 값을 준다.
     *
     * @param raw            사용자 입력. {@code 010-1234-5678}, {@code +82 10 1234 5678},
     *                       {@code 09012345678} 등 어떤 표기든 받는다
     * @param defaultRegion  국가코드가 없는 입력을 어느 나라로 볼지. 사용자의 지역 설정에서 온다
     */
    public static Optional<String> toE164(String raw, Region defaultRegion) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            PhoneNumber parsed = UTIL.parse(raw, defaultRegion.code());

            // isValidNumber 는 자릿수뿐 아니라 **실제로 배정된 대역인지**까지 본다.
            if (!UTIL.isValidNumber(parsed)) {
                return Optional.empty();
            }

            // 이 서비스가 문구를 준비한 국가인지 확인한다. 형식이 맞아도 미국 번호가 들어오면
            // 보낼 문구가 없고, 요금 체계도 다르다.
            String region = UTIL.getRegionCodeForNumber(parsed);
            if (region == null || regionOf(region).isEmpty()) {
                return Optional.empty();
            }

            // SMS 는 유선번호로 가지 않는다. MOBILE 과 FIXED_LINE_OR_MOBILE 만 허용한다
            // (일부 번호는 구분이 불가능해 후자로 나온다).
            PhoneNumberType type = UTIL.getNumberType(parsed);
            if (type != PhoneNumberType.MOBILE && type != PhoneNumberType.FIXED_LINE_OR_MOBILE) {
                return Optional.empty();
            }

            return Optional.of(UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164));

        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }

    /** E.164 번호가 속한 지원 국가. 문구 언어를 고르는 데 쓴다. */
    public static Optional<Region> regionOfE164(String e164) {
        try {
            PhoneNumber parsed = UTIL.parse(e164, null);
            String region = UTIL.getRegionCodeForNumber(parsed);
            return region == null ? Optional.empty() : regionOf(region);
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }

    /**
     * 로그·화면에 쓸 마스킹 형태. {@code +821012345678} → {@code +8210****5678}.
     *
     * <p>전화번호는 개인정보다. 로그에 그대로 남기면 로그 열람 권한이 곧 연락처 열람 권한이 된다.
     * 그렇다고 전부 가리면 "어느 번호로 갔는지" 확인이 불가능해 장애 대응이 어려워지므로,
     * 앞뒤 일부만 남긴다.
     */
    public static String mask(String e164) {
        if (e164 == null || e164.length() < 8) {
            return "***";
        }
        int keepFront = 5;   // +82 + 통신사 앞자리 정도
        int keepBack = 4;
        return e164.substring(0, keepFront)
                + "*".repeat(Math.max(0, e164.length() - keepFront - keepBack))
                + e164.substring(e164.length() - keepBack);
    }

    /** 화면 표시용 국내 표기. {@code +821012345678} → {@code 010-1234-5678} */
    public static String toNational(String e164) {
        try {
            PhoneNumber parsed = UTIL.parse(e164, null);
            return UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.NATIONAL);
        } catch (NumberParseException e) {
            return e164;
        }
    }

    private static Optional<Region> regionOf(String isoCode) {
        for (Region r : Region.values()) {
            if (r.code().equalsIgnoreCase(isoCode)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** 문자열에서 Region 을 고른다. 알 수 없으면 KR. */
    public static Region regionFrom(String value) {
        if (value != null) {
            for (Region r : Region.values()) {
                if (r.code().equalsIgnoreCase(value.trim())) {
                    return r;
                }
            }
        }
        return Region.KR;
    }

    /** 문구 언어. 한국 번호면 ko, 일본 번호면 ja. */
    public static Locale localeOf(Region region) {
        return region == Region.JP ? Locale.JAPANESE : Locale.KOREAN;
    }
}
