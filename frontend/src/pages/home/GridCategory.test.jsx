import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import GridCategory from "pages/home/GridCategory";
import { withI18n } from "i18n/testing";

/**
 * 🐛 <b>홈 가운데 격자에 빈 공백이 크게 남았다.</b>
 *
 * <p>둘이 겹쳐 있었다.
 *
 * <ul>
 *   <li><b>한 줄짜리 격자 × 420px 타일.</b> {@code grid-cols-1} + {@code h-[420px]} 에
 *       서버가 4개를 내려 주므로 카드 하나가 세로 1700px 이 됐다. 상품이 1개뿐인
 *       카테고리 카드는 420px 이라, 같은 줄의 높이를 맞추느라 그 아래로 1200px 넘는
 *       흰 공백이 생겼다.</li>
 *   <li><b>{@code object-contain} 레터박스.</b> 상자 비율과 사진 비율이 다른 만큼
 *       상자가 빈다. 정사각 타일 + {@code object-cover} 는 <b>절대 비지 않는다.</b></li>
 * </ul>
 *
 * <p>[JA] 🐛 <b>ホーム中央の格子に大きな空白が残った。</b>二つが重なっていた。<b>1 列の格子 × 420px タイル。</b>{@code grid-cols-1} + {@code h-[420px]} に
 * サーバーが 4 個返すのでカード一枚が縦 1700px になった。商品が 1 個だけのカテゴリのカードは 420px なので、同じ行の高さを揃えるためにその下に 1200px 超の
 * 白い空白ができた。<b>{@code object-contain} のレターボックス。</b>箱の比率と写真の比率が違う分だけ箱が空く。正方形タイル + {@code object-cover} は<b>決して空かない。</b>
 */
describe("홈 격자 카드", () => {
  const product = (id, image) => ({
    id,
    slug: `p-${id}`,
    name: `상품 ${id}`,
    skus: [{ images: image ? [image] : [] }],
  });

  const draw = (products) =>
    // 하단 링크 문구가 사전에서 온다 — 앱처럼 I18nProvider 안에서 그린다.
    // [JA] 下部のリンク文言が辞書から来る — アプリのように I18nProvider の中で描く。
    render(withI18n(
      <MemoryRouter>
        <GridCategory title="fashion" products={products} />
      </MemoryRouter>
    ));

  const tileOf = (img) => img.parentElement;

  it("상품을 모두 그린다", () => {
    draw([product(1, "/a.jpg"), product(2, "/b.jpg")]);

    expect(screen.getAllByRole("img")).toHaveLength(2);
  });

  /**
   * 🔴 이 파일의 핵심 가드다. 타일이 정사각으로 고정돼 있고 이미지가 그것을 <b>채워야</b>
   * 남는 자리가 없다. 셋 중 하나만 빠져도 흰 띠가 돌아온다.
   *
   * <p>[JA] 🔴 このファイルの中核のガードである。タイルが正方形に固定され画像がそれを<b>埋めて</b>こそ余る場所がない。三つのうち一つでも欠けると白い帯が戻ってくる。
   */
  it("타일이 비지 않는다 — 정사각 상자를 이미지가 채운다", () => {
    draw([product(1, "/a.jpg")]);

    const img = screen.getByRole("img");
    expect(img.className).toContain("object-cover");
    expect(img.className).toContain("h-full");
    expect(img.className).toContain("w-full");
    // contain 은 남는 자리를 비운다 — 되살아나면 안 된다.
    // [JA] contain は余った場所を空ける — 蘇ってはならない。
    expect(img.className).not.toContain("object-contain");

    expect(tileOf(img).className).toContain("aspect-square");
  });

  /** cover 는 넘치는 쪽을 잘라내는 방식이다. 잘라 줄 상자가 없으면 옆 칸을 침범한다. */
  // [JA] cover ははみ出す側を切り取る方式である。切り取ってくれる箱がなければ隣の欄を侵す。
  it("넘친 부분을 잘라낸다", () => {
    draw([product(1, "/a.jpg")]);

    expect(tileOf(screen.getByRole("img")).className).toContain("overflow-hidden");
  });

  /**
   * 고정 높이가 되살아나면 타일 비율이 다시 어긋난다 — 레터박스의 출발점이었다.
   *
   * <p>[JA] 固定の高さが蘇るとタイルの比率が再びずれる — レターボックスの出発点だった。
   */
  it("타일 높이를 픽셀로 박지 않는다", () => {
    draw([product(1, "/a.jpg")]);

    expect(tileOf(screen.getByRole("img")).className).not.toMatch(/h-\[\d+px\]/);
  });

  /**
   * 4개를 한 열에 쌓으면 카드가 1700px 이 된다. 2열이면 2줄 — 카드 높이 편차가 절반이고,
   * 그 편차가 곧 옆 카드의 빈 공백이었다.
   *
   * <p>[JA] 4 個を 1 列に積むとカードが 1700px になる。2 列なら 2 行 — カードの高さの差が半分になり、その差がそのまま隣のカードの空白だった。
   */
  it("여러 개면 2열로 쌓는다 — 한 열이면 카드가 세로로 폭주한다", () => {
    const { container } = draw([1, 2, 3, 4].map((i) => product(i, `/${i}.jpg`)));

    const grid = container.querySelector(".grid");
    expect(grid.className).toContain("grid-cols-2");
  });

  /** 반대로 하나뿐인데 2열이면 오른쪽 칸이 통째로 빈다. */
  // [JA] 逆に一つだけなのに 2 列だと右の欄が丸ごと空く。
  it("하나뿐이면 한 칸을 꽉 쓴다", () => {
    const { container } = draw([product(1, "/a.jpg")]);

    const grid = container.querySelector(".grid");
    expect(grid.className).toContain("grid-cols-1");
    expect(grid.className).not.toContain("grid-cols-2");
  });

  /**
   * 이미지 업로드가 실패한 상품이 섞일 수 있다(firstImageOf 주석 참조). 자리표시자가
   * 타일 크기를 그대로 지켜야 격자가 어긋나지 않는다.
   *
   * <p>[JA] 画像アップロードに失敗した商品が混ざることがある(firstImageOf のコメント参照)。プレースホルダーがタイルの大きさをそのまま守ってこそ格子がずれない。
   */
  it("이미지가 없어도 타일 자리는 지킨다", () => {
    const { container } = draw([product(1, null), product(2, "/b.jpg")]);

    expect(screen.getAllByRole("img")).toHaveLength(1);
    expect(container.querySelectorAll(".aspect-square")).toHaveLength(2);
    expect(screen.getByText("상품 1")).toBeInTheDocument();
  });

  it("상품이 없어도 터지지 않는다", () => {
    draw([]);

    expect(screen.queryAllByRole("img")).toHaveLength(0);
  });
});
