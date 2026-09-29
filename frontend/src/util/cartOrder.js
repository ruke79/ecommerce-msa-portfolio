/**
 * 브라우저의 장바구니가 이 주문 그대로인가 — 담긴 줄마다 같은 이름·수량의 주문 품목이 있다.
 *
 * 결제된 주문을 볼 때 장바구니를 비울지 정한다(pages/order.jsx). 이름·수량으로만 본다 — 주문 품목에는 사이즈가 없고, 색상은
 * catalog 조회가 실패하면 비어 온다. 빈 장바구니는 "이 주문" 이 아니다(비울 것이 없다).
 */
export function cartIsThisOrder(cartItems, orderProducts) {
    if (!Array.isArray(cartItems) || cartItems.length === 0 || !Array.isArray(orderProducts)) return false;
    const left = orderProducts.map((p) => ({ name: p?.name, qty: Number(p?.qty) }));
    return cartItems.every((item) => {
        const i = left.findIndex((p) => p.name === item?.name && p.qty === Number(item?.qty));
        if (i < 0) return false;
        left.splice(i, 1);
        return true;
    });
}
