import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { withI18n } from "i18n/testing";

// Layout 은 Header 를 거쳐 redux·라우터·소켓까지 끌고 온다. 이 테스트가 보는 것은
// 프로필 화면의 동작이므로 뼈대는 비운다(사이드바는 자체 테스트 대상이 아니다).
jest.mock("components/profile/Layout", () => ({
  __esModule: true,
  default: ({ children, title }) => (
    <div>
      <span data-testid="layout-title">{title}</span>
      {children}
    </div>
  ),
}));

const mockUser = { email: "hong@example.com", userId: 7, roles: ["ROLE_USER"] };
jest.mock("store/AuthContext", () => ({
  useAuthContext: () => ({ user: mockUser, isAuthenticated: true }),
}));

const mockGetMyProfile = jest.fn();
const mockUpdateMyProfile = jest.fn();
const mockUploadMyProfileImage = jest.fn();
const mockDeleteMyProfileImage = jest.fn();
jest.mock("util/api", () => ({
  getMyProfile: (...a) => mockGetMyProfile(...a),
  updateMyProfile: (...a) => mockUpdateMyProfile(...a),
  uploadMyProfileImage: (...a) => mockUploadMyProfileImage(...a),
  deleteMyProfileImage: (...a) => mockDeleteMyProfileImage(...a),
}));

const Profile = require("pages/profile/profile").default;

function renderPage(lang = "ko") {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    withI18n(
      <QueryClientProvider client={client}>
        <Profile />
      </QueryClientProvider>,
      lang
    )
  );
}

const profileOf = (over = {}) => ({
  userId: "7",
  email: "hong@example.com",
  name: "홍길동",
  username: "홍길동",
  image: null,
  roles: ["ROLE_USER"],
  signUpMethod: "email",
  ...over,
});

/**
 * 🐛 회귀 대상: **이 화면은 본문이 없었다.**
 *
 * 전체가 `<h2>My Profile</h2>` 한 줄이었다 — 버그가 아니라 만들어진 적이 없다.
 * 그리고 프로필 사진을 바꿀 방법도 없었는데, UI 가 빠진 것이 아니라 서버에 경로 자체가
 * 없었다(프로필 GET 도, 이미지 변경 엔드포인트도).
 */
