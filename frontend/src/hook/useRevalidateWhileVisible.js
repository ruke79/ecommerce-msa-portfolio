import { useEffect, useRef } from "react";
import { useRevalidator } from "react-router-dom";

/**
 * 화면을 연 채로 두어도 loader 의 값이 낡지 않게 — 탭으로 돌아올 때와, 보이는 동안 {@code intervalMs} 마다 loader 를 다시 돌린다.
 *
 * <p>상품 상세의 남은 수량이 이유다(docs/59 §8). 서버는 응답마다 다른 주문의 결제 전 예약을 빼서 주지만, 상세 화면은 연 순간의
 * 값을 들고 있었다 — 그 뒤 생긴 예약은 새로고침 전까지 보이지 않았다. 담기 조회가 그 순간의 값을 다시 보긴 하지만, 손님은
 * "3개 남음" 을 보고 3개를 고른 뒤에야 거절당했다.
 *
 * <p>숨겨진 탭에서는 돌지 않는다(요청을 아낀다). 이미 다시 읽는 중이면 겹쳐 부르지 않는다.
 */
export default function useRevalidateWhileVisible(intervalMs = 60_000) {
    const revalidator = useRevalidator();
    const ref = useRef(revalidator);
    ref.current = revalidator;

    useEffect(() => {
        const refresh = () => {
            if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
            if (ref.current.state !== "idle") return;
            ref.current.revalidate();
        };
        const onVisibility = () => {
            if (document.visibilityState === "visible") refresh();
        };
        window.addEventListener("focus", refresh);
        document.addEventListener("visibilitychange", onVisibility);
        const timer = intervalMs > 0 ? setInterval(refresh, intervalMs) : null;
        return () => {
            window.removeEventListener("focus", refresh);
            document.removeEventListener("visibilitychange", onVisibility);
            if (timer) clearInterval(timer);
        };
    }, [intervalMs]);
}
