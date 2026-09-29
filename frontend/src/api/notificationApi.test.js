import api from "util/api";
import {
  deleteNotification,
  fetchUnreadCount,
  isBroadcast,
  markAllNotificationsRead,
  markNotificationRead,
  requestPhoneCode,
  confirmPhoneCode,
} from "api/notificationApi";

/**
 * 알림·인증 API.
 *
 * 핵심은 <b>429 를 오류로 취급하지 않는 것</b>이다. 인증번호 요청 제한에 걸린 것은 정상적인
 * 응답이고, 사용자에게는 "몇 초 뒤에 다시 시도하라"를 보여줘야 한다. 일반 오류로 처리하면
 * 재시도 시점을 알 수 없어 사용자가 계속 버튼만 누른다.
 */
describe("notificationApi", () => {
  afterEach(() => jest.restoreAllMocks());

  describe("requestPhoneCode", () => {
    it("returns the masked phone and expiry on success", async () => {
      jest.spyOn(api, "post").mockResolvedValue({
        data: { phone: "010-****-5678", expiresInMinutes: 3 },
      });

      await expect(requestPhoneCode({ phone: "01012345678", region: "KR" })).resolves.toEqual({
        ok: true,
        phone: "010-****-5678",
        expiresInMinutes: 3,
      });
    });

    it("treats 429 as a rate limit, carrying the retry delay", async () => {
      jest.spyOn(api, "post").mockRejectedValue({
        response: { status: 429, data: { message: "너무 많이 요청했습니다", retryAfterSeconds: 42 } },
      });

      const result = await requestPhoneCode({ phone: "01012345678", region: "KR" });

      expect(result).toEqual({
        ok: false,
        tooMany: true,
        message: "너무 많이 요청했습니다",
        retryAfterSeconds: 42,
      });
    });

    it("does not mark an ordinary failure as a rate limit", async () => {
      jest.spyOn(api, "post").mockRejectedValue({
        response: { status: 400, data: { message: "번호 형식이 올바르지 않습니다" } },
      });

      const result = await requestPhoneCode({ phone: "abc", region: "KR" });

      expect(result.ok).toBe(false);
      expect(result.tooMany).toBeUndefined();
      expect(result.message).toBe("번호 형식이 올바르지 않습니다");
    });

    it("still returns a usable message when the body is missing", async () => {
      jest.spyOn(api, "post").mockRejectedValue({ response: { status: 500 } });

      await expect(requestPhoneCode({ phone: "1", region: "KR" }))
        .resolves.toMatchObject({ ok: false, message: "인증번호를 보내지 못했습니다" });
    });
  });

  describe("confirmPhoneCode", () => {
    it("passes attemptsLeft through so the UI can warn the user", async () => {
      jest.spyOn(api, "post").mockRejectedValue({
        response: { status: 400, data: { message: "코드가 틀렸습니다", attemptsLeft: 2 } },
      });

      const result = await confirmPhoneCode({ phone: "1", region: "KR", code: "000000" });

      expect(result).toMatchObject({ ok: false, attemptsLeft: 2 });
    });

    it("reports verification success", async () => {
      jest.spyOn(api, "post").mockResolvedValue({ data: { verified: true, preference: {} } });

      await expect(confirmPhoneCode({ phone: "1", region: "KR", code: "123456" }))
        .resolves.toMatchObject({ ok: true, verified: true });
    });
  });

  describe("fetchUnreadCount", () => {
    it("unwraps the count field", async () => {
      jest.spyOn(api, "get").mockResolvedValue({ data: { count: 3 } });

      await expect(fetchUnreadCount()).resolves.toBe(3);
    });
  });

  describe("markAllNotificationsRead", () => {
    it("returns how many were updated", async () => {
      jest.spyOn(api, "patch").mockResolvedValue({ data: { updated: 5 } });

      await expect(markAllNotificationsRead()).resolves.toBe(5);
    });
  });

  /**
   * 목록은 개인 알림과 전체 알림(공지·관리자 안내)을 합쳐서 내려오는데, 읽음·삭제
   * 엔드포인트가 다르다 — 전체 알림은 모두가 공유하는 행이라 개인 알림처럼 지울 수 없고
   * 개인별 표시만 남긴다.
   *
   * id 만으로는 어느 쪽인지 알 수 없다(두 테이블이 각자 TSID 를 쓴다). 잘못된 경로로
   * 보내면 404 가 나거나 — 더 나쁘게 — 우연히 같은 id 의 남의 개인 알림을 건드린다.
   */
  describe("scope routing", () => {
    const personal = { id: 7, scope: "PERSONAL" };
    const broadcast = { id: 7, scope: "BROADCAST" };

    it("recognises a broadcast by its scope, not its id", () => {
      expect(isBroadcast(broadcast)).toBe(true);
      expect(isBroadcast(personal)).toBe(false);
    });

    it("treats a missing scope as personal — old responses must not hit the broadcast path", () => {
      expect(isBroadcast({ id: 7 })).toBe(false);
      expect(isBroadcast(undefined)).toBe(false);
    });

    it("marks a personal notification through the personal path", async () => {
      const patch = jest.spyOn(api, "patch").mockResolvedValue({});

      await markNotificationRead(personal);

      expect(patch).toHaveBeenCalledWith("/notification-service/api/notifications/7/read");
    });

    it("marks a broadcast through the broadcast path", async () => {
      const patch = jest.spyOn(api, "patch").mockResolvedValue({});

      await markNotificationRead(broadcast);

      expect(patch).toHaveBeenCalledWith(
        "/notification-service/api/notifications/broadcasts/7/read"
      );
    });

    it("deletes a personal notification through the personal path", async () => {
      const del = jest.spyOn(api, "delete").mockResolvedValue({});

      await deleteNotification(personal);

      expect(del).toHaveBeenCalledWith("/notification-service/api/notifications/7");
    });

    it("dismisses a broadcast through the broadcast path", async () => {
      const del = jest.spyOn(api, "delete").mockResolvedValue({});

      await deleteNotification(broadcast);

      expect(del).toHaveBeenCalledWith("/notification-service/api/notifications/broadcasts/7");
    });
  });
});
