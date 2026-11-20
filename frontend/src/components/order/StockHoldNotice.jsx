import { useEffect, useState } from "react";

import { formatDateTime, parseServerDate } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

/** 재고를 잡아 두는 상태 — 결제 전(NOT_PROCESSED)과 입금 대기(UNPAID). */
// [JA] 在庫を確保しておく状態 — 決済前(NOT_PROCESSED)と入金待ち(UNPAID)。
const HOLDING_STATUSES = ["NOT_PROCESSED", "UNPAID"];

/** setTimeout 이 받는 최대 지연(약 24.8일). 넘기면 즉시 실행된다. */
// [JA] setTimeout が受け付ける最大遅延(約 24.8 日)。超えると即時実行される。
const MAX_TIMEOUT = 2 ** 31 - 1;

/**
 * 결제 전 재고 예약 안내 (docs/59).
 *
 * <p>주문을 만들 때 서버가 재고를 잠시 잡아 둔다({@code order.stockHoldUntil}). 그 시각까지 결제하면
 * 다른 사람이 같은 재고를 가져가지 못한다. 지나면 예약이 풀린다 — 결제는 여전히 되지만, 그 사이 재고가
 * 팔렸으면 결제 뒤 부족분만큼 환불된다. 사용자가 그걸 결제 <b>전에</b> 알아야 한다.
 *
 * <p>예약 시각이 없으면(catalog 에 닿지 못해 예약 없이 주문이 만들어졌거나, 예약 기능이 꺼졌다)
 * 아무것도 그리지 않는다 — "잡아 두었다" 고 말할 근거가 없다.
 *
 * <p>[JA] 決済前の在庫予約案内(docs/59)。
 * 注文作成時にサーバーが在庫をしばらく確保する({@code order.stockHoldUntil})。その時刻までに決済すれば
 * 他の人が同じ在庫を持っていけない。過ぎると予約が解ける — 決済はなおできるが、その間に在庫が
 * 売れていれば決済後に不足分が返金される。ユーザーはそれを決済 <b>前に</b> 知る必要がある。
 * 予約時刻がなければ(catalog に届かず予約なしで注文が作られた、または予約機能がオフ)
 * 何も描かない — 「確保した」と言う根拠がない。
 */
const StockHoldNotice = ({ order }) => {
  const { t } = useTranslation();
  const until = parseServerDate(order?.stockHoldUntil);
  const [now, setNow] = useState(() => Date.now());

  // 화면을 켜 둔 채 기한이 지나면 문구가 바뀌어야 한다 — 기한 순간에 한 번만 다시 그린다.
  // [JA] 画面を開いたまま期限が過ぎたら文言が変わらなければならない — 期限の瞬間に一度だけ再描画する。
  useEffect(() => {
    if (!until) return undefined;
    const left = until.getTime() - Date.now();
    if (left <= 0) return undefined;
    const timer = setTimeout(() => setNow(Date.now()), Math.min(left + 1000, MAX_TIMEOUT));
    return () => clearTimeout(timer);
  }, [until?.getTime()]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!until || !HOLDING_STATUSES.includes(order?.orderStatus)) {
    return null;
  }

  if (until.getTime() > now) {
    return (
      <p
        className="mt-2 rounded border border-sky-200 bg-sky-50 p-3 text-sm text-sky-900"
        data-testid="stock-hold-notice"
      >
        {t("stock_hold.before")} <b>{formatDateTime(until)}</b>{t("stock_hold.after")}
      </p>
    );
  }

  return (
    <p
      className="mt-2 rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900"
      role="status"
      data-testid="stock-hold-expired"
    >
      {t("stock_hold.expired", { time: formatDateTime(until) })}
    </p>
  );
};

export default StockHoldNotice;
