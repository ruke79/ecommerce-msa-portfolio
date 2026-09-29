import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import RegisterPage from "pages/user/RegisterPage";
import { withI18n } from "i18n/testing";
import api from "util/api";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
  Link: ({ children, to }) => <a href={to}>{children}</a>,
}));

jest.mock("store/AuthContext", () => ({ useAuthContext: () => ({ token: null }) }));

const mockToastError = jest.fn();
const mockToastSuccess = jest.fn();
jest.mock("react-hot-toast", () => ({
  __esModule: true,
  default: { error: (...a) => mockToastError(...a), success: (...a) => mockToastSuccess(...a) },
}));

function renderPage(lang = "ko") {
  const { QueryClient, QueryClientProvider } = require("@tanstack/react-query");
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    withI18n(
      <QueryClientProvider client={client}>
        <RegisterPage />
      </QueryClientProvider>,
      lang
    )
  );
}

async function fillForm() {
  await userEvent.type(screen.getByPlaceholderText("이름"), "홍길동");
  await userEvent.type(screen.getByPlaceholderText(/이메일 주소/), "a@example.com");
  await userEvent.type(screen.getByPlaceholderText("비밀번호"), "pw123456");
  await userEvent.type(screen.getByPlaceholderText("비밀번호 다시 입력"), "pw123456");
  await userEvent.click(screen.getByRole("button", { name: "가입하기" }));
}

describe("RegisterPage", () => {
  afterEach(() => {
    jest.clearAllMocks();
    jest.restoreAllMocks();
  });

  it("🐛 keeps every character typed into the email field", async () => {
    // 회원가입 폼은 이 버그가 가장 심했다. "a@example.com" 을 치면 "@xml.o" 가 남았다 —
    // 즉 **정상적인 이메일로 가입하는 것이 불가능했다.** 원인은 SignInPage 와 같다:
    // 페이지 state 와 Formik 이 값을 이중으로 소유했고 enableReinitialize 가 키 입력마다
    // 폼을 초기화했다.
    renderPage();

    const email = screen.getByPlaceholderText(/이메일 주소/);
    await userEvent.type(email, "a@example.com");

    expect(email).toHaveValue("a@example.com");
  });

  it("keeps every character in the other fields too", async () => {
    renderPage();

    const name = screen.getByPlaceholderText("이름");
    const password = screen.getByPlaceholderText("비밀번호");
    await userEvent.type(name, "홍길동");
    await userEvent.type(password, "pw123456");

    expect(name).toHaveValue("홍길동");
    expect(password).toHaveValue("pw123456");
  });

  it("submits exactly what the user entered", async () => {
    const post = jest.spyOn(api, "post").mockResolvedValue({ data: { message: "ok" } });

    renderPage();
    await fillForm();

    await waitFor(() => expect(post).toHaveBeenCalled());
    const body = post.mock.calls[0][1];
    expect(body).toMatchObject({ username: "홍길동", email: "a@example.com", password: "pw123456" });
  });

  it("🐛 does not claim success when registration fails", async () => {
    // 예전에는 signup() 이 오류를 삼켜 undefined 를 반환했고 useMutation 이 그걸 성공으로
    // 받았다. 그래서 실패해도 "인증 메일을 보냈습니다" 토스트가 뜨고 로그인 화면으로
    // 이동했다 — 사용자는 오지 않는 메일을 기다렸다.
    jest.spyOn(api, "post").mockRejectedValue({
      response: { status: 400, data: { message: "이미 가입된 이메일입니다" } },
    });

    renderPage();
    await fillForm();

    await waitFor(() => expect(mockToastError).toHaveBeenCalledWith("이미 가입된 이메일입니다"));
    expect(mockToastSuccess).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalledWith("/signin");
  });

  it("blocks submission when the passwords do not match", async () => {
    const post = jest.spyOn(api, "post").mockResolvedValue({ data: {} });

    renderPage();
    await userEvent.type(screen.getByPlaceholderText("이름"), "홍길동");
    await userEvent.type(screen.getByPlaceholderText(/이메일 주소/), "a@example.com");
    await userEvent.type(screen.getByPlaceholderText("비밀번호"), "pw123456");
    await userEvent.type(screen.getByPlaceholderText("비밀번호 다시 입력"), "different");
    await userEvent.click(screen.getByRole("button", { name: "가입하기" }));

    expect(await screen.findByText(/비밀번호가 일치하지 않습니다/)).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });

  it("rejects an invalid email before hitting the server", async () => {
    const post = jest.spyOn(api, "post").mockResolvedValue({ data: {} });

    renderPage();
    await userEvent.type(screen.getByPlaceholderText("이름"), "홍길동");
    await userEvent.type(screen.getByPlaceholderText(/이메일 주소/), "not-an-email");
    await userEvent.type(screen.getByPlaceholderText("비밀번호"), "pw123456");
    await userEvent.type(screen.getByPlaceholderText("비밀번호 다시 입력"), "pw123456");
    await userEvent.click(screen.getByRole("button", { name: "가입하기" }));

    expect(await screen.findByText(/이메일 주소 형식이 올바르지 않습니다/)).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });

  it("일본어 화면에서는 검증 메시지도 일본어다", async () => {
    const post = jest.spyOn(api, "post").mockResolvedValue({ data: {} });

    renderPage("ja");
    await userEvent.type(screen.getByPlaceholderText("お名前"), "山田太郎");
    await userEvent.type(screen.getByPlaceholderText("メールアドレス"), "a@example.com");
    await userEvent.type(screen.getByPlaceholderText("パスワード"), "pw123456");
    await userEvent.type(screen.getByPlaceholderText("パスワードを再入力"), "different");
    await userEvent.click(screen.getByRole("button", { name: /登録する/ }));

    expect(await screen.findByText("パスワードが一致しません。")).toBeInTheDocument();
    expect(screen.queryByText(/비밀번호가 일치하지 않습니다/)).not.toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });
});
