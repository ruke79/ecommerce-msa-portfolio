import { render as rtlRender, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { withI18n } from "i18n/testing";

// 문구가 사전에서 오므로 I18nProvider 안에서 그린다(기본 한국어).
// [JA] 文言が辞書から来るので I18nProvider の中で描く(既定は韓国語)。
const render = (ui, lang) => rtlRender(withI18n(ui, lang));

jest.mock("react-router-dom", () => ({
  Link: ({ children, to, onClick }) => (
    <a href={to} onClick={onClick}>{children}</a>
  ),
}));

const ErrorFallback = require("components/common/ErrorFallback").default;

const httpError = (status, message) => ({
  message: `Request failed with status code ${status}`,
  response: { status, data: message ? { message } : {} },
});

/**
 * 🐛 회귀 대상: **오류 화면이 사용자에게 아무 의미가 없었다.**
 *
 * 예전 fallback 은 axios 의 `error.message` 를 그대로 찍었다 —
 * "Request failed with status code 401". 정작 서버가 보낸 설명은 버려졌고, 나갈 길은
 * 같은 화면을 다시 그리는 "Try again" 뿐이었다.
 *
 * <p>이 화면이 중요해진 이유: 인터셉터가 401·500·503 을 더 이상 "로그아웃"이나
 * "홈으로 이동"으로 삼키지 않고 호출부로 돌려주기 시작했다(TokenRefresher).
 * 그 오류들이 결국 여기로 온다.
 *
 * <p>[JA] 🐛 回帰対象: **エラー画面がユーザーにとって何の意味もなかった。**
 * 以前の fallback は axios の `error.message` をそのまま出していた — "Request failed with status code 401"。
 * 肝心のサーバーが送った説明は捨てられ、出口は同じ画面を描き直す "Try again" だけだった。
 * この画面が重要になった理由: インターセプターが 401 · 500 · 503 をもう「ログアウト」や「ホームへ移動」で握りつぶさず
 * 呼び出し側へ返し始めた(TokenRefresher)。そのエラーは結局ここに来る。
 */
describe("ErrorFallback", () => {
  const reset = jest.fn();
  beforeEach(() => jest.clearAllMocks());

  it("axios 의 영문 메시지를 본문에 노출하지 않는다", () => {
    const { container } = render(
      <ErrorFallback error={httpError(500)} resetErrorBoundary={reset} />
    );

    // 원문은 접힌 <details> 안에만 남긴다(문의 대응용). 본문에는 나오면 안 된다 —
    // "Request failed with status code 500" 은 사용자에게 아무 의미가 없다.
    // [JA] 原文は折りたたんだ <details> の中にだけ残す(問い合わせ対応用)。本文に出てはならない —
    //      "Request failed with status code 500" はユーザーにとって何の意味もない。
    const details = container.querySelector("details");
    const body = container.cloneNode(true);
    body.querySelector("details")?.remove();

    expect(body.textContent).not.toMatch(/Request failed with status code/);
    expect(details.textContent).toMatch(/Request failed with status code/);
    expect(screen.getByRole("heading")).toHaveTextContent("문제가 발생했습니다");
  });

  it("서버가 보낸 설명이 있으면 그것을 쓴다 — 우리 일반론보다 구체적이다", () => {
    render(
      <ErrorFallback
        error={httpError(400, "이름은 20자까지 입력할 수 있습니다.")}
        resetErrorBoundary={reset}
      />
    );

    expect(screen.getByText("이름은 20자까지 입력할 수 있습니다.")).toBeInTheDocument();
  });

  /**
   * 401 은 이제 로그아웃을 뜻하지 않는다 — 갱신이 성공했는데도 401 이면 그 기능에
   * 권한이 없는 것이다. 사용자가 할 수 있는 일(다른 계정으로 로그인)을 알려 준다.
   *
   * <p>[JA] 401 はもうログアウトを意味しない — 更新が成功したのに 401 ならその機能に権限がないのである。
   * ユーザーにできること(別アカウントでログイン)を知らせる。
   */
  it("401·403 은 권한 문제로 안내하고 로그인 길을 준다", () => {
    for (const status of [401, 403]) {
      const { unmount } = render(
        <ErrorFallback error={httpError(status)} resetErrorBoundary={reset} />
      );
      expect(screen.getByRole("heading")).toHaveTextContent("권한이 없습니다");
      expect(screen.getByText("로그인")).toBeInTheDocument();
      unmount();
    }
  });

  it("상태별로 다른 안내를 준다 — 할 수 있는 일이 다르다", () => {
    const cases = [
      [404, "찾을 수 없습니다"],
      [429, "요청이 너무 잦습니다"],
      [503, "서버가 응답하지 않습니다"],
      [500, "문제가 발생했습니다"],
    ];
    for (const [status, heading] of cases) {
      const { unmount } = render(
        <ErrorFallback error={httpError(status)} resetErrorBoundary={reset} />
      );
      expect(screen.getByRole("heading")).toHaveTextContent(heading);
      unmount();
    }
  });

  it("권한 문제가 아니면 로그인 버튼을 보여주지 않는다", () => {
    render(<ErrorFallback error={httpError(500)} resetErrorBoundary={reset} />);

    expect(screen.queryByText("로그인")).not.toBeInTheDocument();
  });

  /**
   * "Try again" 하나뿐이면 원인이 그대로일 때 같은 화면이 다시 죽는다 — 나갈 길이 없다.
   *
   * <p>[JA] "Try again" 一つだけだと原因がそのままのとき同じ画面がまた落ちる — 出口がない。
   */
  it("홈으로 나갈 길이 있다", () => {
    render(<ErrorFallback error={httpError(500)} resetErrorBoundary={reset} />);

    expect(screen.getByText("홈으로")).toBeInTheDocument();
  });

  it("다시 시도를 누르면 경계를 초기화한다", async () => {
    render(<ErrorFallback error={httpError(500)} resetErrorBoundary={reset} />);

    await userEvent.click(screen.getByRole("button", { name: "다시 시도" }));

    expect(reset).toHaveBeenCalled();
  });

  it("타임아웃은 네트워크 문제로 안내한다", () => {
    render(
      <ErrorFallback
        error={{ code: "ECONNABORTED", message: "timeout of 15000ms exceeded" }}
        resetErrorBoundary={reset}
      />
    );

    expect(screen.getByRole("heading")).toHaveTextContent("응답이 너무 늦습니다");
  });

  it("HTTP 응답이 없는 렌더 오류에도 터지지 않는다", () => {
    expect(() =>
      render(
        <ErrorFallback
          error={new TypeError("Cannot read properties of undefined")}
          resetErrorBoundary={reset}
        />
      )
    ).not.toThrow();

    expect(screen.getByRole("heading")).toHaveTextContent("문제가 발생했습니다");
  });

  /**
   * 기술적인 내용은 사용자에게 소음이지만, 문의를 받는 쪽에는 이 한 줄이 전부다.
   * 숨기되 지우지는 않는다.
   *
   * <p>[JA] 技術的な内容はユーザーには雑音だが、問い合わせを受ける側にはこの一行がすべてである。隠すが消しはしない。
   */
  it("기술 정보는 접어서 남겨 둔다", () => {
    render(<ErrorFallback error={httpError(500)} resetErrorBoundary={reset} />);

    expect(screen.getByText("기술 정보")).toBeInTheDocument();
    expect(screen.getByText(/HTTP 500/)).toBeInTheDocument();
  });

  it("일본어 화면에서는 일본어로 안내한다 — 한국어가 남지 않는다", () => {
    const { container } = render(
      <ErrorFallback error={httpError(401)} resetErrorBoundary={reset} />,
      "ja"
    );

    expect(screen.getByRole("heading")).toHaveTextContent("権限がありません");
    expect(screen.getByRole("button", { name: "再試行" })).toBeInTheDocument();
    expect(screen.getByText("ホームへ")).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/[가-힣]/);
  });
});
