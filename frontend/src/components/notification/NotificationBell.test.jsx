import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import NotificationBell from "components/notification/NotificationBell";
import { withI18n } from "i18n/testing";
import {
  fetchNotifications,
  fetchUnreadCount,
  isBroadcast,
  markNotificationRead,
  markAllNotificationsRead,
  deleteNotification,
} from "api/notificationApi";

jest.mock("api/notificationApi");

// jest.mock 은 파일 최상단으로 호이스팅되므로 팩토리가 바깥 변수를 참조할 수 없다.
// `mock` 접두사가 붙은 이름만 예외로 허용된다.
const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({ useNavigate: () => mockNavigate }));

const items = [
  { id: 1, type: "ORDER_PLACED", title: "주문 완료", content: "주문이 접수됐습니다", read: false,
    createdAt: "2026-07-28T10:00:00", smsStatus: "SENT", scope: "PERSONAL" },
  { id: 2, type: "SHIPPING_UPDATED", title: "배송 시작", content: "발송됐습니다", read: true,
    createdAt: "2026-07-28T09:00:00", scope: "PERSONAL" },
];

/**
 * 전체 알림(공지·관리자 안내). 목록은 개인 알림과 **합쳐서** 내려오지만 읽음·삭제
 * 엔드포인트가 다르다 — 그 갈림을 결정하는 것이 scope 다.
 */
const broadcast = {
  id: 99, type: "NOTICE_PUBLISHED", title: "시스템 점검 안내", content: "02시부터 점검합니다",
  read: false, createdAt: "2026-07-28T11:00:00", smsStatus: "NOT_APPLICABLE", scope: "BROADCAST",
};

