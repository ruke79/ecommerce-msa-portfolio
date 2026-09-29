import { render } from "@testing-library/react";
import useDocumentTitle, { SITE_NAME } from "hook/useDocumentTitle";

const Probe = ({ title }) => {
  useDocumentTitle(title);
  return null;
};

/**
 * 🐛 회귀 대상: **탭 제목이 바뀌지 않았다.**
 *
 * `Header.jsx` 가 `<header>` 안에 `<title>{title}</title>` 을 그리고 있었다. React 18 은
 * `<title>` 을 `<head>` 로 올리지 않는다 — `<body>` 안의 그 태그를 브라우저는 무시한다
 * (호이스팅은 React 19 부터). 게다가 `public/index.html` 에 `<title>` 자체가 없어서
 * 탭에는 URL 이 그대로 보였다.
 */
describe("useDocumentTitle", () => {
  const original = document.title;
  afterEach(() => {
    document.title = original;
  });

  it("제목을 세운다", () => {
    render(<Probe title="장바구니" />);
    expect(document.title).toBe(`장바구니 | ${SITE_NAME}`);
  });

  it("제목이 없으면 사이트 이름만 쓴다 — 'undefined | …' 이 되면 안 된다", () => {
    render(<Probe title={undefined} />);
    expect(document.title).toBe(SITE_NAME);
  });

  it("제목이 바뀌면 따라간다", () => {
    const { rerender } = render(<Probe title="장바구니" />);
    rerender(<Probe title="주문 내역" />);
    expect(document.title).toBe(`주문 내역 | ${SITE_NAME}`);
  });

  /**
   * 되돌리지 않으면 제목을 세우지 않는 화면으로 이동했을 때 이전 화면의 제목이 남는다.
   */
  it("언마운트하면 이전 제목으로 되돌린다", () => {
    document.title = "이전 제목";
    const { unmount } = render(<Probe title="장바구니" />);
    expect(document.title).not.toBe("이전 제목");

    unmount();
    expect(document.title).toBe("이전 제목");
  });
});
