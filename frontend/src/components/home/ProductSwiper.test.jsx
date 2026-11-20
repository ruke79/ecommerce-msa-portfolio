import { render as rtlRender, screen } from "@testing-library/react";

import ProductSwiper from "components/home/ProductSwiper";
import { withI18n } from "i18n/testing";

// 대체 텍스트가 사전에서 오므로 I18nProvider 안에서 그린다(기본 한국어).
// [JA] 代替テキストが辞書から来るので I18nProvider の中で描く(既定は韓国語)。
const render = (ui, lang) => rtlRender(withI18n(ui, lang));

// swiper 대역은 jest.config.js 의 moduleNameMapper 가 이미 걸어 둔다
// (src/__mocks__/swiperMock.js) — 여기서 또 mock 하면 그것을 덮어써 깨진다.
// [JA] swiper の代役は jest.config.js の moduleNameMapper がすでに掛けている
//      (src/__mocks__/swiperMock.js) — ここでまた mock するとそれを上書きして壊れる。

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
 *
 * <p>[JA] 🐛 商品画像が画面外へ飛び出した。
 * <img className="w-auto h-auto object-cover"> だった。`w-auto h-auto` は「原寸で描け」という意味で、1200×1600 の商品写真が
 * 220px の箱の中で 1200px 幅でレンダーされた。箱に `overflow-hidden` もなく四方へ溢れた。
 * `object-cover` は死んだ宣言だった — object-fit は要素の箱が原本の比率と違うときだけ意味があるが、`w-auto h-auto` なら
 * 箱 = 原本で合わせるものがない。設定があるので大丈夫に見えたことがこのバグが長く残った理由である。
 * なぜ一瞬見えたか: 読み込み前は大きさが 0 に近くおとなしいが、読み込まれた瞬間に原寸へ跳ね上がり、その後スワイパーがレイアウトを組み直して整う。
 * 🐛 次は逆に空いた。最初の修正で使った h-[300px] + object-contain ははみ出しはしないが、箱の比率と写真の比率が違う分だけ
 * 箱を空ける — 215×300 の箱に正方形の写真なら上下に 85px ずつ白い帯である。比率の箱(aspect-[4/5])を object-cover で埋めれば箱は決して空かない。
 * だからこのファイルのガードは両側を同時に固定する — はみ出しもせず、空けもしない。
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
   *
   * <p>[JA] 🔴 これがこのファイルの核心のガードである。画像は箱の大きさにそのまま従わなければならない — はみ出しも
   * (元のバグ)余らせも(次のバグ)しない。
   */
  it("이미지가 상자를 넘지도, 비우지도 않는다", () => {
    render(<ProductSwiper images={images} />);

    for (const img of screen.getAllByRole("img")) {
      expect(img.className).toContain("h-full");
      expect(img.className).toContain("w-full");
      expect(img.className).toContain("object-cover");
      // w-auto/h-auto 는 "원본 크기로" 라는 뜻이다. 되살아나면 안 된다.
      // [JA] w-auto/h-auto は「原寸で」という意味である。蘇ってはならない。
      expect(img.className).not.toMatch(/\bw-auto\b/);
      expect(img.className).not.toMatch(/\bh-auto\b/);
      // contain 은 상자 비율과 사진 비율의 차이를 흰 띠로 남긴다.
      // [JA] contain は箱の比率と写真の比率の差を白い帯として残す。
      expect(img.className).not.toContain("object-contain");
    }
  });

  /** 제약이 어긋나도 상자 밖으로 나가지 못하게 하는 두 번째 방어선. */
  // [JA] 制約が食い違っても箱の外へ出られないようにする第二の防衛線。
  it("상자가 넘친 것을 잘라낸다", () => {
    render(<ProductSwiper images={images} />);

    const box = screen.getAllByRole("img")[0].parentElement;
    expect(box.className).toContain("overflow-hidden");
  });

  /**
   * 이미지가 오기 전에 높이를 알 수 있어야 늦게 도착해도 레이아웃이 밀리지 않는다.
   * 지연 로딩과 짝이다. 픽셀로 박을 필요는 없다 — 비율 + 폭이면 알 수 있고, 비율
   * 상자여야 `object-cover` 가 남는 자리 없이 채워진다.
   *
   * <p>[JA] 画像が来る前に高さが分かってこそ、遅れて届いてもレイアウトが押されない。
   * 遅延読み込みと対である。ピクセルで固定する必要はない — 比率 + 幅なら分かり、比率の
   * 箱であってこそ `object-cover` が余白なく埋める。
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
  // [JA] 内側が外側より広ければそれだけではみ出す — 幅は親が決める。
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
