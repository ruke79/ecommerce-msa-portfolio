import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import DashboardLayout from "components/admin/DashboardLayout";

// Sidebar 는 redux 와 라우팅에 얽혀 있다. 여기서 확인할 것은 "사이드바가 렌더링되는가"이지
// 사이드바의 내용이 아니므로 자리 표시자로 바꾼다.
jest.mock("components/admin/Sidebar", () => () => <nav data-testid="sidebar">SIDEBAR</nav>);

/**
 * 🐛 회귀 대상: JSX 안에 `//<Sidebar/>` 라고 적혀 있었다.
 *
 * JSX 에서 `//` 는 주석이 아니라 <b>그냥 글자</b>다. 그래서 사이드바는 렌더링되지 않았고,
 * 대신 관리자 화면 왼쪽 위에 `//` 두 글자가 그대로 찍혔다. 관리자 메뉴 이동이 아예 불가능했다.
 */
describe("DashboardLayout", () => {
  it("renders the sidebar", () => {
    render(
      <MemoryRouter>
        <DashboardLayout />
      </MemoryRouter>
    );

    expect(screen.getByTestId("sidebar")).toBeInTheDocument();
  });

  it("does not print a stray '//' on the page", () => {
    const { container } = render(
      <MemoryRouter>
        <DashboardLayout />
      </MemoryRouter>
    );

    // 고치기 전에는 화면 텍스트에 "//" 가 들어 있었다.
    expect(container.textContent).not.toContain("//");
  });
});
