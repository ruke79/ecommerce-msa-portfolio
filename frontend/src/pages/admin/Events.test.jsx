import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import Events from "pages/admin/Events";
import { withI18n } from "i18n/testing";
import {
  adminCreateEvent,
  adminDeleteEvent,
  adminEndEvent,
  adminGetEvent,
  adminListEvents,
  adminUpdateEvent,
  getAdminProducts,
  getCategories,
} from "util/api";

jest.mock("util/api");

const product = (id, name) => ({ id: String(id), name });

const listed = [
  { id: 1, title: "가을 세일", status: "ACTIVE", productCount: 2,
    startsAt: "2026-09-01T00:00:00", endsAt: "2026-09-30T23:59:00" },
  { id: 2, title: "겨울 예고", status: "SCHEDULED", productCount: 0,
    startsAt: "2026-12-01T00:00:00", endsAt: null },
  { id: 3, title: "여름 세일", status: "ENDED", productCount: 5,
    startsAt: "2026-06-01T00:00:00", endsAt: "2026-06-30T23:59:00" },
];

/**
 * 기획전 관리 화면.
 *
 * <p>여기서 지키는 것은 대부분 <b>되돌릴 수 없는 실수를 막는 규칙</b>이다. 진행 중인
 * 세일을 지우면 할인이 조용히 사라지고 기록도 안 남는다. 시작 시각 없이 확정하면 영영
 * 시작되지 않는데 화면에는 "예약"이라고만 보인다.
 */
