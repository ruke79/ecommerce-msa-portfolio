import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import HomeSections from "pages/home/HomeSections";
import { withI18n } from "i18n/testing";

// 스와이퍼 대역은 jest.config.js 의 moduleNameMapper 가 이미 걸어 둔다.

/**
 * 🐛 <b>격자 마지막 줄에 빈 칸이 남았다.</b>
 *
 * <p>{@code md:grid-cols-4} 는 열 개수를 <b>고정</b>한다. 섹션 개수는 데이터가 정하는데
 * (시드는 GRID 5개) 5 를 4 로 나누면 마지막 줄은 카드 하나 + 빈 칸 셋이다.
 * {@code grid-flow-row-dense} 는 뒤의 <b>작은</b> 항목을 앞의 구멍에 끼우는 것이라, 모든
 * 카드가 1칸인 여기서는 아무 일도 하지 않았다.
 *
 * <p>flex 줄바꿈은 열 개수를 <b>내용이</b> 정하게 한다 — 한 줄에 몇 개가 들어가든 남는
 * 폭을 나눠 가지므로 그 줄은 항상 꽉 찬다.
 */
describe("홈 섹션 배치", () => {
  const section = (id, layout) => ({
    id,
    layout,
    title: `섹션 ${id}`,
    products: [
      {
        id: `${id}-1`,
        slug: `s-${id}`,
        name: `상품 ${id}`,
        skus: [{ images: ["/a.jpg"], sizes: [{ price: 10 }], color: {} }],
      },
    ],
  });

  const draw = (sections, error = false) =>
    // 카드가 재고 배지 문구를 번역한다(docs/59 §5) — 앱처럼 I18nProvider 안에서 그린다.
    render(withI18n(
      <MemoryRouter>
        <HomeSections sections={sections} error={error} />
      </MemoryRouter>
    ));

  /**
   * 🔴 핵심 가드. 고정 열 개수가 돌아오면 마지막 줄의 구멍도 함께 돌아온다.
   */
  it("열 개수를 고정하지 않는다 — 마지막 줄이 비지 않도록", () => {
    const { container } = draw([1, 2, 3, 4, 5].map((i) => section(i, "GRID")));

    const row = container.querySelector(".flex-wrap");
    expect(row).not.toBeNull();
    expect(row.className).not.toMatch(/grid-cols-\d/);
  });

  /** 남는 폭을 나눠 가지는 것은 `flex-1`(grow) 이다. 이게 없으면 오른쪽이 빈다. */
  it("카드가 남는 폭을 나눠 가진다", () => {
    const { container } = draw([1, 2, 3, 4, 5].map((i) => section(i, "GRID")));

    const cards = container.querySelectorAll(".flex-wrap > div");
    expect(cards).toHaveLength(5);
    for (const card of cards) {
      expect(card.className).toContain("flex-1");
      // min-w-0 이 없으면 긴 제목 하나가 basis 를 무시하고 줄바꿈을 망가뜨린다.
      expect(card.className).toContain("min-w-0");
    }
  });

  it("GRID 와 SWIPER 를 나눠 배치한다", () => {
    draw([section(1, "GRID"), section(2, "SWIPER")]);

    expect(screen.getByText("섹션 1")).toBeInTheDocument();
    expect(screen.getByText("섹션 2")).toBeInTheDocument();
  });

  /** 장애와 "아직 아무것도 없음" 은 화면에서 구분돼야 한다. */
  it("실패와 빈 목록을 구분해 알린다", () => {
    const { rerender } = draw([], true);
    expect(screen.getByText(/불러오지 못했습니다/)).toBeInTheDocument();

    rerender(withI18n(
      <MemoryRouter>
        <HomeSections sections={[]} error={false} />
      </MemoryRouter>
    ));
    expect(screen.getByText(/아직 없습니다/)).toBeInTheDocument();
  });

  /**
   * 🐛 "더 보기"가 <h4> 였다 — 눌리는 모양인데 아무 데도 가지 않았다.
   * 서버가 실어 준 id 로 탐색 화면에 간다(이름이 아니라 id 로 거른다).
   */
  it("격자 카드의 '더 보기'는 그 카테고리의 탐색 화면으로 간다", () => {
    draw([
      { ...section(1, "GRID"), categoryId: "812345678901234567" },
      { ...section(2, "GRID"), categoryId: "10", subcategoryId: "22" },
    ]);

    const links = screen.getAllByRole("link", { name: /지금 쇼핑하기|더 보기/ });
    expect(links.map((a) => a.getAttribute("href"))).toEqual([
      "/browse?category=812345678901234567",
      "/browse?category=10&subCategory=22",
    ]);
  });

  it("스와이퍼 줄에도 '더 보기'가 있다", () => {
    draw([{ ...section(3, "SWIPER"), categoryId: "7" }]);

    expect(screen.getByRole("link", { name: "더 보기" })).toHaveAttribute("href", "/browse?category=7");
  });

  it("카테고리 id 를 모르면 전체 목록으로 — 죽은 링크를 만들지 않는다", () => {
    draw([section(1, "GRID")]);

    expect(screen.getByRole("link", { name: "지금 쇼핑하기" })).toHaveAttribute("href", "/browse");
  });
});
