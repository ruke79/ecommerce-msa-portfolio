import { classifyPortOneFailure, portOneFailureText } from "util/portoneError";
import ko from "i18n/messages/ko";
import ja from "i18n/messages/ja";

/** 사전 한 벌로 t 를 흉내 낸다 — {{code}} 만 채운다. */
const tFor = (dict) => (key, vars = {}) =>
  (dict[key] ?? key).replace(/\{\{(\w+)\}\}/g, (_, k) => vars[k] ?? "");

/**
 * 포트원 결제창의 실패 문구 (docs/60 §12).
 *
 * <p>PG 가 쓴 문구(대개 한국어)를 그대로 띄우면 일본어 화면에 한국어가 나온다. 알아보고 사전 문구로.
 */
describe("util/portoneError", () => {
  it.each([
    [{ error_msg: "[결제포기] 사용자가 결제를 취소하셨습니다" }, "canceled"],
    [{ code: "FAILURE_TYPE_PG", message: "[PAY_PROCESS_CANCELED] 사용자가 결제를 취소하였습니다." }, "canceled"],
    [{ error_msg: "결제 가능 시간이 초과되었습니다" }, "timeout"],
    [{ error_msg: "잔액이 부족합니다" }, "insufficient"],
    [{ error_msg: "[카드사] 한도초과" }, "insufficient"],
    [{ error_msg: "유효기간 오류" }, "card"],
    [{ error_msg: "카드 비밀번호 오류 횟수 초과" }, "card"],
    [{ error_msg: "본인인증에 실패했습니다" }, "auth"],
  ])("%j → %s", (response, kind) => {
    expect(classifyPortOneFailure(response)?.kind).toBe(kind);
  });

  it("🔴 일본어 화면에는 PG 의 한국어가 나오지 않는다 — 알아본 것은 사전 문구로", () => {
    const text = portOneFailureText(tFor(ja), "ja", { error_msg: "[결제포기] 사용자가 결제를 취소하셨습니다" });
    expect(text).toBe(ja["payment.portone.error.canceled"]);
    expect(text).not.toMatch(/[가-힣]/);
  });

  it("🔴 알아보지 못한 문구 — 일본어면 일반 안내 + 오류 코드, 원문은 빼고", () => {
    const warn = jest.spyOn(console, "warn").mockImplementation(() => {});
    const text = portOneFailureText(tFor(ja), "ja", { error_code: "F400", error_msg: "가맹점 설정 오류입니다" });
    expect(text).toContain("F400");
    expect(text).not.toMatch(/[가-힣]/);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it("알아보지 못한 문구 — 한국어 화면이면 PG 원문 그대로(원인을 가장 잘 말한다)", () => {
    expect(portOneFailureText(tFor(ko), "ko", { error_msg: "가맹점 설정 오류입니다" })).toBe("가맹점 설정 오류입니다");
  });

  it("문구도 코드도 없으면 '결제가 완료되지 않았습니다'", () => {
    expect(portOneFailureText(tFor(ja), "ja", undefined)).toBe(ja["payment.error.not_completed"]);
  });

  it("모든 안내 문구가 두 언어 사전에 있다", () => {
    for (const key of ["canceled", "timeout", "insufficient", "card", "auth", "generic_code"]) {
      expect(ko[`payment.portone.error.${key}`]).toBeTruthy();
      expect(ja[`payment.portone.error.${key}`]).toMatch(/[ぁ-んァ-ヶ一-龯]/);
    }
  });
});
