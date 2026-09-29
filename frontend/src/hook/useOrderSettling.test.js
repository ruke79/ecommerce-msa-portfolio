import { act, renderHook } from "@testing-library/react";

import {
    SETTLE_BACKOFF_MS,
    SETTLE_INTERVAL_MS,
    SETTLE_TIMEOUT_MS,
    settleDelay,
    useOrderSettling,
} from "hook/useOrderSettling";

/**
 * 결제 확인 뒤 주문이 확정될 때까지 다시 읽는다 — 그리고 영원히 돌지 않는다.
 *
 * <p>확인 응답은 주문에 결제를 기록할 뿐이고, PAID 로 바꾸는 것은 사가(Kafka 왕복)다. 그
 * 사이를 화면이 "완료" 라고 말하면 결제 상태 X 와 나란히 놓여 모순으로 보였다.
 */
describe("useOrderSettling", () => {
    beforeEach(() => jest.useFakeTimers());
    afterEach(() => jest.useRealTimers());

    it("start 전에는 아무것도 하지 않는다", () => {
        const refetch = jest.fn();
        const { result } = renderHook(() => useOrderSettling({ orderStatus: "NOT_PROCESSED" }, refetch));
        act(() => jest.advanceTimersByTime(SETTLE_INTERVAL_MS * 3));
        expect(refetch).not.toHaveBeenCalled();
        expect(result.current.settling).toBe(false);
    });

    /** 🔴 핵심 — 상태가 그대로여도 다음 읽기를 건다(의존성이 안 바뀌어도). */
    it("🔴 start 하면 상태가 NOT_PROCESSED 인 동안 간격마다 다시 읽는다", async () => {
        const refetch = jest.fn().mockResolvedValue({});
        const { result } = renderHook(() => useOrderSettling({ orderStatus: "NOT_PROCESSED" }, refetch));

        act(() => result.current.start());
        expect(result.current.settling).toBe(true);

        await act(async () => { jest.advanceTimersByTime(settleDelay(0)); });
        expect(refetch).toHaveBeenCalledTimes(1);
        await act(async () => { jest.advanceTimersByTime(settleDelay(1)); });
        expect(refetch).toHaveBeenCalledTimes(2);
    });

    /**
     * 🔴 사가가 1초 안에 끝나게 되자(docs/56) 사용자가 기다리는 시간의 대부분은 첫 재조회까지의
     * 2초였다. 첫 재조회는 0.3초 뒤다.
     */
    it("🔴 첫 재조회는 곧바로 한다 — 2초를 기다리지 않는다", async () => {
        const refetch = jest.fn().mockResolvedValue({});
        const { result } = renderHook(() => useOrderSettling({ orderStatus: "NOT_PROCESSED" }, refetch));

        act(() => result.current.start());
        await act(async () => { jest.advanceTimersByTime(SETTLE_BACKOFF_MS[0] - 1); });
        expect(refetch).not.toHaveBeenCalled();
        await act(async () => { jest.advanceTimersByTime(1); });
        expect(refetch).toHaveBeenCalledTimes(1);
    });

    it("간격은 촘촘하게 시작해 늘어나고, 2초에서 멈춘다", async () => {
        const refetch = jest.fn().mockResolvedValue({});
        const { result } = renderHook(() => useOrderSettling({ orderStatus: "NOT_PROCESSED" }, refetch));
        act(() => result.current.start());

        // 각 간격을 1ms 모자라게 흘리면 안 읽고, 채우면 정확히 한 번 더 읽는다.
        for (let i = 0; i < SETTLE_BACKOFF_MS.length + 3; i += 1) {
            const delay = settleDelay(i);
            // eslint-disable-next-line no-await-in-loop
            await act(async () => { jest.advanceTimersByTime(delay - 1); });
            expect(refetch).toHaveBeenCalledTimes(i);
            // eslint-disable-next-line no-await-in-loop
            await act(async () => { jest.advanceTimersByTime(1); });
            expect(refetch).toHaveBeenCalledTimes(i + 1);
        }

        expect(SETTLE_BACKOFF_MS.every((d, i, a) => i === 0 || d >= a[i - 1])).toBe(true);
        expect(Math.max(...SETTLE_BACKOFF_MS)).toBeLessThanOrEqual(SETTLE_INTERVAL_MS);
        expect(settleDelay(SETTLE_BACKOFF_MS.length)).toBe(SETTLE_INTERVAL_MS);
        expect(settleDelay(100)).toBe(SETTLE_INTERVAL_MS);
    });

    /**
     * 촘촘한 것은 앞부분뿐이다 — 즉시 릴레이가 놓쳐 폴러가 되줍는 동안(최대 20초) 요청이 쏟아지면
     * 안 된다. 시한까지 전체 횟수가 예전(2초 간격, 15번)보다 크게 늘지 않는다.
     */
    it("시한까지 전체 재조회 횟수는 스무 번을 넘지 않는다", async () => {
        const refetch = jest.fn().mockResolvedValue({});
        const { result } = renderHook(() => useOrderSettling({ orderStatus: "NOT_PROCESSED" }, refetch));
        act(() => result.current.start());

        for (let t = 0; t < SETTLE_TIMEOUT_MS + 5000; t += 100) {
            // eslint-disable-next-line no-await-in-loop
            await act(async () => { jest.advanceTimersByTime(100); });
        }

        expect(result.current.timedOut).toBe(true);
        expect(refetch.mock.calls.length).toBeGreaterThanOrEqual(15);
        expect(refetch.mock.calls.length).toBeLessThanOrEqual(20);
    });

    /** 확정된 순간 한 번 — 결제 화면은 여기서 장바구니를 비운다(결제수단과 무관하게). */
    it("확정되면 onSettled 가 그 주문과 함께 한 번 불린다", () => {
        const refetch = jest.fn().mockResolvedValue({});
        const onSettled = jest.fn();
        let order = { orderStatus: "NOT_PROCESSED" };
        const { result, rerender } = renderHook(() => useOrderSettling(order, refetch, { onSettled }));

        act(() => result.current.start());
        expect(onSettled).not.toHaveBeenCalled();
        order = { orderStatus: "PAID" };
        rerender();
        rerender();

        expect(onSettled).toHaveBeenCalledTimes(1);
        expect(onSettled).toHaveBeenCalledWith({ orderStatus: "PAID" });
    });

    it("상태가 NOT_PROCESSED 를 벗어나면 멈춘다", async () => {
        const refetch = jest.fn().mockResolvedValue({});
        let order = { orderStatus: "NOT_PROCESSED" };
        const { result, rerender } = renderHook(() => useOrderSettling(order, refetch));

        act(() => result.current.start());
        order = { orderStatus: "PAID" };
        rerender();

        expect(result.current.settling).toBe(false);
        expect(result.current.timedOut).toBe(false);
        await act(async () => { jest.advanceTimersByTime(SETTLE_INTERVAL_MS * 2); });
        expect(refetch).not.toHaveBeenCalled();
    });

    /**
     * 배송 취소 뒤에는 주문이 DISPATCHED → PAID 로 돌아오길 기다린다. 그것도 Kafka 왕복이라
     * 결제 확정과 같은 모양으로 늦는다 — 기다리는 조건만 다르다.
     */
    it("until 을 주면 그 조건이 참이 될 때까지 다시 읽는다 — 배송 취소 뒤의 DISPATCHED → PAID", async () => {
        const refetch = jest.fn().mockResolvedValue({});
        let order = { orderStatus: "DISPATCHED" };
        const until = (s) => s !== "PROCESSING" && s !== "DISPATCHED";
        const { result, rerender } = renderHook(() => useOrderSettling(order, refetch, { until }));

        act(() => result.current.start());
        await act(async () => { jest.advanceTimersByTime(SETTLE_INTERVAL_MS); });
        expect(refetch).toHaveBeenCalledTimes(1);          // DISPATCHED 인 동안은 계속 읽는다

        order = { orderStatus: "PAID" };
        rerender();
        expect(result.current.settling).toBe(false);       // 돌아왔다
        await act(async () => { jest.advanceTimersByTime(SETTLE_INTERVAL_MS * 2); });
        expect(refetch).toHaveBeenCalledTimes(1);          // 더 읽지 않는다
    });

    it("시한을 넘기면 그만두고 timedOut 을 켠다 — 영원히 돌지 않는다", async () => {
        const refetch = jest.fn().mockResolvedValue({});
        const { result } = renderHook(() => useOrderSettling({ orderStatus: "NOT_PROCESSED" }, refetch));

        act(() => result.current.start());
        const ticks = Math.ceil(SETTLE_TIMEOUT_MS / SETTLE_INTERVAL_MS) + 1;
        for (let i = 0; i < ticks; i += 1) {
            // eslint-disable-next-line no-await-in-loop
            await act(async () => { jest.advanceTimersByTime(SETTLE_INTERVAL_MS); });
        }

        expect(result.current.settling).toBe(false);
        expect(result.current.timedOut).toBe(true);
        const calls = refetch.mock.calls.length;
        await act(async () => { jest.advanceTimersByTime(SETTLE_INTERVAL_MS * 3); });
        expect(refetch).toHaveBeenCalledTimes(calls);
    });

    it("다시 start 하면 timedOut 이 풀리고 처음부터 기다린다", async () => {
        const refetch = jest.fn().mockResolvedValue({});
        const { result } = renderHook(() => useOrderSettling({ orderStatus: "NOT_PROCESSED" }, refetch));
        act(() => result.current.start());
        const ticks = Math.ceil(SETTLE_TIMEOUT_MS / SETTLE_INTERVAL_MS) + 1;
        for (let i = 0; i < ticks; i += 1) {
            // eslint-disable-next-line no-await-in-loop
            await act(async () => { jest.advanceTimersByTime(SETTLE_INTERVAL_MS); });
        }
        expect(result.current.timedOut).toBe(true);

        act(() => result.current.start());
        expect(result.current.timedOut).toBe(false);
        expect(result.current.settling).toBe(true);
    });
});
