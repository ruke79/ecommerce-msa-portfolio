import { render, screen } from "@testing-library/react";

import TierStatus from "components/profile/TierStatus";
import { withI18n } from "i18n/testing";
import { getMyTier } from "util/api";

jest.mock("util/api");

const status = (over = {}) => ({
  tier: "SILVER",
  displayName: "실버",
  computedAt: "2026-09-16T04:10:00",
  recentPaidAmount: 320000,
  lifetimeOrderCount: 7,
  months: 3,
  silverAmount: 200000,
  goldAmount: 500000,
  vipAmount: 1500000,
  ...over,
});

/**
 * 내 등급 화면.
 *
 * <p>🔴 <b>여기서 지키는 것의 절반은 "없어야 할 것"이다.</b> 진행 게이지나 "골드까지
 * ○○원 남았습니다" 는 넣기 쉽고, 넣는 순간 화면의 성격이 바뀐다 — "당신의 혜택"이
 * "당신이 더 써야 할 금액"이 된다. 등급을 점수처럼 전시하지 않는 것이 이 서비스가
 * 택한 톤이다.
 *
 * <p>대신 근거는 전부 보여 준다. "왜 내가 실버인가"에 화면이 답하지 못하면 그 질문은
 * CS 로 간다.
 */
describe("내 등급", () => {
  beforeEach(() => {
    getMyTier.mockResolvedValue(status());
  });

  afterEach(() => jest.clearAllMocks());

  it("등급 이름을 보여준다", async () => {
    render(withI18n(<TierStatus />));

    expect(await screen.findByText("실버")).toBeInTheDocument();
  });

  it("판단 근거가 된 금액을 보여준다 — '왜 내가 실버인가'에 답한다", async () => {
    render(withI18n(<TierStatus />));

    expect(await screen.findByText(/최근 3개월 결제액 320,000원/)).toBeInTheDocument();
  });

  it("기준 금액을 서버가 준 값으로 보여준다 — 화면에 하드코딩하지 않는다", async () => {
    getMyTier.mockResolvedValue(status({ silverAmount: 111111, goldAmount: 222222 }));

    render(withI18n(<TierStatus />));

    expect(await screen.findByText(/실버 111,111원 이상/)).toBeInTheDocument();
    expect(screen.getByText(/골드 222,222원 이상/)).toBeInTheDocument();
  });

  /**
   * 🔴 이 테스트가 이 화면의 성격을 고정한다. 누가 "친절하게" 게이지나 남은 금액을
   * 넣으면 여기서 막힌다.
   */
  it("'다음 등급까지 얼마'를 말하지 않는다 — 소비를 압박하지 않는다", async () => {
    render(withI18n(<TierStatus />));
    await screen.findByText("실버");

    expect(screen.queryByText(/남았습니다|까지 [\d,]+원|더 구매|달성/)).not.toBeInTheDocument();
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
  });

  /**
   * ⏱️ 등급은 하루 한 번 갱신된다. 화면이 말하지 않으면 "오늘 큰 주문을 했는데 왜
   * 그대로냐"가 반드시 문의로 온다.
   */
  it("하루 한 번 갱신된다는 사실과 마지막 갱신 시각을 알려준다", async () => {
    render(withI18n(<TierStatus />));

    expect(await screen.findByText(/하루에 한 번/)).toBeInTheDocument();
    expect(screen.getByText(/마지막 갱신/)).toBeInTheDocument();
    expect(screen.getByText(/오늘 주문한 금액은 내일부터/)).toBeInTheDocument();
  });

  /** 가입 직후에는 아직 배치가 돈 적이 없다. 그때도 화면이 깨지면 안 된다. */
  it("아직 계산된 적이 없어도 화면이 뜬다", async () => {
    getMyTier.mockResolvedValue(status({ tier: "BASIC", displayName: "일반", computedAt: null, recentPaidAmount: 0 }));

    render(withI18n(<TierStatus />));

    expect(await screen.findByText("일반")).toBeInTheDocument();
    expect(screen.queryByText(/마지막 갱신/)).not.toBeInTheDocument();
  });

  it("일반 등급에게도 받을 수 있는 것을 말해 준다", async () => {
    getMyTier.mockResolvedValue(status({ tier: "BASIC", displayName: "일반" }));

    render(withI18n(<TierStatus />));

    expect(await screen.findByText(/모두에게 열린 쿠폰/)).toBeInTheDocument();
  });

  it("등급이 있으면 그 등급 대상 쿠폰을 안내한다", async () => {
    render(withI18n(<TierStatus />));

    expect(await screen.findByText(/실버 등급 대상 쿠폰/)).toBeInTheDocument();
  });

  /** 등급 하나 때문에 프로필 화면 전체가 죽으면 안 된다. */
  it("불러오지 못해도 화면이 죽지 않는다", async () => {
    getMyTier.mockRejectedValue(new Error("500"));

    render(withI18n(<TierStatus />));

    expect(await screen.findByText(/불러오지 못했습니다/)).toBeInTheDocument();
  });

  it("일본어 화면에서는 문구가 일본어다 — 금액의 원화 표기도 따라간다", async () => {
    render(withI18n(<TierStatus />, "ja"));

    expect(await screen.findByText(/直近3か月のお支払い額 320,000ウォン/)).toBeInTheDocument();
    expect(screen.getByText("会員ランク")).toBeInTheDocument();
    expect(screen.getByText(/1日1回/)).toBeInTheDocument();
    expect(screen.queryByText(/등급|결제액/)).not.toBeInTheDocument();
  });
});
