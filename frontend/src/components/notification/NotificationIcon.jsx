import {
  IoBagCheckOutline,
  IoCardOutline,
  IoAlertCircleOutline,
  IoCubeOutline,
  IoChatbubbleEllipsesOutline,
  IoPersonAddOutline,
  IoTicketOutline,
  IoShieldCheckmarkOutline,
  IoNotificationsOutline,
  IoMegaphoneOutline,
  IoInformationCircleOutline,
  IoWalletOutline,
  IoCheckmarkDoneCircleOutline,
  IoRefreshCircleOutline,
} from "react-icons/io5";

/**
 * 알림 종류별 아이콘.
 *
 * <p>비트맵 이미지 대신 아이콘 폰트(react-icons, 이미 의존성에 있음)를 쓴다:
 *  - 해상도에 관계없이 선명하다(레티나용 @2x 파일을 따로 관리할 필요가 없다)
 *  - 색을 CSS 로 바꿀 수 있어 다크모드·읽음 상태에 그대로 대응한다
 *  - 추가 네트워크 요청이 없다 — 알림 목록은 스크롤할 때마다 여러 개가 한꺼번에 그려진다
 *
 * 색은 의미를 담는다. 결제 실패·보안은 빨강 계열로 눈에 먼저 들어오게 한다.
 */
const ICONS = {
  ORDER_PLACED:      { Icon: IoBagCheckOutline,           tone: "brand"  },
  PAYMENT_COMPLETED: { Icon: IoCardOutline,               tone: "ok"     },
  PAYMENT_FAILED:    { Icon: IoAlertCircleOutline,        tone: "danger" },
  PAYMENT_REFUNDED:  { Icon: IoWalletOutline,             tone: "info"   },
  SHIPPING_UPDATED:  { Icon: IoCubeOutline,               tone: "brand"  },
  // 구매 확정(docs/25 §12) — 주문의 마무리. 대개 자동이라 "끝났다" 가 한눈에 보여야 한다.
  ORDER_COMPLETED:   { Icon: IoCheckmarkDoneCircleOutline, tone: "ok"     },
  // 재입고(docs/53) — 손님이 신청한 소식이다. "다시 살 수 있다" 가 먼저 보여야 한다.
  RESTOCK_AVAILABLE: { Icon: IoRefreshCircleOutline,       tone: "accent" },
  CHAT_MESSAGE:      { Icon: IoChatbubbleEllipsesOutline, tone: "info"   },
  FRIEND_ONLINE:     { Icon: IoPersonAddOutline,          tone: "ok"     },
  COUPON_ISSUED:     { Icon: IoTicketOutline,             tone: "accent" },
  SECURITY_ALERT:    { Icon: IoShieldCheckmarkOutline,    tone: "danger" },
  // 전체 알림. 나에게만 온 것이 아니라는 점이 한눈에 드러나야 한다.
  NOTICE_PUBLISHED:  { Icon: IoInformationCircleOutline,  tone: "info"   },
  ANNOUNCEMENT:      { Icon: IoMegaphoneOutline,          tone: "accent" },
};

export default function NotificationIcon({ type, size = 20 }) {
  const { Icon, tone } = ICONS[type] ?? { Icon: IoNotificationsOutline, tone: "muted" };

  return (
    <span className={`notif-icon notif-icon--${tone}`} aria-hidden="true">
      <Icon size={size} />
    </span>
  );
}
