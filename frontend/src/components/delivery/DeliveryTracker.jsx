import { useCallback, useEffect, useRef, useState } from "react";
import {
  IoCheckmarkCircle,
  IoCube,
  IoAirplane,
  IoHome,
  IoWarning,
  IoCloseCircle,
  IoChevronDown,
  IoChevronUp,
  IoOpenOutline,
  IoLocationOutline,
} from "react-icons/io5";

import { fetchDeliveryByOrder, cancelDelivery } from "api/deliveryApi";
import "./delivery.css";
import { parseServerDate } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 배송 추적 — 아마존 주문 상세의 배송 영역을 참고했다.
 *
 * <p>설계 판단 두 가지:
 *
 * 1. **단계를 접는다.** 서버의 내부 상태는 12개지만 사용자에게는 4~5단계만 보여준다.
 *    상태를 그대로 노출하면 "CUSTOMS_CLEARED 가 뭐지?" 가 된다. 자세한 이력은 접힌 영역에
 *    둔다 — 아마존이 하는 방식이고, 필요한 사람만 펼친다.
 *
 * 2. **단계 매핑을 서버가 준 `stage` 로 한다.** 프론트가 상태 enum 을 직접 매핑하면 서버가
 *    상태를 추가할 때마다 양쪽을 고쳐야 하고, 둘이 어긋나면 화면이 조용히 틀린다.
 */

/**
 * 국내 배송의 진행 단계. 통관은 국제 배송에만 끼워 넣는다.
 *
 * <p>이름표는 사전의 키로 둔다 — 모듈이 읽힐 때 문구를 만들면 그 순간의 언어로 굳는다. 그릴 때 번역한다.
 */
const DOMESTIC_STAGES = [
  { key: "ORDERED", labelKey: "delivery.stage.ordered", Icon: IoCheckmarkCircle },
  { key: "SHIPPED", labelKey: "delivery.stage.shipped", Icon: IoCube },
  { key: "IN_TRANSIT", labelKey: "delivery.stage.in_transit", Icon: IoAirplane },
  { key: "DELIVERED", labelKey: "delivery.stage.delivered", Icon: IoHome },
];

const INTERNATIONAL_STAGES = [
  { key: "ORDERED", labelKey: "delivery.stage.ordered", Icon: IoCheckmarkCircle },
  { key: "SHIPPED", labelKey: "delivery.stage.shipped", Icon: IoCube },
  { key: "IN_TRANSIT", labelKey: "delivery.stage.international", Icon: IoAirplane },
  { key: "CUSTOMS", labelKey: "delivery.stage.customs", Icon: IoLocationOutline },
  { key: "DELIVERED", labelKey: "delivery.stage.delivered", Icon: IoHome },
];

/** 진행 중인 배송을 다시 읽는 간격. 시뮬레이터는 1분마다, 실제 택배사는 몇 시간마다 바뀐다. */
export const POLL_MS = 20000;

/** 더 바뀌지 않는 상태 — 여기 오면 읽기를 멈춘다. */
const TERMINAL = new Set(["DELIVERED", "RETURNED", "CANCELLED"]);

/**
 * @param orderStatus 주문 상태. 바뀌면(PROCESSING → DISPATCHED → DELIVERED) 배송도 바로 다시 읽는다 —
 *                    주문은 react-query 가 갱신하는데 배송만 옛 값으로 남으면 같은 화면이 다른 말을 한다.
 * @param onCancelled 배송 취소가 서버에서 받아들여진 뒤 한 번 불린다. 주문 상태는 Kafka
 *                    왕복 뒤에 바뀌므로(DISPATCHED → PAID) 주문 화면이 이것을 받아 다시 읽는다.
 * @param onStatusChange 주기 읽기에서 배송 상태가 <b>바뀐 것을 본</b> 순간 새 배송과 함께 불린다
 *                    (첫 읽기는 제외). 주문 상태도 같은 이벤트로 바뀌지만 Kafka 왕복 뒤라,
 *                    주문 화면이 이것을 받아 주문을 다시 읽는다 — 안 그러면 여기만 "발송" 이고
 *                    진행 띠는 "상품 준비" 로 남는다.
 */