describe("NotificationBell", () => {
  beforeEach(() => {
    fetchUnreadCount.mockResolvedValue(2);
    fetchNotifications.mockResolvedValue(items);
    // 자동 목은 undefined 를 돌려준다 — 실제 구현과 같은 판정을 쓰게 해 둔다.
    isBroadcast.mockImplementation((item) => item?.scope === "BROADCAST");
  });

  afterEach(() => jest.clearAllMocks());

  it("renders nothing for anonymous visitors", () => {
    const { container } = render(withI18n(<NotificationBell isAuthenticated={false} />));
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the unread badge", async () => {
    render(withI18n(<NotificationBell isAuthenticated />));

    expect(await screen.findByText("2")).toBeInTheDocument();
  });

  it("caps the badge at 99+", async () => {
    fetchUnreadCount.mockResolvedValue(150);

    render(withI18n(<NotificationBell isAuthenticated />));

    expect(await screen.findByText("99+")).toBeInTheDocument();
  });

  it("announces the count to screen readers, not just visually", async () => {
    render(withI18n(<NotificationBell isAuthenticated />));

    await waitFor(() =>
      expect(screen.getByRole("button", { name: /알림 2건/ })).toBeInTheDocument()
    );
  });

  it("does not fetch the list until the dropdown is opened", async () => {
    // 목록까지 주기적으로 받아오면 알림이 쌓일수록 헤더 하나 때문에 트래픽이 는다.
    render(withI18n(<NotificationBell isAuthenticated />));
    await screen.findByText("2");

    expect(fetchNotifications).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button"));

    await waitFor(() => expect(fetchNotifications).toHaveBeenCalled());
  });

  it("lists notifications once opened", async () => {
    render(withI18n(<NotificationBell isAuthenticated />));
    await userEvent.click(await screen.findByRole("button"));

    expect(await screen.findByText("주문 완료")).toBeInTheDocument();
    expect(screen.getByText("배송 시작")).toBeInTheDocument();
  });

  it("shows whether an SMS went out, so the user can self-diagnose", async () => {
    render(withI18n(<NotificationBell isAuthenticated />));
    await userEvent.click(await screen.findByRole("button"));

    expect(await screen.findByText("SMS 발송됨")).toBeInTheDocument();
  });

  it("marks an item read optimistically and decrements the badge", async () => {
    markNotificationRead.mockResolvedValue();

    render(withI18n(<NotificationBell isAuthenticated />));
    await userEvent.click(await screen.findByRole("button"));
    await userEvent.click(await screen.findByText("주문 완료"));

    // id 가 아니라 항목 자체를 넘긴다 — 읽음 경로가 scope 로 갈린다.
    await waitFor(() =>
      expect(markNotificationRead).toHaveBeenCalledWith(expect.objectContaining({ id: 1 }))
    );
  });

  it("removes an item without also triggering navigation", async () => {
    deleteNotification.mockResolvedValue();

    render(withI18n(<NotificationBell isAuthenticated />));
    await userEvent.click(await screen.findByRole("button"));

    const removeButtons = await screen.findAllByRole("button", { name: "알림 삭제" });
    await userEvent.click(removeButtons[0]);

    await waitFor(() =>
      expect(deleteNotification).toHaveBeenCalledWith(expect.objectContaining({ id: 1 }))
    );
    // stopPropagation 이 없으면 삭제가 항목 클릭(이동)으로 번진다.
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("marks everything read", async () => {
    markAllNotificationsRead.mockResolvedValue(2);

    render(withI18n(<NotificationBell isAuthenticated />));
    await userEvent.click(await screen.findByRole("button"));
    await userEvent.click(await screen.findByRole("button", { name: "모두 읽음" }));

    await waitFor(() => expect(markAllNotificationsRead).toHaveBeenCalled());
  });

  it("says so when there is nothing to show", async () => {
    fetchUnreadCount.mockResolvedValue(0);
    fetchNotifications.mockResolvedValue([]);

    render(withI18n(<NotificationBell isAuthenticated />));
    await userEvent.click(await screen.findByRole("button"));

    expect(await screen.findByText("새로운 알림이 없습니다")).toBeInTheDocument();
  });

  it("keeps the header usable when the count request fails", async () => {
    // 배지 하나 때문에 헤더 전체가 죽으면 안 된다.
    fetchUnreadCount.mockRejectedValue(new Error("500"));

    render(withI18n(<NotificationBell isAuthenticated />));

    await waitFor(() => expect(screen.getByRole("button")).toBeInTheDocument());
  });

  /**
   * 전체 알림(공지·관리자 안내).
   *
   * 목록은 개인 알림과 합쳐서 내려오지만 읽음·삭제 엔드포인트가 다르다. 종은 각 항목의
   * scope 를 그대로 API 층까지 들고 가야 한다 — id 만 넘기면 전체 알림이 개인 알림 경로로
   * 가서 404 가 되거나, 우연히 같은 id 의 남의 개인 알림을 건드린다.
   */
  describe("전체 알림", () => {
    beforeEach(() => {
      fetchNotifications.mockResolvedValue([...items, broadcast]);
    });

    it("lists broadcasts alongside personal notifications", async () => {
      render(withI18n(<NotificationBell isAuthenticated />));
      await userEvent.click(await screen.findByRole("button"));

      expect(await screen.findByText("시스템 점검 안내")).toBeInTheDocument();
      expect(screen.getByText("주문 완료")).toBeInTheDocument();
    });

    it("labels a broadcast so the user knows it was not sent only to them", async () => {
      render(withI18n(<NotificationBell isAuthenticated />));
      await userEvent.click(await screen.findByRole("button"));

      expect(await screen.findByText("공지")).toBeInTheDocument();
    });

    it("passes the whole item to the read call, carrying its scope", async () => {
      markNotificationRead.mockResolvedValue();

      render(withI18n(<NotificationBell isAuthenticated />));
      await userEvent.click(await screen.findByRole("button"));
      await userEvent.click(await screen.findByText("시스템 점검 안내"));

      await waitFor(() =>
        expect(markNotificationRead).toHaveBeenCalledWith(
          expect.objectContaining({ id: 99, scope: "BROADCAST" })
        )
      );
    });

    it("passes the whole item to the delete call too", async () => {
      deleteNotification.mockResolvedValue();

      render(withI18n(<NotificationBell isAuthenticated />));
      await userEvent.click(await screen.findByRole("button"));

      const removeButtons = await screen.findAllByRole("button", { name: "알림 삭제" });
      await userEvent.click(removeButtons[2]);

      await waitFor(() =>
        expect(deleteNotification).toHaveBeenCalledWith(
          expect.objectContaining({ id: 99, scope: "BROADCAST" })
        )
      );
    });

    /**
     * 두 테이블의 id 는 서로 독립이다. 부딪히면 React 가 두 항목을 같은 것으로 보고
     * 하나만 그린다 — 개인 알림이 공지에 가려 사라진다.
     */
    it("keeps both rows when a personal id collides with a broadcast id", async () => {
      fetchNotifications.mockResolvedValue([
        { ...items[0], id: 42, scope: "PERSONAL", title: "주문 완료" },
        { ...broadcast, id: 42, title: "시스템 점검 안내" },
      ]);

      render(withI18n(<NotificationBell isAuthenticated />));
      await userEvent.click(await screen.findByRole("button"));

      expect(await screen.findByText("주문 완료")).toBeInTheDocument();
      expect(screen.getByText("시스템 점검 안내")).toBeInTheDocument();
    });

    /** 같은 id 라도 읽음 처리가 서로에게 번지면 안 된다. */
    it("does not mark the colliding personal item read when the broadcast is clicked", async () => {
      markNotificationRead.mockResolvedValue();
      fetchNotifications.mockResolvedValue([
        { ...items[0], id: 42, scope: "PERSONAL", title: "주문 완료", read: false },
        { ...broadcast, id: 42, title: "시스템 점검 안내", read: false },
      ]);

      render(withI18n(<NotificationBell isAuthenticated />));
      await userEvent.click(await screen.findByRole("button"));
      await userEvent.click(await screen.findByText("시스템 점검 안내"));

      await waitFor(() => expect(markNotificationRead).toHaveBeenCalledTimes(1));
      // 개인 알림은 여전히 안 읽음이라 다시 누르면 한 번 더 호출된다.
      await userEvent.click(screen.getByText("주문 완료"));
      await waitFor(() => expect(markNotificationRead).toHaveBeenCalledTimes(2));
    });
  });

  it("일본어 화면에서는 이름표·빈 목록 안내가 일본어다", async () => {
    fetchNotifications.mockResolvedValue([]);

    render(withI18n(<NotificationBell isAuthenticated />, "ja"));

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "通知 2件（未読）" })).toBeInTheDocument()
    );
    await userEvent.click(screen.getByRole("button"));

    expect(await screen.findByText("新しい通知はありません")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "すべて既読にする" })).toBeInTheDocument();
  });
});