describe("기획전 관리", () => {
  beforeEach(() => {
    adminListEvents.mockResolvedValue(listed);
    getAdminProducts.mockResolvedValue({
      contents: [product(42, "가죽 자켓"), product(99, "면 티셔츠")],
      nextCursor: -1,
    });
    getCategories.mockResolvedValue([
      { id: "3", name: "여성 의류" },
      { id: "4", name: "남성 의류" },
    ]);
    adminCreateEvent.mockResolvedValue({});
    adminUpdateEvent.mockResolvedValue({});
    adminEndEvent.mockResolvedValue({});
    adminDeleteEvent.mockResolvedValue({});
  });

  afterEach(() => jest.clearAllMocks());

  const type = async (labelPattern, value) => {
    const field = screen.getByLabelText(labelPattern);
    await userEvent.clear(field);
    await userEvent.type(field, value);
  };

  // datetime-local 은 userEvent.type 으로 값이 들어가지 않는다(jsdom). 값을 직접 넣는다.
  const setDateTime = (labelPattern, value) =>
    fireEvent.change(screen.getByLabelText(labelPattern), { target: { value } });

  it("초안·예약·종료까지 전부 보여준다 — 안 보이는 것은 고칠 수 없다", async () => {
    render(withI18n(<Events />));

    expect(await screen.findByText("가을 세일")).toBeInTheDocument();
    expect(screen.getByText("겨울 예고")).toBeInTheDocument();
    expect(screen.getByText("여름 세일")).toBeInTheDocument();
  });

  it("상태를 한글로 보여준다 — 관리자가 ACTIVE 를 해석하지 않아도 된다", async () => {
    render(withI18n(<Events />));

    const row = (t) => screen.getByText(t).closest("tr");
    await screen.findByText("가을 세일");

    expect(within(row("가을 세일")).getByText("진행 중")).toBeInTheDocument();
    expect(within(row("겨울 예고")).getByText("예약")).toBeInTheDocument();
    expect(within(row("여름 세일")).getByText("종료")).toBeInTheDocument();
  });

  it("일본어 화면에서는 상태·안내도 일본어다", async () => {
    render(withI18n(<Events />, "ja"));

    const row = (t) => screen.getByText(t).closest("tr");
    await screen.findByText("가을 세일");

    expect(screen.getByRole("heading", { name: "特集管理" })).toBeInTheDocument();
    expect(within(row("가을 세일")).getByText("開催中")).toBeInTheDocument();
    expect(within(row("겨울 예고")).getByText("予約")).toBeInTheDocument();
    // <b>…</b> 는 굵은 글씨로 그려지고 태그 글자는 남지 않는다
    expect(screen.getByText("明日").tagName).toBe("B");
    expect(screen.queryByText(/<b>/)).not.toBeInTheDocument();
  });

  /**
   * 🔴 진행 중인 것을 지우면 할인이 조용히 사라지고, "어제 30% 였는데요" 라는 문의에
   * 답할 근거도 없어진다. 종료가 맞는 동작이다.
   */
  it("진행 중인 기획전에는 삭제 버튼 대신 종료 버튼을 보여준다", async () => {
    render(withI18n(<Events />));

    const activeRow = (await screen.findByText("가을 세일")).closest("tr");
    expect(within(activeRow).getByText("종료")).toBeInTheDocument();
    expect(within(activeRow).queryByText("삭제")).not.toBeInTheDocument();
  });

  it("종료된 기획전은 지울 수 있고 고칠 수는 없다", async () => {
    render(withI18n(<Events />));

    const endedRow = (await screen.findByText("여름 세일")).closest("tr");
    expect(within(endedRow).getByText("삭제")).toBeInTheDocument();
    // 끝난 기획전의 할인율을 바꾸는 것은 아무 효과가 없다 — 서버도 409 로 막는다.
    expect(within(endedRow).queryByText("수정")).not.toBeInTheDocument();
  });

  it("종료 버튼은 종료 API 를 부른다 — 삭제가 아니다", async () => {
    render(withI18n(<Events />));

    const activeRow = (await screen.findByText("가을 세일")).closest("tr");
    await userEvent.click(within(activeRow).getByText("종료"));

    await waitFor(() => expect(adminEndEvent).toHaveBeenCalledWith(1));
    expect(adminDeleteEvent).not.toHaveBeenCalled();
  });

  /**
   * 🔴 스케줄러는 시작 시각이 있는 SCHEDULED 만 시작시킨다. 시각 없이 확정하면 영영
   * 시작되지 않는데, 목록에는 "예약"이라고만 보여 무엇이 잘못됐는지 알 수 없다.
   */
  it("시작 시각 없이 확정하면 막고 이유를 말한다", async () => {
    render(withI18n(<Events />));
    await screen.findByText("가을 세일");

    await type(/제목/, "새 기획전");
    await userEvent.click(screen.getByLabelText(/확정/));
    await userEvent.click(screen.getByRole("button", { name: "등록" }));

    expect(await screen.findByText(/시작 시각이 필요합니다/)).toBeInTheDocument();
    expect(adminCreateEvent).not.toHaveBeenCalled();
  });

  it("종료가 시작보다 앞서면 막는다 — 아무에게도 안 보이는 기획전이 만들어진다", async () => {
    render(withI18n(<Events />));
    await screen.findByText("가을 세일");

    await type(/제목/, "거꾸로 기획전");
    setDateTime(/^시작 시각$/, "2026-10-10T00:00");
    setDateTime(/^종료 시각/, "2026-10-01T00:00");
    await userEvent.click(screen.getByRole("button", { name: "등록" }));

    expect(await screen.findByText(/앞설 수 없습니다/)).toBeInTheDocument();
    expect(adminCreateEvent).not.toHaveBeenCalled();
  });

  it("제목만 있으면 초안으로 등록된다", async () => {
    render(withI18n(<Events />));
    await screen.findByText("가을 세일");

    await type(/제목/, "초안 기획전");
    await userEvent.click(screen.getByRole("button", { name: "등록" }));

    await waitFor(() => expect(adminCreateEvent).toHaveBeenCalled());
    expect(adminCreateEvent.mock.calls[0][0]).toMatchObject({
      title: "초안 기획전",
      publish: false,
    });
  });

  describe("대상 상품", () => {
    it("이름으로 찾아 담고 할인율을 정한다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      await userEvent.type(screen.getByPlaceholderText("상품 이름으로 찾기"), "자켓");
      await userEvent.click(await screen.findByRole("button", { name: "가죽 자켓" }));

      // 담으면 기본 할인율이 붙는다 — 0 이면 "담았는데 가격이 그대로"가 된다.
      const rate = screen.getByRole("spinbutton", { name: "가죽 자켓 할인율" });
      expect(Number(rate.value)).toBeGreaterThan(0);
    });

    it("100 을 넘는 할인율은 100 으로 잘린다 — 가격이 음수가 되면 안 된다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      await userEvent.type(screen.getByPlaceholderText("상품 이름으로 찾기"), "자켓");
      await userEvent.click(await screen.findByRole("button", { name: "가죽 자켓" }));

      const rate = screen.getByRole("spinbutton", { name: "가죽 자켓 할인율" });
      await userEvent.clear(rate);
      await userEvent.type(rate, "150");

      expect(Number(rate.value)).toBe(100);
    });

    it("담은 상품은 후보 목록에 다시 나오지 않는다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      const search = screen.getByPlaceholderText("상품 이름으로 찾기");
      await userEvent.type(search, "자켓");
      await userEvent.click(await screen.findByRole("button", { name: "가죽 자켓" }));

      await userEvent.type(search, "자켓");
      expect(screen.queryByRole("button", { name: "가죽 자켓" })).not.toBeInTheDocument();
    });

    it("담은 상품과 할인율을 함께 보낸다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      await type(/제목/, "자켓 세일");
      await userEvent.type(screen.getByPlaceholderText("상품 이름으로 찾기"), "자켓");
      await userEvent.click(await screen.findByRole("button", { name: "가죽 자켓" }));
      await userEvent.click(screen.getByRole("button", { name: "등록" }));

      await waitFor(() => expect(adminCreateEvent).toHaveBeenCalled());
      expect(adminCreateEvent.mock.calls[0][0].products).toEqual([
        { productId: 42, discountRate: expect.any(Number) },
      ]);
    });

    it("대상에서 뺄 수 있다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      await userEvent.type(screen.getByPlaceholderText("상품 이름으로 찾기"), "자켓");
      await userEvent.click(await screen.findByRole("button", { name: "가죽 자켓" }));
      await userEvent.click(screen.getByRole("button", { name: "대상에서 제외" }));

      expect(screen.queryByRole("spinbutton", { name: /가죽 자켓/ })).not.toBeInTheDocument();
    });

    /** 기존 기획전의 기간이나 배너를 고치는 데는 상품 목록이 필요 없다. */
    it("상품 목록을 못 받아도 화면은 쓸 수 있다", async () => {
      getAdminProducts.mockRejectedValue(new Error("500"));

      render(withI18n(<Events />));

      expect(await screen.findByText("가을 세일")).toBeInTheDocument();
    });
  });

  describe("수정", () => {
    /**
     * 🔴 진행 중인 기획전의 배너만 고치는 요청이 그것을 취소시키면 안 된다. 폼이
     * publish=false 로 열리면 저장하는 순간 취소된다.
     */
    it("진행 중인 것을 수정할 때 확정 상태를 유지한다", async () => {
      adminGetEvent.mockResolvedValue({
        id: 1, title: "가을 세일", slug: "autumn", status: "ACTIVE",
        startsAt: "2026-09-01T00:00:00", endsAt: "2026-09-30T23:59:00", products: [],
      });

      render(withI18n(<Events />));
      const activeRow = (await screen.findByText("가을 세일")).closest("tr");
      await userEvent.click(within(activeRow).getByText("수정"));

      await waitFor(() => expect(screen.getByLabelText(/확정/)).toBeChecked());
    });

    it("불러온 대상 상품과 할인율을 그대로 보여준다", async () => {
      adminGetEvent.mockResolvedValue({
        id: 1, title: "가을 세일", slug: "autumn", status: "ACTIVE",
        startsAt: "2026-09-01T00:00:00", endsAt: null,
        products: [{ productId: 42, discountRate: 35 }],
      });

      render(withI18n(<Events />));
      const activeRow = (await screen.findByText("가을 세일")).closest("tr");
      await userEvent.click(within(activeRow).getByText("수정"));

      await waitFor(() =>
        expect(screen.getByRole("spinbutton", { name: "가죽 자켓 할인율" })).toHaveValue(35));
      // id 가 아니라 이름으로 보여야 관리자가 무엇인지 안다.
      expect(screen.getByText("가죽 자켓")).toBeInTheDocument();
    });

    it("수정 저장은 update API 로 간다", async () => {
      adminGetEvent.mockResolvedValue({
        id: 1, title: "가을 세일", slug: "autumn", status: "ACTIVE",
        startsAt: "2026-09-01T00:00:00", endsAt: null, products: [],
      });

      render(withI18n(<Events />));
      const activeRow = (await screen.findByText("가을 세일")).closest("tr");
      await userEvent.click(within(activeRow).getByText("수정"));
      await waitFor(() => expect(screen.getByLabelText(/제목/)).toHaveValue("가을 세일"));

      await userEvent.click(screen.getByRole("button", { name: "저장" }));

      await waitFor(() => expect(adminUpdateEvent).toHaveBeenCalledWith(1, expect.anything()));
      expect(adminCreateEvent).not.toHaveBeenCalled();
    });
  });

  /**
   * 카테고리 단위 대상.
   *
   * <p>🔴 상품 단위와 달리 <b>기획전 중에 등록된 상품도 자동으로 포함된다.</b> 상품을
   * 하나씩 담는 방식은 500개 상한에 금방 닿고, 더 나쁘게는 신상품만 정가로 남는다.
   */
  describe("대상 카테고리", () => {
    it("카테고리를 담고 할인율을 정한다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      await userEvent.click(await screen.findByLabelText("여성 의류"));

      const rate = screen.getByRole("spinbutton", { name: "여성 의류 할인율" });
      expect(Number(rate.value)).toBeGreaterThan(0);
    });

    it("담지 않은 카테고리에는 할인율 칸이 없다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      expect(screen.queryByRole("spinbutton", { name: "남성 의류 할인율" }))
        .not.toBeInTheDocument();
    });

    it("담은 카테고리와 할인율을 함께 보낸다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      await type(/제목/, "카테고리 세일");
      await userEvent.click(await screen.findByLabelText("여성 의류"));
      await userEvent.click(screen.getByRole("button", { name: "등록" }));

      await waitFor(() => expect(adminCreateEvent).toHaveBeenCalled());
      expect(adminCreateEvent.mock.calls[0][0].categories).toEqual([
        { categoryId: 3, discountRate: expect.any(Number) },
      ]);
    });

    it("다시 누르면 대상에서 빠진다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      const checkbox = await screen.findByLabelText("여성 의류");
      await userEvent.click(checkbox);
      await userEvent.click(checkbox);

      expect(screen.queryByRole("spinbutton", { name: "여성 의류 할인율" }))
        .not.toBeInTheDocument();
    });

    /** 상품 단위 지정을 없애지 않았다 — 같은 카테고리에서 일부만 더 깎는 경우가 있다. */
    it("상품과 카테고리를 함께 담을 수 있다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      await type(/제목/, "혼합 세일");
      await userEvent.type(screen.getByPlaceholderText("상품 이름으로 찾기"), "자켓");
      await userEvent.click(await screen.findByRole("button", { name: "가죽 자켓" }));
      await userEvent.click(await screen.findByLabelText("여성 의류"));
      await userEvent.click(screen.getByRole("button", { name: "등록" }));

      await waitFor(() => expect(adminCreateEvent).toHaveBeenCalled());
      const payload = adminCreateEvent.mock.calls[0][0];
      expect(payload.products).toHaveLength(1);
      expect(payload.categories).toHaveLength(1);
    });

    it("카테고리를 못 받아도 화면은 쓸 수 있다", async () => {
      getCategories.mockRejectedValue(new Error("500"));

      render(withI18n(<Events />));

      expect(await screen.findByText("가을 세일")).toBeInTheDocument();
    });
  });

  /**
   * 딸린 쿠폰과 대상 고객군.
   *
   * <p>🔴 세그먼트 할인은 진열가가 아니라 <b>쿠폰으로</b> 준다. 상품 조회에는 신원이 없고
   * (공개 라우트), 목록은 캐시되며, 신원을 아는 화면과 모르는 화면이 섞이면 "목록에서
   * 30% 를 보고 들어왔는데 상세는 정가" 가 구조적으로 되살아난다.
   */
  describe("딸린 쿠폰", () => {
    it("이름과 할인율을 함께 보낸다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      await type(/제목/, "쿠폰 기획전");
      await type(/쿠폰 이름/, "가을 추가 10%");
      await type(/쿠폰 할인율/, "10");
      await userEvent.click(screen.getByRole("button", { name: "등록" }));

      await waitFor(() => expect(adminCreateEvent).toHaveBeenCalled());
      expect(adminCreateEvent.mock.calls[0][0]).toMatchObject({
        couponName: "가을 추가 10%",
        couponDiscount: 10,
      });
    });

    /** 0% 쿠폰은 받아 놓고도 결제 때 아무 일이 일어나지 않는다. */
    it("이름만 있고 할인율이 없으면 막는다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      await type(/제목/, "쿠폰 기획전");
      await type(/쿠폰 이름/, "이름뿐인 쿠폰");
      await userEvent.click(screen.getByRole("button", { name: "등록" }));

      expect(await screen.findByText(/할인율을 1 이상/)).toBeInTheDocument();
      expect(adminCreateEvent).not.toHaveBeenCalled();
    });

    it("쿠폰 이름이 없으면 할인율을 보내지 않는다 — 쿠폰 없는 기획전이다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      await type(/제목/, "쿠폰 없는 기획전");
      await userEvent.click(screen.getByRole("button", { name: "등록" }));

      await waitFor(() => expect(adminCreateEvent).toHaveBeenCalled());
      expect(adminCreateEvent.mock.calls[0][0].couponName).toBeNull();
      expect(adminCreateEvent.mock.calls[0][0].couponDiscount).toBeNull();
    });

    it("고객군을 고를 수 있다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      await type(/제목/, "신규 전용");
      await type(/쿠폰 이름/, "신규 20%");
      await type(/쿠폰 할인율/, "20");
      await userEvent.selectOptions(screen.getByLabelText(/받을 수 있는 고객/), "NEW_USER");
      await userEvent.click(screen.getByRole("button", { name: "등록" }));

      await waitFor(() => expect(adminCreateEvent).toHaveBeenCalled());
      expect(adminCreateEvent.mock.calls[0][0].audience).toBe("NEW_USER");
    });

    it("기본 고객군은 전체다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      expect(screen.getByLabelText(/받을 수 있는 고객/)).toHaveValue("ALL");
    });

    it("수정할 때 쿠폰과 고객군을 그대로 불러온다", async () => {
      adminGetEvent.mockResolvedValue({
        id: 1, title: "가을 세일", slug: "autumn", status: "ACTIVE",
        startsAt: "2026-09-01T00:00:00", endsAt: null, products: [],
        categories: [{ categoryId: 3, discountRate: 25 }],
        audience: "NEW_USER", couponName: "신규 20%", couponDiscount: 20, couponValidDays: 7,
      });

      render(withI18n(<Events />));
      const activeRow = (await screen.findByText("가을 세일")).closest("tr");
      await userEvent.click(within(activeRow).getByText("수정"));

      await waitFor(() => expect(screen.getByLabelText(/쿠폰 이름/)).toHaveValue("신규 20%"));
      expect(screen.getByLabelText(/받을 수 있는 고객/)).toHaveValue("NEW_USER");
      expect(screen.getByRole("spinbutton", { name: "여성 의류 할인율" })).toHaveValue(25);
    });

    /**
     * 고객군은 서로 독립인 세 축에서 나온다 — 가입 시점 / 구매 이력 / 등급. 한 축만
     * 있으면 "신규 가입자" 로 겨냥할 수 있는 것을 "아직 구매가 없는 사람" 으로는 겨냥할
     * 수 없는데, 첫 구매 쿠폰의 진짜 대상은 후자다.
     */
    it("세 축을 모두 고를 수 있다 — 가입 시점 · 구매 이력 · 등급", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      const audience = screen.getByLabelText(/받을 수 있는 고객/);
      for (const value of ["NEW_USER", "EXISTING_USER", "PURCHASED",
        "NEVER_PURCHASED", "TIER_SILVER", "TIER_GOLD", "TIER_VIP", "ALL"]) {
        await userEvent.selectOptions(audience, value);
        expect(audience).toHaveValue(value);
      }
    });

    /**
     * 🔴 등급 세그먼트는 전부 "이상" 이다 — 골드 회원은 실버 조건에도 해당한다.
     * 화면이 그냥 "실버" 라고 쓰면 운영자는 <b>실버만</b> 이라고 읽고, 골드·VIP 고객이
     * 빠진 줄 알고 쿠폰을 하나 더 만든다.
     */
    it("등급은 '이상' 이라고 쓴다 — 골드 회원도 실버 조건에 해당한다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      const audience = screen.getByLabelText(/받을 수 있는 고객/);
      expect(within(audience).getByRole("option", { name: "실버 이상" })).toBeInTheDocument();
      expect(within(audience).getByRole("option", { name: "골드 이상" })).toBeInTheDocument();
    });

    /** 등급이 하루 한 번 갱신된다는 사실을 모르면 "왜 안 잡히냐" 는 문의가 운영자에게 간다. */
    it("등급이 언제 갱신되는지 화면이 말해 준다", async () => {
      render(withI18n(<Events />));
      await screen.findByText("가을 세일");

      expect(screen.getByText(/하루에 한 번 다시 매겨집니다/)).toBeInTheDocument();
    });
  });

  it("서버가 거절하면 그 이유를 보여준다", async () => {
    adminDeleteEvent.mockRejectedValue({
      response: { status: 409, data: { message: "진행 중인 기획전은 삭제할 수 없습니다." } },
    });

    render(withI18n(<Events />));
    const endedRow = (await screen.findByText("여름 세일")).closest("tr");
    await userEvent.click(within(endedRow).getByText("삭제"));

    expect(await screen.findByText(/삭제할 수 없습니다/)).toBeInTheDocument();
  });
});