export default function DeliveryTracker({ orderId, orderStatus, onChangeAddress, onCancelled, onStatusChange }) {
  const { t } = useTranslation();
  const [delivery, setDelivery] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [expanded, setExpanded] = useState({});
  const [busy, setBusy] = useState(false);

  // 🐛 송장이 등록되면 여기(폴링)는 "발송되었습니다" 로 바뀌는데 주문 진행 띠·환불 안내는
  //    새로고침해야 바뀌었다. 주문은 react-query 가 들고 있고 아무도 다시 읽으라고 하지
  //    않았기 때문이다. 배송 상태가 **바뀐 것을 본 순간** 부모에게 알린다 — 첫 읽기는 변화가
  //    아니므로 알리지 않고, 같은 상태를 다시 받은 것도 아니다.
  const lastStatus = useRef(undefined);
  const onStatusChangeRef = useRef(onStatusChange);
  onStatusChangeRef.current = onStatusChange;

  const load = useCallback(async () => {
    let next = null;
    try {
      next = await fetchDeliveryByOrder(orderId);
      setDelivery(next);
    } catch {
      // 아직 배송이 만들어지지 않았을 수 있다(결제 직후). 오류가 아니라 정상 상태다.
      setDelivery(null);
    } finally {
      setLoading(false);
    }
    const status = next?.status ?? null;
    if (lastStatus.current !== undefined && lastStatus.current !== status && next) {
      onStatusChangeRef.current?.(next);
    }
    lastStatus.current = status;
  }, [orderId]);

  // 🐛 처음 한 번만 읽었다. 송장이 등록되고 시뮬레이터가 단계를 밟아도 화면은 그대로였고,
  //    주문 상태(react-query)는 갱신되니 진행 바는 "발송" 인데 여기는 "배송 준비 중" 이었다.
  //    주문 상태가 바뀔 때 바로 읽고, 끝나지 않은 배송은 주기적으로 읽는다.
  useEffect(() => { load(); }, [load, orderStatus]);

  const active = !loading && (!delivery || !TERMINAL.has(delivery.status));
  useEffect(() => {
    if (!active) return undefined;
    const timer = setInterval(load, POLL_MS);
    return () => clearInterval(timer);
  }, [active, load]);

  const onCancel = async () => {
    setBusy(true);
    const result = await cancelDelivery(orderId);
    if (result.ok) {
      setDelivery(await fetchDeliveryByOrder(orderId));
      onCancelled?.();
    } else {
      setError(result.message);
    }
    setBusy(false);
  };

  if (loading) return <div className="dlv dlv--loading">{t("delivery.loading")}</div>;

  if (!delivery) {
    return (
      <div className="dlv dlv--pending">
        <IoCube size={20} />
        <span>{t("delivery.pending")}</span>
      </div>
    );
  }

  const international = delivery.shipments?.some((s) => s.international);
  const stages = international ? INTERNATIONAL_STAGES : DOMESTIC_STAGES;
  const cancelled = delivery.stage === "CANCELLED";
  const problem = delivery.stage === "EXCEPTION";

  return (
    <div className="dlv">
      <DeliveryHeadline delivery={delivery} problem={problem} cancelled={cancelled} />

      {/* 진행 바. 예외·취소 상태에서는 진행을 그리지 않는다 — 진행 바만 있으면 문제가 묻힌다. */}
      {!problem && !cancelled && (
        <ProgressBar stages={stages} currentStage={delivery.stage} />
      )}

      {delivery.reorderedFromOrderId && (
        <div className="dlv__note">
          {t("delivery.reordered", { id: delivery.reorderedFromOrderId })}
        </div>
      )}

      {/* 분할 배송이면 상자별로 나눠 보여준다 — 아마존의 "패키지 1/2" */}
      {delivery.shipments?.map((shipment) => (
        <ShipmentCard
          key={shipment.shipmentId}
          shipment={shipment}
          total={delivery.shipments.length}
          expanded={!!expanded[shipment.shipmentId]}
          onToggle={() =>
            setExpanded((prev) => ({
              ...prev,
              [shipment.shipmentId]: !prev[shipment.shipmentId],
            }))
          }
        />
      ))}

      <AddressPanel
        address={delivery.address}
        canChange={delivery.canChangeAddress}
        onChangeAddress={onChangeAddress}
      />

      {delivery.canCancel && !cancelled && (
        <button type="button" className="dlv__cancel" onClick={onCancel} disabled={busy}>
          {t("delivery.cancel")}
        </button>
      )}

      {error && <div className="dlv__error" role="alert">{error}</div>}
    </div>
  );
}

