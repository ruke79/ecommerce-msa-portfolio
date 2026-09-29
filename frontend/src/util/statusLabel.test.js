import { batchStatusLabel, deliveryStatusLabel, orderStatusLabel } from "util/statusLabel";
import ko from "i18n/messages/ko";
import ja from "i18n/messages/ja";

const tOf = (dict) => (key) => dict[key] ?? key;

describe("상태 코드 이름표 (docs/60 §2)", () => {
    it("🔴 코드 대신 이름 — 한국어·일본어", () => {
        expect(orderStatusLabel(tOf(ko), "PAID")).toBe("결제 완료");
        expect(orderStatusLabel(tOf(ja), "PAID")).toBe("決済完了");
        expect(deliveryStatusLabel(tOf(ja), "IN_TRANSIT")).toBe("配送中");
        expect(batchStatusLabel(tOf(ko), "FAILED")).toBe("실패");
    });

    it("소문자·공백도 알아본다, 모르는 코드는 그대로, 비어 있으면 빈 글자", () => {
        expect(orderStatusLabel(tOf(ko), " paid ")).toBe("결제 완료");
        expect(orderStatusLabel(tOf(ko), "NEW_STATUS")).toBe("NEW_STATUS");
        expect(orderStatusLabel(tOf(ko), null)).toBe("");
    });
});
