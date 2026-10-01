const mockDecode = jest.fn();
jest.mock("jwt-decode", () => ({ jwtDecode: (...a) => mockDecode(...a) }));
jest.mock("util/api", () => ({ __esModule: true, default: {}, getRequest: jest.fn() }));

const { refreshDelayMs, MIN_REFRESH_DELAY_MS } = require("util/TokenRefresher");

/**
 * 🔴 선제 갱신 시점 — 이 PC 의 시계를 믿지 않는다(docs/68 §12).
 * 예전에는 PC 시계가 15분 이상 앞서 있으면 막 받은 토큰도 만료로 보여 갱신이 끝없이 돌았다.
 */
describe("refreshDelayMs — 시계가 틀린 PC", () => {
  const iat = 1_700_000_000;
  const exp = iat + 15 * 60;   // 15분짜리 액세스 토큰

  beforeEach(() => mockDecode.mockReturnValue({ iat, exp }));

  it("🔴 PC 시계가 하루 앞서 있어도 즉시 갱신하지 않는다 — 수명(15분) − 10초 뒤", () => {
    const aDayAhead = (exp + 24 * 3600) * 1000;
    expect(refreshDelayMs("t", exp, aDayAhead)).toBe(15 * 60 * 1000 - 10 * 1000);
  });

  it("PC 시계가 늦어도 수명만큼만 기다린다 — 서버 기준으로 만료되기 전에 갱신한다", () => {
    const anHourBehind = (iat - 3600) * 1000;
    expect(refreshDelayMs("t", exp, anHourBehind)).toBe(15 * 60 * 1000 - 10 * 1000);
  });

  it("토큰을 읽을 수 없으면 예전 계산 — 그래도 최소 간격 아래로는 내려가지 않는다(갱신이 쏟아지지 않게)", () => {
    mockDecode.mockImplementation(() => { throw new Error("bad"); });
    expect(refreshDelayMs("t", exp, (exp + 100) * 1000)).toBe(MIN_REFRESH_DELAY_MS);
    expect(refreshDelayMs("t", exp, (exp - 600) * 1000)).toBe(600 * 1000 - 10 * 1000);
  });

  it("iat 가 없으면 예전 계산", () => {
    mockDecode.mockReturnValue({ exp });
    expect(refreshDelayMs("t", exp, (exp - 60) * 1000)).toBe(50 * 1000);
  });
});
