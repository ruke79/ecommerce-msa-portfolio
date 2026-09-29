import { useTranslation } from "i18n/I18nProvider";

/**
 * 내용이 들어올 자리를 미리 그려 두는 회색 블록.
 *
 * <p>이 앱에는 스켈레톤이 <b>한 곳도 없었다</b>(docs/43 D5). 목록을 기다리는 동안 화면이
 * 비거나(빈 공간), 전체화면 스피너가 덮거나 둘 중 하나였다. 둘 다 <b>레이아웃이 튄다</b> —
 * 데이터가 도착하는 순간 높이가 갑자기 늘어나면서 읽던 위치가 밀린다.
 *
 * <p>스켈레톤은 실제 내용과 <b>같은 크기</b>를 차지한다. 그래서 도착해도 아무것도
 * 움직이지 않는다. 기다림을 감추는 장치가 아니라 <b>자리를 잡아 두는</b> 장치다.
 *
 * @param className 크기와 모양(높이·너비·radius)은 부르는 쪽이 정한다 — 그래야 실제
 *                  내용과 같은 크기로 맞출 수 있다.
 */
export const Skeleton = ({ className = "" }) => (
    <div
        aria-hidden="true"
        className={`animate-[skeleton-shimmer_1.4s_ease-in-out_infinite] rounded bg-slate-200 ${className}`}
    />
);

/**
 * 상품 카드 한 장의 자리.
 *
 * <p>비율은 실제 카드(이미지 + 이름 두 줄 + 가격)에 맞췄다. 숫자가 어긋나면 도착하는
 * 순간 격자가 튀므로, 카드 레이아웃을 바꿀 때 여기도 같이 봐야 한다.
 */
export const ProductCardSkeleton = () => (
    <div className="p-2">
        <Skeleton className="aspect-square w-full" />
        <Skeleton className="mt-3 h-4 w-11/12" />
        <Skeleton className="mt-2 h-4 w-2/3" />
        <Skeleton className="mt-3 h-5 w-1/3" />
    </div>
);

/**
 * 상품 격자 한 판.
 *
 * <p>목록 전체를 그리지 않는다. 한 화면에 들어오는 만큼이면 "곧 목록이 온다"는 뜻은
 * 충분히 전달되고, 그 이상은 DOM 만 늘린다.
 */
export const ProductGridSkeleton = ({ count = 8, label }) => {
    const { t } = useTranslation();
    return (
        <div role="status" aria-live="polite" className="grid grid-cols-2 gap-2 md:grid-cols-4">
            {Array.from({ length: count }, (_, i) => (
                <ProductCardSkeleton key={i} />
            ))}
            <span className="sr-only">{label ?? t("skeleton.products_loading")}</span>
        </div>
    );
};

export default Skeleton;