describe("내 프로필 화면", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetMyProfile.mockResolvedValue(profileOf());
  });

  it("서버에서 받은 이름과 이메일을 보여준다", async () => {
    renderPage();

    expect(await screen.findByDisplayValue("홍길동")).toBeInTheDocument();
    expect(screen.getByDisplayValue("hong@example.com")).toBeInTheDocument();
  });

  /**
   * 이메일은 신원 그 자체이고 JWT 의 subject 이며 다른 서비스(order·cart·coupon)가
   * 사용자를 찾는 키다. 바꿀 수 없는 것을 입력란처럼 보여 주면 눌러 보고 나서야 안다.
   */
  it("이메일은 바꿀 수 없다", async () => {
    renderPage();

    const email = await screen.findByDisplayValue("hong@example.com");
    expect(email).toBeDisabled();
    expect(email).toHaveAttribute("readonly");
    expect(screen.getByText(/변경할 수 없습니다/)).toBeInTheDocument();
  });

  it("이름을 저장하면 서버에 보내고 결과를 알린다", async () => {
    mockUpdateMyProfile.mockResolvedValue(profileOf({ name: "임꺽정" }));
    renderPage();

    const input = await screen.findByDisplayValue("홍길동");
    await userEvent.clear(input);
    await userEvent.type(input, "임꺽정");
    await userEvent.click(screen.getByRole("button", { name: "저장" }));

    await waitFor(() => expect(mockUpdateMyProfile).toHaveBeenCalledWith({ name: "임꺽정" }));
    expect(await screen.findByText("저장되었습니다.")).toBeInTheDocument();
  });

  it("이름이 비면 저장할 수 없다", async () => {
    renderPage();

    const input = await screen.findByDisplayValue("홍길동");
    await userEvent.clear(input);

    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    expect(mockUpdateMyProfile).not.toHaveBeenCalled();
  });

  it("저장이 실패하면 서버가 준 이유를 보여준다", async () => {
    mockUpdateMyProfile.mockRejectedValue({
      response: { data: { message: "이름은 20자까지 입력할 수 있습니다." } },
    });
    renderPage();

    await screen.findByDisplayValue("홍길동");
    await userEvent.click(screen.getByRole("button", { name: "저장" }));

    expect(await screen.findByText("이름은 20자까지 입력할 수 있습니다.")).toBeInTheDocument();
  });

  // ── 프로필 사진 ─────────────────────────────────────────────────────────

  it("사진을 고르면 업로드한다", async () => {
    mockUploadMyProfileImage.mockResolvedValue(profileOf({ image: "https://cdn/x.png" }));
    const { container } = renderPage();

    await screen.findByDisplayValue("홍길동");
    const file = new File(["x"], "me.png", { type: "image/png" });
    await userEvent.upload(container.querySelector('input[type="file"]'), file);

    await waitFor(() => expect(mockUploadMyProfileImage).toHaveBeenCalledWith(file));
  });

  /**
   * 사진이 없는 것은 **정상 상태**다. 삭제 버튼을 늘 보여주면 지울 것이 없는데도
   * 누를 수 있는 것처럼 보인다.
   */
  it("사진이 없으면 삭제 버튼을 보여주지 않는다", async () => {
    renderPage();

    await screen.findByDisplayValue("홍길동");
    expect(screen.queryByRole("button", { name: "사진 삭제" })).not.toBeInTheDocument();
  });

  it("사진이 있으면 삭제할 수 있다", async () => {
    mockGetMyProfile.mockResolvedValue(profileOf({ image: "https://cdn/x.png" }));
    mockDeleteMyProfileImage.mockResolvedValue(profileOf({ image: null }));
    renderPage();

    const remove = await screen.findByRole("button", { name: "사진 삭제" });
    await userEvent.click(remove);

    await waitFor(() => expect(mockDeleteMyProfileImage).toHaveBeenCalled());
  });

  it("업로드가 실패하면 이유를 보여준다", async () => {
    mockUploadMyProfileImage.mockRejectedValue({
      response: { data: { message: "이미지는 5MB까지 올릴 수 있습니다." } },
    });
    const { container } = renderPage();

    await screen.findByDisplayValue("홍길동");
    await userEvent.upload(
      container.querySelector('input[type="file"]'),
      new File(["x"], "big.png", { type: "image/png" })
    );

    expect(await screen.findByText("이미지는 5MB까지 올릴 수 있습니다.")).toBeInTheDocument();
  });

  // ── 실패해도 화면은 산다 ────────────────────────────────────────────────

  /**
   * 전역 QueryClient 는 throwOnError: true 다. 프로필 조회가 그 설정을 따르면 예외가
   * ErrorBoundary 까지 올라가 화면 전체가 사라진다 — 이름과 사진이 비는 것과 화면이
   * 죽는 것은 다르다.
   */
  it("프로필을 못 받아도 화면이 죽지 않고 이메일은 보인다", async () => {
    mockGetMyProfile.mockRejectedValue({ response: { data: { message: "서버 오류" } } });
    renderPage();

    expect(await screen.findByText("서버 오류")).toBeInTheDocument();
    // JWT 의 이메일이 그때의 근거다.
    expect(screen.getByDisplayValue("hong@example.com")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "내 프로필" })).toBeInTheDocument();
  });
});

/**
 * 🔴 문구를 사전으로 옮긴 것이 실제로 화면에 닿는지 확인한다.
 *
 * <p>한국어 테스트는 사전을 거치든 하드코딩이든 똑같이 통과한다 — 일본어로 그려 봐야
 * 컴포넌트가 정말 {@code t()} 를 쓰는지 드러난다.
 */
describe("내 프로필 화면 (일본어)", () => {
  beforeEach(() => {
    mockGetMyProfile.mockResolvedValue(profileOf());
  });

  afterEach(() => jest.clearAllMocks());

  it("🔴 일본어로 렌더된다", async () => {
    renderPage("ja");

    expect(
      await screen.findByRole("heading", { name: "マイプロフィール" })
    ).toBeInTheDocument();
    expect(screen.getByText("お名前")).toBeInTheDocument();
    expect(screen.getByText("メールアドレス")).toBeInTheDocument();
  });

  it("한국어가 남아 있지 않다", async () => {
    renderPage("ja");

    await screen.findByRole("heading", { name: "マイプロフィール" });
    expect(screen.queryByText("내 프로필")).not.toBeInTheDocument();
    expect(screen.queryByText("이름")).not.toBeInTheDocument();
  });

  /** 이메일 주소 자체는 번역 대상이 아니다 — 값은 그대로여야 한다. */
  it("이메일 값은 언어와 무관하게 같다", async () => {
    renderPage("ja");

    expect(await screen.findByDisplayValue("hong@example.com")).toBeInTheDocument();
  });
});
