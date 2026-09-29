import { useCallback, useEffect, useRef, useState } from "react";

/**
 * 주문을 다시 읽는 간격 — 처음엔 촘촘히, 점점 늘려 {@link SETTLE_INTERVAL_MS} 에서 멈춘다.
 *
 * <p>예전에는 처음부터 2초 간격이었다. 사가가 10초 폴러를 두 번 건너던 시절(평균 약 10초)에는
 * 그 2초가 묻혔지만, 아웃박스를 커밋 직후 릴레이하면서(docs/56) 사가는 보통 1초 안에 끝난다 —
 * 이제 사용자가 기다리는 시간의 대부분이 <b>첫 재조회까지의 2초</b>였다.
 *
 * <p>그렇다고 전 구간을 촘촘히 하면, 즉시 릴레이가 놓쳐 폴러가 되줍는 경우(최대 20초) 내내
 * 요청이 쏟아진다. 그래서 앞만 촘촘하다: 누적 0.3 → 0.8 → 1.5 → 2.5 → 3.5 → 5초, 그 뒤 2초마다.
 * 첫 5초에 여섯 번, 시한까지 모두 열아홉 번쯤이다(예전 열다섯 번).
 */
export const SETTLE_BACKOFF_MS = [300, 500, 700, 1000, 1000, 1500];
/** 촘촘한 구간이 끝난 뒤의 간격. */
export const SETTLE_INTERVAL_MS = 2000;
/** 그만두는 시한. */
export const SETTLE_TIMEOUT_MS = 30000;

/** n 번째(0부터) 재조회 전에 기다리는 시간. */
export const settleDelay = (attempt) => SETTLE_BACKOFF_MS[attempt] ?? SETTLE_INTERVAL_MS;

/**
 * 결제 확인 뒤 주문이 <b>확정될 때까지</b> 기다린다.
 *
 * <h3>왜 기다려야 하나</h3>
 *
 * <p>결제 확인(복귀 검증·웹훅)은 주문에 결제를 <b>기록</b>할 뿐이다. 주문 상태를
 * {@code PAID} 로 바꾸는 것은 사가다 — 아웃박스 → Kafka → pay-service → 응답 → OrderSaga.
 * 그 왕복이 끝나기 전에는 주문이 여전히 {@code NOT_PROCESSED} 다. 그런데 화면은 확인 응답을
 * 받자마자 "결제가 완료되었습니다" 라고 말했고, 바로 옆의 결제 상태는 X, 주문 상태는
 * NOT_PROCESSED 였다. <b>둘 다 사실이지만 사용자에게는 모순</b>이다.
 *
 * <p>확인이 끝나면 {@code start()} 를 부른다. 그러면 상태가 {@code NOT_PROCESSED} 를 벗어날
 * 때까지 주문을 다시 읽는다 — 보통 1~2초다(처음엔 촘촘히, 점점 느슨하게). 시한을 넘기면 그만두고 {@code timedOut} 을
 * 켠다. 화면은 그때 "확정이 늦어진다" 고 정직하게 말한다. 영원히 돌지 않는다.
 *
 * @param order   지금 화면이 들고 있는 주문
 * @param refetch 주문을 다시 읽는 함수(react-query 의 refetch)
 * @param onSettled 확정된 순간 주문과 함께 한 번 불린다 — 결제 화면은 여기서 장바구니를 비운다
 * @param until     "다 됐다" 의 조건. 기본은 NOT_PROCESSED 를 벗어나는 것이다. 배송 취소처럼
 *                  다른 전이를 기다릴 때 바꾼다 — 그쪽도 Kafka 왕복이라 같은 모양으로 늦는다
 */
const SETTLED_BY_DEFAULT = (status) => Boolean(status) && status !== "NOT_PROCESSED";

export function useOrderSettling(order, refetch, { onSettled, until = SETTLED_BY_DEFAULT } = {}) {
    const [settling, setSettling] = useState(false);
    const [timedOut, setTimedOut] = useState(false);
    const [attempt, setAttempt] = useState(0);
    const startedAt = useRef(0);
    // 확정된 순간 한 번 부른다(장바구니 비우기 등). 최신 콜백을 ref 로 들어 effect 의존성에서 뺀다.
    const onSettledRef = useRef(onSettled);
    onSettledRef.current = onSettled;

    const start = useCallback(() => {
        startedAt.current = Date.now();
        setTimedOut(false);
        setAttempt(0);
        setSettling(true);
    }, []);

    const status = order?.orderStatus;

    useEffect(() => {
        if (!settling) return undefined;
        if (until(status)) {
            setSettling(false);              // 기다리던 전이가 끝났다(PAID 등)
            onSettledRef.current?.(order);
            return undefined;
        }
        if (Date.now() - startedAt.current >= SETTLE_TIMEOUT_MS) {
            setSettling(false);
            setTimedOut(true);
            return undefined;
        }
        const timer = setTimeout(async () => {
            try {
                await refetch?.();
            } finally {
                // 상태가 그대로면 의존성이 바뀌지 않아 effect 가 다시 돌지 않는다 —
                // 시도 횟수를 올려 다음 타이머를 건다.
                setAttempt((n) => n + 1);
            }
        }, settleDelay(attempt));
        return () => clearTimeout(timer);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [settling, status, attempt, refetch, until]);

    return { settling, timedOut, start };
}
