import { renderHook, act } from "@testing-library/react";

import useRevalidateWhileVisible from "hook/useRevalidateWhileVisible";

// jsdom 에는 fetch 의 Request 가 없어 데이터 라우터를 띄울 수 없다 — 라우터의 revalidator 를 흉내 낸다.
const mockRevalidator = { state: "idle", revalidate: jest.fn() };
jest.mock("react-router-dom", () => ({ useRevalidator: () => mockRevalidator }));

const setVisibility = (v) => Object.defineProperty(document, "visibilityState", { configurable: true, get: () => v });

/**
 * 상품 상세의 남은 수량이 연 순간에 굳지 않는다(docs/59 §8) — 탭으로 돌아오면, 그리고 보이는 동안 주기적으로 loader 를 다시 돌린다.
 */
describe("useRevalidateWhileVisible", () => {
    beforeEach(() => { mockRevalidator.state = "idle"; mockRevalidator.revalidate.mockClear(); setVisibility("visible"); });
    afterEach(() => jest.useRealTimers());

    it("🔴 창으로 돌아오면(포커스·보이게 됨) 다시 읽는다", () => {
        renderHook(() => useRevalidateWhileVisible(0));
        act(() => { window.dispatchEvent(new Event("focus")); });
        act(() => { document.dispatchEvent(new Event("visibilitychange")); });
        expect(mockRevalidator.revalidate).toHaveBeenCalledTimes(2);
    });

    it("보이는 동안 주기적으로 — 숨겨진 탭에서는 읽지 않는다", () => {
        jest.useFakeTimers();
        renderHook(() => useRevalidateWhileVisible(1000));
        act(() => { jest.advanceTimersByTime(1000); });
        expect(mockRevalidator.revalidate).toHaveBeenCalledTimes(1);

        setVisibility("hidden");
        act(() => { jest.advanceTimersByTime(3000); });
        expect(mockRevalidator.revalidate).toHaveBeenCalledTimes(1);
    });

    it("이미 읽는 중이면 겹쳐 부르지 않는다", () => {
        mockRevalidator.state = "loading";
        renderHook(() => useRevalidateWhileVisible(0));
        act(() => { window.dispatchEvent(new Event("focus")); });
        expect(mockRevalidator.revalidate).not.toHaveBeenCalled();
    });

    it("화면을 떠나면 멈춘다", () => {
        jest.useFakeTimers();
        const { unmount } = renderHook(() => useRevalidateWhileVisible(1000));
        unmount();
        act(() => { jest.advanceTimersByTime(5000); window.dispatchEvent(new Event("focus")); });
        expect(mockRevalidator.revalidate).not.toHaveBeenCalled();
    });
});
