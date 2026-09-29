import { countries } from "components/checkout/countries";
import { countryName, countryOptions, toCountryCode, DEFAULT_COUNTRY } from "util/country";

/**
 * 나라 — 저장은 코드, 화면은 언어별 이름 (docs/60 §11).
 *
 * <p>옛 주소는 영어 이름으로 저장돼 있다 — 두 모양을 모두 읽어야 주문 상세·주소 카드가 깨지지 않는다.
 */
describe("util/country", () => {
  it("코드와 옛 영어 이름을 모두 코드로 읽는다 — 대소문자·문장부호 흔들림도", () => {
    expect(toCountryCode("KR")).toBe("KR");
    expect(toCountryCode("kr")).toBe("KR");
    expect(toCountryCode("Korea, Republic of")).toBe("KR");
    expect(toCountryCode("Korea, Democratic People'S Republic of")).toBe("KP");
    expect(toCountryCode("AndorrA")).toBe("AD");
    expect(toCountryCode("Atlantis")).toBeNull();
    expect(toCountryCode("")).toBeNull();
  });

  it("화면 언어의 이름으로 그린다", () => {
    expect(countryName("KR", "ko")).toBe("대한민국");
    expect(countryName("KR", "ja")).toBe("韓国");
    expect(countryName("Japan", "ja")).toBe("日本");
    expect(countryName("Korea, Republic of", "ja")).toBe("韓国");
  });

  it("모르는 값은 그대로 — 사람이 직접 쓴 값일 수 있다", () => {
    expect(countryName("Atlantis", "ja")).toBe("Atlantis");
    expect(countryName(null, "ja")).toBe("");
  });

  it("🔴 일본어 목록에 영어 이름이 남지 않는다 — 모든 나라가 일본어 이름을 가진다", () => {
    const options = countryOptions("ja");
    expect(options).toHaveLength(countries.length);
    const english = options.filter((o) => /^[A-Za-z]/.test(o.name));
    expect(english).toEqual([]);
  });

  it("선택지는 그 언어의 이름 순서 — 값은 언어와 무관하게 같은 코드", () => {
    const ko = countryOptions("ko");
    const ja = countryOptions("ja");
    expect(new Set(ko.map((o) => o.code))).toEqual(new Set(ja.map((o) => o.code)));
    const names = ko.map((o) => o.name);
    expect(names).toEqual([...names].sort(new Intl.Collator("ko").compare));
    expect(ja.find((o) => o.code === DEFAULT_COUNTRY).name).toBe("韓国");
  });
});
