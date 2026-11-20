/**
 * 서버의 상태 코드(PAID·IN_TRANSIT·COMPLETED…)를 사람이 읽는 이름으로 (docs/60 §2).
 *
 * <p>표에 코드를 그대로 찍던 곳이 여섯 군데 있었다 — 한국어 화면에서도 일본어 화면에서도 영어 대문자였다. 서버로 보내거나
 * 비교하는 값은 코드 그대로 두고, <b>보여 줄 때만</b> 이름으로 바꾼다. 모르는 코드(서버에 새 상태가 생겼다)는 코드 그대로
 * 보여 준다 — 빈칸보다 낫다.
 *
 * <p>[JA] サーバーの状態コード(PAID · IN_TRANSIT · COMPLETED…)を人が読む名前に(docs/60 §2)。表にコードをそのまま出していた箇所が六つあった —
 * 韓国語画面でも日本語画面でも英大文字だった。サーバーへ送ったり比較したりする値はコードのまま、<b>見せるときだけ</b>名前に変える。
 * 知らないコード(サーバーに新しい状態ができた)はコードのまま見せる — 空欄よりましだ。
 */
function label(t, prefix, code) {
    if (code === null || code === undefined || code === "") return "";
    const key = `${prefix}.${String(code).trim().toUpperCase()}`;
    const text = t(key);
    return text === key ? String(code) : text;
}

/** 주문 상태 — common/constants/OrderStatus.java */
// [JA] 注文状態 — common/constants/OrderStatus.java
export const orderStatusLabel = (t, code) => label(t, "status.order", code);

/** 배송 상태 — delivery-service DeliveryStatus */
// [JA] 配送状態 — delivery-service DeliveryStatus
export const deliveryStatusLabel = (t, code) => label(t, "status.delivery", code);

/** 일괄 등록 잡의 단계 상태 — Spring Batch BatchStatus */
// [JA] 一括登録ジョブのステップ状態 — Spring Batch BatchStatus
export const batchStatusLabel = (t, code) => label(t, "status.batch", code);
