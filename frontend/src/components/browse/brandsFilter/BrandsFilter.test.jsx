import { fireEvent, render, screen } from "@testing-library/react";

import BrandsFilter from "components/browse/brandsFilter/BrandsFilter";
import { withI18n } from "i18n/testing";

/**
 * 🐛 <b>브랜드 아이콘이 대부분 깨져 있었다.</b>
 *
 * <p>칸마다 조건 없이 {@code /assets/images/<brand>.png} 를 그렸다. 그런데 그 폴더에 있는
 * 로고는 열 몇 개뿐이고(adidas·nike·puma·zara …), 브랜드 목록은 <b>카탈로그에 실제로
 * 등록된 값</b>이다. 파일이 없으면 404 → 깨진 이미지 아이콘.
 *
 * <p>그리고 칸에 <b>글자가 하나도 없었다.</b> alt 만으로는 어느 브랜드인지 읽히지 않는다 —
 * 사용자는 무엇을 고르는지 모르는 채로 눌러야 했다.
 */
describe("브랜드 필터", () => {
  const replaceQuery = (name, value) => ({ result: String(value), active: false });

  const draw = (brands, props = {}, lang = "ko") =>
    render(withI18n(
      <BrandsFilter
        brands={brands}
        brandHandler={jest.fn()}
        replaceQuery={replaceQuery}
        {...props}
      />,
      lang
    ));

  /** 🔴 핵심. 로고가 없어도 브랜드 이름은 반드시 읽혀야 한다. */
  it("로고가 없어도 브랜드 이름을 보여 준다", () => {
    draw(["Nike", "Some Unknown Brand"]);

    expect(screen.getByText("Nike")).toBeInTheDocument();
    expect(screen.getByText("Some Unknown Brand")).toBeInTheDocument();
  });

  /**
   * 🔴 이미지 로드가 실패하면 <b>그 자리를 비운다.</b> 이 onError 가 없으면 깨진 아이콘이
   * 그대로 남는다 — 그게 원래 증상이다.
   */
  it("로고를 못 불러오면 깨진 이미지를 남기지 않는다", () => {
    draw(["Nowhere"]);

    const img = screen.getByRole("img", { hidden: true });
    fireEvent.error(img);

    expect(screen.queryByRole("img", { hidden: true })).not.toBeInTheDocument();
    // 이름은 그대로 남아야 한다.
    expect(screen.getByText("Nowhere")).toBeInTheDocument();
  });

  it("브랜드를 누르면 필터를 건다", () => {
    const brandHandler = jest.fn();
    draw(["Nike"], { brandHandler });

    fireEvent.click(screen.getByRole("button", { name: /Nike/ }));

    expect(brandHandler).toHaveBeenCalledWith("Nike");
  });

  it("브랜드가 없어도 터지지 않는다", () => {
    draw([]);

    expect(screen.queryAllByRole("button")).toHaveLength(0);
  });

  /** 🔴 제목이 영어("Brands")로 박혀 있었다 — 일본어로 그려 봐야 드러난다. 브랜드 이름(서버 값)은 번역하지 않는다. */
  it("일본어로 그리면 제목이 일본어다", () => {
    draw(["Nike"], {}, "ja");

    expect(screen.getByText("ブランド")).toBeInTheDocument();
    expect(screen.queryByText("Brands")).not.toBeInTheDocument();
    expect(screen.getByText("Nike")).toBeInTheDocument();
  });

  it("한국어로 그리면 제목이 한국어다", () => {
    draw(["Nike"]);
    expect(screen.getByText("브랜드")).toBeInTheDocument();
  });
});
