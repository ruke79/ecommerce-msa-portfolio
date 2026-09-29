import { targetDimensions, MAX_DIMENSION } from "util/imageResize";

// 리사이즈의 유일한 산수. canvas 자체는 jsdom 에 없으므로(실패 시 원본 폴백이
// 그 경우를 덮는다) 여기서는 크기 결정만 고정한다.
describe("targetDimensions", () => {
  it("긴 변이 한도 이하면 건드리지 않는다 — 확대는 화질만 상한다", () => {
    expect(targetDimensions(800, 600)).toEqual({ width: 800, height: 600, resized: false });
    expect(targetDimensions(MAX_DIMENSION, 100).resized).toBe(false);
  });

  it("가로가 긴 사진은 가로를 1920 으로, 비율 유지", () => {
    const r = targetDimensions(4000, 3000);
    expect(r).toEqual({ width: 1920, height: 1440, resized: true });
  });

  it("세로가 긴 사진(폰 기본)은 세로를 1920 으로", () => {
    const r = targetDimensions(3000, 4000);
    expect(r).toEqual({ width: 1440, height: 1920, resized: true });
  });

  it("비율이 극단이어도 반올림으로 0 이 되지 않는다", () => {
    const r = targetDimensions(10000, 10);
    expect(r.width).toBe(1920);
    expect(r.height).toBeGreaterThan(0);
  });
});
