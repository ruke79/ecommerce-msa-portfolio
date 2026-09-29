import { act, render, screen } from "@testing-library/react";

import NavigationProgress from "components/loader/NavigationProgress";
import { withI18n } from "i18n/testing";

let mockNavigationState = "idle";
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigation: () => ({ state: mockNavigationState }),
}));

/**
 * 화면이 바뀌는 동안 <b>무언가 진행 중이라는 사실</b>을 보여 준다 (docs/43 D5).
 *
 * <p>🔴 이 앱의 화면 전환은 loader 가 끝나야 일어난다. 데이터 라우터는 로더가 응답할
 * 때까지 <b>URL 도 바꾸지 않고</b> 이전 화면을 그대로 둔다. `/browse` 에서 필터를 눌러도
 * 아무 반응이 없던 이유이고, 그래서 사용자는 클릭이 먹었는지 모른 채 한 번 더 누른다.
 *
 * <p>Root.jsx 에는 `{navigation.state === 'loading' && <p>Loading...</p>}` 이 <b>주석
 * 처리된 채</b> 남아 있었다.
 */
describe("화면 전환 진행 표시", () => {
  beforeEach(() => {
    jest.useFakeTimers();
    mockNavigationState = "idle";
  });

  afterEach(() => {
    jest.runOnlyPendingTimers();
    jest.useRealTimers();
  });

  const advance = (ms) => act(() => { jest.advanceTimersByTime(ms); });

  it("가만히 있을 때는 아무것도 그리지 않는다", () => {
    render(withI18n(<NavigationProgress />));
    advance(1000);

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("전환이 길어지면 진행 표시가 나타난다", () => {
    mockNavigationState = "loading";
    render(withI18n(<NavigationProgress />));

    advance(250);

    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  /**
   * 🔴 캐시된 전환은 수십 ms 에 끝난다. 그때 막대를 띄우면 <b>깜빡임</b>만 남아 오히려
   * 화면이 불안해 보인다 — 진행 표시가 없는 것보다 나쁘다.
   */
  it("짧은 전환에는 나타나지 않는다 — 깜빡임이 없는 것보다 나쁘다", () => {
    mockNavigationState = "loading";
    const { rerender } = render(withI18n(<NavigationProgress />));

    // 🔴 여기서 **전환이 진행되는 중**에 없어야 한다. 끝난 뒤만 확인하면 지연을 통째로
    //    지워도 테스트가 통과한다 — 실제로 지워 보고 통과하는 것을 확인한 뒤 고쳤다.
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    advance(100);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    mockNavigationState = "idle";
    rerender(withI18n(<NavigationProgress />));
    advance(1000);

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("전환이 끝나면 사라진다", () => {
    mockNavigationState = "loading";
    const { rerender } = render(withI18n(<NavigationProgress />));
    advance(250);
    expect(screen.getByRole("status")).toBeInTheDocument();

    mockNavigationState = "idle";
    rerender(withI18n(<NavigationProgress />));

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  /** form 제출도 같은 기다림이다 — 사용자 입장에서 구분할 이유가 없다. */
  it("제출 중에도 나타난다", () => {
    mockNavigationState = "submitting";
    render(withI18n(<NavigationProgress />));

    advance(250);

    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  /** 막대만 그리면 눈으로 보는 사람에게만 전달된다. */
  it("스크린리더에도 알린다", () => {
    mockNavigationState = "loading";
    render(withI18n(<NavigationProgress />));
    advance(250);

    expect(screen.getByRole("status")).toHaveAttribute("aria-live", "polite");
    expect(screen.getByText("불러오는 중…")).toBeInTheDocument();
  });

  /**
   * 화면을 덮지 않는다. 이 앱의 전체화면 스피너(DotLoaderSpinner)가 호출부 대부분에서
   * 주석 처리된 이유가 그것이다 — 덮으면 읽던 내용이 사라지고 화면이 깜빡인다.
   */
  it("화면을 덮지 않는다", () => {
    mockNavigationState = "loading";
    render(withI18n(<NavigationProgress />));
    advance(250);

    const bar = screen.getByRole("status");
    expect(bar.className).toContain("h-1");
    expect(bar.className).not.toContain("h-screen");
  });
});