/** 맨 위의 큰 한 줄. 사용자가 가장 알고 싶은 것 하나만 크게 보여준다. */
function DeliveryHeadline({ delivery, problem, cancelled }) {
  const { t } = useTranslation();
  if (cancelled) {
    return (
      <div className="dlv__headline dlv__headline--cancelled">
        <IoCloseCircle size={22} /> {t("delivery.headline.cancelled")}
      </div>
    );
  }
  if (problem) {
    return (
      <div className="dlv__headline dlv__headline--problem">
        <IoWarning size={22} /> {t("delivery.headline.problem")}
        <div className="dlv__sub">{t("delivery.headline.contact")}</div>
      </div>
    );
  }
  if (delivery.status === "DELIVERED") {
    return (
      <div className="dlv__headline dlv__headline--done">
        <IoCheckmarkCircle size={22} /> {t("delivery.headline.delivered")}
      </div>
    );
  }
  // OUT_FOR_DELIVERY 는 가장 가치 있는 정보다 — 오늘 받는다.
  if (delivery.status === "OUT_FOR_DELIVERY") {
    return <div className="dlv__headline dlv__headline--today">{t("delivery.headline.today")}</div>;
  }
  if (delivery.status === "CUSTOMS_HOLD") {
    return (
      <div className="dlv__headline dlv__headline--problem">
        <IoWarning size={22} /> {t("delivery.headline.customs_hold")}
        <div className="dlv__sub">{t("delivery.headline.customs_hint")}</div>
      </div>
    );
  }
  if (delivery.estimatedAt) {
    return (
      <div className="dlv__headline">
        <EstimatedDate value={delivery.estimatedAt} />
      </div>
    );
  }
  // 🐛 예상 도착일이 없으면 무조건 "배송 준비 중" 이었다. 송장을 막 등록하면 첫 조회 전이라
  //    도착일이 없는데, 진행 바는 "발송" 을 가리키면서 헤드라인은 준비 중이라고 했다.
  //    도착일이 없을 때는 **단계**로 말한다.
  return (
    <div className="dlv__headline">
      {t(STAGE_HEADLINE[delivery.stage] ?? "delivery.headline.preparing")}
    </div>
  );
}

/** 예상 도착일을 아직 모를 때 단계별로 하는 말(사전의 키). 진행 바와 같은 것을 가리켜야 한다. */
const STAGE_HEADLINE = {
  ORDERED: "delivery.headline.preparing",
  SHIPPED: "delivery.headline.shipped",
  IN_TRANSIT: "delivery.headline.in_transit",
  CUSTOMS: "delivery.headline.customs",
};

function EstimatedDate({ value }) {
  const { t, lang } = useTranslation();
  const parsed = parseServerDate(value);
  if (!parsed) return t("delivery.stage.in_transit");

  // 요일 이름은 Intl 이 안다 — 한국어 "월", 일본어 "月". 예전에는 한국어 배열이 박혀 있었다.
  const weekday = parsed.toLocaleDateString(lang === "ja" ? "ja-JP" : "ko-KR", { weekday: "short" });
  return t("delivery.estimated", { month: parsed.getMonth() + 1, day: parsed.getDate(), weekday });
}

