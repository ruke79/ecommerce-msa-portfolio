import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import EventList from "pages/event/EventList";
import { withI18n } from "i18n/testing";
import { getEvents } from "util/api";

jest.mock("util/api", () => ({ __esModule: true, getEvents: jest.fn() }));
jest.mock("hook/useDocumentTitle", () => () => {});

const renderList = (lang) => render(withI18n(<MemoryRouter><EventList /></MemoryRouter>, lang));

beforeEach(() => jest.clearAllMocks());

/**
 * 🔴 기획전 목록의 문구가 한국어로 박혀 있었다 — 일본어로 그려 봐야 드러난다.
 * 기획전 제목(서버 값)은 번역하지 않는다.
 */
describe("기획전 목록", () => {
    it("한국어 — 비어 있으면 그렇다고 말한다", async () => {
        getEvents.mockResolvedValue([]);
        renderList("ko");

        expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("기획전");
        expect(await screen.findByText("진행 중인 기획전이 없습니다.")).toBeInTheDocument();
    });

    it("일본어 — 제목·빈 목록 안내가 일본어다", async () => {
        getEvents.mockResolvedValue([]);
        renderList("ja");

        expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("特集");
        expect(await screen.findByText("開催中の特集はありません。")).toBeInTheDocument();
    });

    it("일본어 — 불러오지 못하면 일본어로 알린다", async () => {
        getEvents.mockRejectedValue(new Error("boom"));
        renderList("ja");

        expect(await screen.findByText("特集を読み込めませんでした。")).toBeInTheDocument();
    });

    it("기획전 제목은 서버 값 그대로다", async () => {
        getEvents.mockResolvedValue([{ id: 1, slug: "s", title: "가을 세일" }]);
        renderList("ja");

        expect(await screen.findByText("가을 세일")).toBeInTheDocument();
    });
});
