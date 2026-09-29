import { loader } from "pages/home/Home";

const mockGetHome = jest.fn();
const mockGetBanners = jest.fn();
jest.mock("util/api", () => ({
  getHome: (...a) => mockGetHome(...a),
  getBanners: (...a) => mockGetBanners(...a),
}));

/**
 * 🐛 회귀 대상: **홈을 열면 화면이 위아래로 튀었다.**
 *
 * 상품 섹션은 라우터 loader 가 가져오므로 첫 페인트에 이미 있는데, 배너만
 * `CarouselContainer` 가 마운트된 **뒤** useEffect 로 받아 왔다. 그래서 번들 폴백
 * 이미지로 한 번 그린 다음 원격 이미지로 통째 교체됐고, 아직 받지 않은 이미지라
 * 높이가 무너졌다 다시 커지면서 그 아래 전체가 튀었다.
 *
 * "상품 이미지가 배너보다 먼저 로드돼서 부자연스럽다" 는 것이 이 순서다.
 */
describe("홈 loader — 배너를 상품과 함께 받는다", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => jest.restoreAllMocks());

  it("배너를 loader 에서 받는다 — 마운트 후가 아니라", async () => {
    mockGetHome.mockResolvedValue({ sections: [{ id: 1 }] });
    mockGetBanners.mockResolvedValue([{ id: "b1", imageUrl: "/x.png" }]);

    const data = await loader();

    expect(mockGetBanners).toHaveBeenCalled();
    expect(data.banners).toEqual([{ id: "b1", imageUrl: "/x.png" }]);
    expect(data.home).toEqual({ sections: [{ id: 1 }] });
    expect(data.error).toBe(false);
  });

  /**
   * Promise.all 이었다면 배너 API 가 죽었을 때 **상품까지 함께 사라진다.** 첫 화면의
   * 본문이 장식 때문에 날아가면 안 된다.
   */
  it("배너가 실패해도 상품은 살린다", async () => {
    mockGetHome.mockResolvedValue({ sections: [{ id: 1 }] });
    mockGetBanners.mockRejectedValue(new Error("banner down"));

    const data = await loader();

    expect(data.home).toEqual({ sections: [{ id: 1 }] });
    // null 이면 CarouselContainer 가 번들 폴백으로 그린다 — 첫 화면이 비지 않는다.
    expect(data.banners).toBeNull();
    // 배너 실패는 사용자에게 알리지 않는다. 폴백이 그 자리를 채우므로 알아차릴 것이 없다.
    expect(data.error).toBe(false);
  });

  it("상품이 실패해도 배너는 살리고, 그때는 화면에 알린다", async () => {
    mockGetHome.mockRejectedValue(new Error("catalog down"));
    mockGetBanners.mockResolvedValue([{ id: "b1" }]);

    const data = await loader();

    expect(data.home).toBeNull();
    expect(data.banners).toEqual([{ id: "b1" }]);
    expect(data.error).toBe(true);
  });

  /**
   * loader 에서 예외가 나가면 페이지 전체가 에러 화면으로 바뀐다 — 카탈로그 한 곳이
   * 흔들렸다고 헤더와 캐러셀까지 사라질 이유는 없다.
   */
  it("둘 다 실패해도 throw 하지 않는다", async () => {
    mockGetHome.mockRejectedValue(new Error("down"));
    mockGetBanners.mockRejectedValue(new Error("down"));

    await expect(loader()).resolves.toEqual({
      home: null,
      banners: null,
      error: true,
    });
  });
});
