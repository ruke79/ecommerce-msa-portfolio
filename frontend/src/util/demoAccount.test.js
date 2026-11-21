import { isDemoEmail } from "util/demoAccount";

// 데모 계정 이메일 판별 — 관리자 '데모' 표시(docs/100). [JA] デモアカウントのメール判別。
describe("isDemoEmail", () => {
  it("🔴 demo<N>@demo.invalid 만 데모", () => {
    expect(isDemoEmail("demo1@demo.invalid")).toBe(true);
    expect(isDemoEmail("Demo2@DEMO.invalid")).toBe(true);
    expect(isDemoEmail("a@example.com")).toBe(false);
    expect(isDemoEmail("a@notdemo.invalid")).toBe(false);
    expect(isDemoEmail(undefined)).toBe(false);
  });
});
