import { render, screen } from "@testing-library/react";

import Skeleton, { ProductCardSkeleton, ProductGridSkeleton } from "components/loader/Skeleton";
import { withI18n } from "i18n/testing";

/**
 * 스켈레톤은 <b>자리를 잡아 두는</b> 장치다 (docs/43 D5).
 *
 * <p>이 앱에는 스켈레톤이 한 곳도 없었다. 목록을 기다리는 동안 화면이 비거나 전체화면
 * 스피너가 덮거나 둘 중 하나였고, 둘 다 데이터가 도착하는 순간 <b>레이아웃이 튄다</b> —
 * 높이가 갑자기 늘어나면서 읽던 위치가 밀린다.
 */
describe("스켈레톤", () => {
  it("크기는 부르는 쪽이 정한다 — 실제 내용과 같은 크기여야 한다", () => {
    const { container } = render(<Skeleton className="h-10 w-1/2" />);

    expect(container.firstChild.className).toContain("h-10");
    expect(container.firstChild.className).toContain("w-1/2");
  });

  /**
   * 스크린리더에게 회색 블록은 아무 뜻이 없다. 대신 목록 전체가 한 번
   * "상품을 불러오는 중" 이라고 말한다.
   */
  it("블록 하나하나는 읽히지 않는다", () => {
    const { container } = render(<Skeleton />);

    expect(container.firstChild).toHaveAttribute("aria-hidden", "true");
  });

  it("상품 카드 자리는 이미지·이름·가격 몫을 모두 잡는다", () => {
    const { container } = render(<ProductCardSkeleton />);

    // 이미지 1 + 이름 2줄 + 가격 1 = 4
    expect(container.querySelectorAll("[aria-hidden='true']")).toHaveLength(4);
  });

  it("격자는 기다림을 한 번만 알린다", () => {
    render(withI18n(<ProductGridSkeleton />));

    expect(screen.getByRole("status")).toHaveAttribute("aria-live", "polite");
    expect(screen.getByText("상품을 불러오는 중")).toBeInTheDocument();
  });

  /** 목록 전체를 그리지 않는다 — 한 화면이면 뜻은 충분히 전달되고 그 이상은 DOM 만 늘린다. */
  it("한 화면 분량만 그린다", () => {
    const { container } = render(withI18n(<ProductGridSkeleton count={4} />));

    expect(container.querySelectorAll(".aspect-square")).toHaveLength(4);
  });
});
