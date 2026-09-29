import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import MakeSidebar from "components/admin/Sidebar";
import { withI18n } from "i18n/testing";

const renderSidebar = (lang = "ko") => render(withI18n(<MemoryRouter><MakeSidebar /></MemoryRouter>, lang));

/**
 * 🐛 "Content Management 를 펼쳤는데 하위 메뉴가 안 보인다."
 *
 * <p>하위 메뉴의 높이를 "항목 하나 = 40px" 로 짐작해 height 로 박았는데 실제 항목은 48px 이라
 * 항목이 많을수록 더 잘렸다(다섯 개면 마지막이 통째로). 그룹 제목은 {@code <Link to="">} 라
 * 누르면 빈 주소로 이동했다. 그리고 Orders 그룹은 {@code submenu}(소문자) 라 환불 메뉴가
 * 어디에도 없었다.
 */
describe("관리자 사이드바", () => {
    it("Content Management 를 펼치면 다섯 하위 메뉴가 전부 링크로 보인다", () => {
        renderSidebar();
        const group = screen.getByRole("button", { name: /콘텐츠 관리/ });
        expect(group).toHaveAttribute("aria-expanded", "false");

        fireEvent.click(group);

        expect(group).toHaveAttribute("aria-expanded", "true");
        for (const [text, href] of [
            ["홈 섹션", "/admin/home"], ["배너", "/admin/banner"], ["공지사항", "/admin/notice"],
            ["공지 발송", "/admin/broadcast"], ["기획전", "/admin/event"],
        ]) {
            expect(screen.getByRole("link", { name: text })).toHaveAttribute("href", href);
        }
        // 열린 메뉴는 숨겨져 있지 않고, 높이를 픽셀로 박지 않는다.
        const panel = screen.getByRole("link", { name: "기획전" }).closest(".sub-menu");
        expect(panel).toHaveAttribute("data-open", "true");
        expect(panel).not.toHaveAttribute("style");
    });

    it("그룹 제목은 링크가 아니다 — 누르면 여닫기만 한다", () => {
        renderSidebar();
        const group = screen.getByRole("button", { name: /콘텐츠 관리/ });
        expect(within(group).queryByRole("link")).not.toBeInTheDocument();
        fireEvent.click(group);
        fireEvent.click(group);
        expect(group).toHaveAttribute("aria-expanded", "false");
        expect(screen.queryByRole("link", { name: "기획전" })).not.toBeInTheDocument();
    });

    /** 🔴 환불 화면으로 가는 유일한 메뉴 — `submenu` 오타로 사라져 있었다. */
    it("Orders 를 펼치면 Refunds 가 보인다", () => {
        renderSidebar();
        fireEvent.click(screen.getByRole("button", { name: /주문/ }));
        expect(screen.getByRole("link", { name: "환불" })).toHaveAttribute("href", "/admin/refund");
    });

    it("링크 항목은 링크다", () => {
        renderSidebar();
        expect(screen.getByRole("link", { name: "홈" })).toHaveAttribute("href", "/admin");
    });

    it("일본어로 바꾸면 메뉴 이름도 일본어다 — 영어가 남지 않는다", () => {
        renderSidebar("ja");
        expect(screen.getByRole("link", { name: "ホーム" })).toHaveAttribute("href", "/admin");
        fireEvent.click(screen.getByRole("button", { name: /注文/ }));
        expect(screen.getByRole("link", { name: "返金" })).toHaveAttribute("href", "/admin/refund");
        expect(screen.queryByText("주문")).not.toBeInTheDocument();
    });
});
