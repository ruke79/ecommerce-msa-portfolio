/**
 * 주문 내역 화면의 상태 탭.
 *
 * <p>{@code name} 은 번역하지 않는다 — slugify 해서 주소(?q=)를 만드는 값이다(sidebar/profile.jsx 주석).
 * 화면에 보이는 글자는 {@code key} 로 사전에서 꺼낸다.
 */
export const ordersLinks = [
    {
        name: "All Orders",
        key: "profile.menu.orders.all",
        filter: "",
    },
    {
        name: "Paid Orders",
        key: "profile.menu.orders.paid",
        filter: "PAID",
    },
    {
        name: "Unpaid Orders",
        key: "profile.menu.orders.unpaid",
        filter: "UNPAID",
    },
    {
        name: "Processing Orders",
        key: "profile.menu.orders.processing",
        filter: "PROCESSING",
    },
    {
        name: "Unprocessing Orders",
        key: "profile.menu.orders.unprocessed",
        filter: "NOT_PROCESSED",
    },
    {
        name: "Dispatched Orders",
        key: "profile.menu.orders.dispatched",
        filter: "DISPATCHED",
    },
    // 🐛 "Delivered" 가 COMPLETED 를 걸렀다 — 도착한 주문은 DELIVERED 라 이 탭은 언제나 비었다.
    //    COMPLETED 는 구매 확정(docs/25 §12)이고 그 탭은 따로 있다.
    {
        name: "Delivered Orders",
        key: "profile.menu.orders.delivered",
        filter: "DELIVERED",
    },
    {
        name: "Completed Orders",
        key: "profile.menu.orders.completed",
        filter: "COMPLETED",
    },
    {
        name: "Cancelled Orders",
        key: "profile.menu.orders.cancelled",
        filter: "CANCELED",
    },
]