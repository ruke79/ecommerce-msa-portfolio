import { render as rtlRender, screen } from "@testing-library/react";

import ProductSwiper from "components/home/ProductSwiper";
import { withI18n } from "i18n/testing";

// 대체 텍스트가 사전에서 오므로 I18nProvider 안에서 그린다(기본 한국어).
const render = (ui, lang) => rtlRender(withI18n(ui, lang));

// swiper 대역은 jest.config.js 의 moduleNameMapper 가 이미 걸어 둔다
// (src/__mocks__/swiperMock.js) — 여기서 또 mock 하면 그것을 덮어써 깨진다.

/**
 * 🐛 <b>상품 이미지가 화면 밖으로 튀어나왔다.</b>
 *
 * <p>{@code <img className="w-auto h-auto object-cover">} 였다. `w-auto h-auto` 는
 * "원본 크기로 그려라" 라는 뜻이라, 1200×1600 상품 사진이 220px 상자 안에서 1200px 폭으로
 * 렌더됐다. 상자에 `overflow-hidden` 도 없어 사방으로 흘러넘쳤다.
 *
 * <p>`object-cover` 는 <b>죽은 선언</b>이었다 — object-fit 은 요소의 상자가 원본 비율과
 * 다를 때만 의미가 있는데, `w-auto h-auto` 면 상자 = 원본이라 맞출 것이 없다. 설정이
 * 있으니 괜찮아 보이는 것이 이 버그가 오래 남은 이유다.
 *
 * <p>왜 <b>순간</b> 보였나: 로드 전에는 크기가 0 에 가까워 얌전하다가, 로드되는 순간
 * 원본 크기로 튀어오르고, 그 다음 스와이퍼가 레이아웃을 다시 잡으며 정리된다.
 *
 * <p>🐛 <b>그다음엔 반대로 비었다.</b> 처음 고칠 때 쓴 {@code h-[300px]} +
 * {@code object-contain} 은 넘치지는 않지만 <b>상자 비율과 사진 비율이 다른 만큼 상자를
 * 비운다</b> — 215×300 상자에 정사각 사진이면 위아래로 85px 씩 흰 띠다. 비율 상자
 * ({@code aspect-[4/5]}) 를 {@code object-cover} 로 채우면 상자가 절대 비지 않는다.
 *
 * <p>그래서 이 파일의 가드는 <b>양쪽을 동시에</b> 고정한다 — 넘치지도, 비우지도 않는다.
 */
describe("상품 이미지 스와이퍼", () => {
  const images = ["/a.jpg", "/b.jpg"];

  it("이미지를 모두 그린다", () => {
    render(<ProductSwiper images={images} />);

    expect(screen.getAllByRole("img")).toHaveLength(2);
  });

  /**
   * 🔴 이것이 이 파일의 핵심 가드다. 이미지가 상자 크기를 그대로 따라야 한다 — 넘치지도
   * (원래 버그) 남기지도(그다음 버그) 않는다.
   */
  it("이미지가 상자를 넘지도, 비우지도 않는다", () => {
    render(<ProductSwiper images={images} />);

    for (const img of screen.getAllByRole("img")) {
      expect(img.className).toContain("h-full");
      expect(img.className).toContain("w-full");
      expect(img.className).toContain("object-cover");
      // w-auto/h-auto 는 "원본 크기로" 라는 뜻이다. 되살아나면 안 된다.
      expect(img.className).not.toMatch(/\bw-auto\b/);
      expect(img.className).not.toMatch(/\bh-auto\b/);
      // contain 은 상자 비율과 사진 비율의 차이를 흰 띠로 남긴다.
      expect(img.className).not.toContain("object-contain");
    }
  });

  /** 제약이 어긋나도 상자 밖으로 나가지 못하게 하는 두 번째 방어선. */
  it("상자가 넘친 것을 잘라낸다", () => {
    render(<ProductSwiper images={images} />);

    const box = screen.getAllByRole("img")[0].parentElement;
    expect(box.className).toContain("overflow-hidden");
  });

  /**
   * 이미지가 오기 전에 높이를 알 수 있어야 늦게 도착해도 레이아웃이 밀리지 않는다.
   * 지연 로딩과 짝이다. 픽셀로 박을 필요는 없다 — 비율 + 폭이면 알 수 있고, 비율
   * 상자여야 `object-cover` 가 남는 자리 없이 채워진다.
   */
  it("상자 비율이 고정돼 있다 — 늦게 와도 레이아웃이 밀리지 않는다", () => {
    render(<ProductSwiper images={images} />);

    const box = screen.getAllByRole("img")[0].parentElement;
    expect(box.className).toMatch(/\baspect-/);
  });

  it("화면 밖 이미지는 나중에 받는다", () => {
    render(<ProductSwiper images={images} />);

    expect(screen.getAllByRole("img")[0]).toHaveAttribute("loading", "lazy");
  });

  /** 안쪽이 바깥보다 넓으면 그것만으로 넘친다 — 폭은 부모가 정한다. */
  it("폭을 스스로 정하지 않는다 — 카드가 정한다", () => {
    const { container } = render(<ProductSwiper images={images} />);

    expect(container.firstChild.className).toContain("w-full");
    expect(container.firstChild.className).not.toMatch(/\bw-\d+\b/);
  });

  it("이미지가 없어도 터지지 않는다", () => {
    render(<ProductSwiper images={[]} />);

    expect(screen.queryAllByRole("img")).toHaveLength(0);
  });
});