function ProgressBar({ stages, currentStage }) {
  const { t } = useTranslation();
  const currentIndex = stages.findIndex((s) => s.key === currentStage);

  return (
    <ol className="dlv__progress" aria-label={t("delivery.progress_label")}>
      {stages.map((stage, index) => {
        const done = currentIndex >= 0 && index <= currentIndex;
        const { Icon } = stage;
        return (
          <li
            key={stage.key}
            className={`dlv__step ${done ? "dlv__step--done" : ""}`}
            aria-current={index === currentIndex ? "step" : undefined}
          >
            <span className="dlv__step-icon"><Icon size={18} /></span>
            <span className="dlv__step-label">{t(stage.labelKey)}</span>
          </li>
        );
      })}
    </ol>
  );
}

function ShipmentCard({ shipment, total, expanded, onToggle }) {
  const { t } = useTranslation();
  return (
    <div className="dlv__shipment">
      <div className="dlv__shipment-head">
        <span className="dlv__pkg">
          {total > 1 ? t("delivery.package", { seq: shipment.sequenceNo, total }) : t("delivery.info")}
        </span>
        {shipment.carrierName && (
          <span className="dlv__carrier">
            {shipment.carrierName}
            {shipment.trackingNumber && ` · ${shipment.trackingNumber}`}
          </span>
        )}
      </div>

      {shipment.items?.length > 0 && (
        <ul className="dlv__items">
          {shipment.items.map((item, i) => (
            <li key={`${item.productId}-${i}`}>
              {item.imageUrl && <img src={item.imageUrl} alt="" loading="lazy" />}
              <span>{item.productName}</span>
              {item.quantity > 1 && <em>×{item.quantity}</em>}
            </li>
          ))}
        </ul>
      )}

      {shipment.events?.length > 0 && (
        <>
          <button type="button" className="dlv__toggle" onClick={onToggle} aria-expanded={expanded}>
            {t("delivery.details")} {expanded ? <IoChevronUp /> : <IoChevronDown />}
          </button>
          {expanded && (
            <ol className="dlv__timeline">
              {[...shipment.events].reverse().map((event, i) => (
                <li key={i}>
                  <time>{formatEventTime(event.occurredAt)}</time>
                  <span className="dlv__loc">{event.location}</span>
                  <span className="dlv__desc">{event.description}</span>
                </li>
              ))}
            </ol>
          )}
        </>
      )}

      {/* 택배사 자체 조회 페이지 — 우리가 못 보여주는 정보를 사용자가 직접 볼 수 있게 한다.
          추적 데이터가 늦거나 빠질 때의 안전판이기도 하다. */}
      {shipment.trackingUrl && (
        <a className="dlv__external" href={shipment.trackingUrl}
           target="_blank" rel="noopener noreferrer">
          {t("delivery.carrier_lookup")} <IoOpenOutline size={14} />
        </a>
      )}
    </div>
  );
}

function AddressPanel({ address, canChange, onChangeAddress }) {
  const { t } = useTranslation();
  if (!address) return null;
  return (
    <div className="dlv__address">
      <div className="dlv__address-head">
        <span>{t("delivery.address")}</span>
        {canChange ? (
          <button type="button" className="dlv__link" onClick={onChangeAddress}>
            {t("delivery.change")}
          </button>
        ) : (
          // 왜 못 바꾸는지 알려준다. 버튼만 사라지면 사용자는 이유를 모른다.
          <span className="dlv__muted">{t("delivery.cannot_change")}</span>
        )}
      </div>
      <div className="dlv__address-body">
        <strong>{address.recipientName}</strong>
        {address.recipientPhone && <span> {address.recipientPhone}</span>}
        <div>
          {address.postalCode && `[${address.postalCode}] `}
          {address.line1} {address.line2}
        </div>
        {address.memo && <div className="dlv__muted">{t("delivery.memo", { memo: address.memo })}</div>}
      </div>
    </div>
  );
}

/** 서버는 UTC 로 내려준다(B-21 과 같은 이유). 표시 시점에만 현지 시각으로 바꾼다. */
function formatEventTime(value) {
  if (!value) return "";
  const parsed = parseServerDate(value);
  if (!parsed) return "";
  const pad = (n) => String(n).padStart(2, "0");
  return `${pad(parsed.getMonth() + 1)}/${pad(parsed.getDate())} ${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`;
}
