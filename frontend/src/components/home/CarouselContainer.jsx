import "react-responsive-carousel/lib/styles/carousel.min.css"; // requires a loader
import { Carousel } from "react-responsive-carousel";

import slider1 from "assets/images/slider-1.jpg";
import slider2 from "assets/images/slider-2.jpg";
import slider3 from "assets/images/slider-3.jpg";
import slider4 from "assets/images/slider-4.jpg";
import slider5 from "assets/images/slider-5.jpg";
import { ChevronLeftIcon, ChevronRightIcon } from "@heroicons/react/24/outline";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 홈 배너.
 *
 * <p>배너는 catalog-service 의 콘텐츠 API 에서 온다. 예전에는 이미지 5장을 `import` 로
 * 번들에 박아 두고 있어 배너 하나 바꾸려면 재빌드·재배포였다.
 *
 * <p><b>번들 이미지는 지우지 않고 폴백으로 남긴다.</b> 첫 화면의 가장 큰 요소라, API 가
 * 죽었거나 아직 배너를 한 건도 등록하지 않았을 때 빈 칸이 되면 사이트가 고장 난 것처럼
 * 보인다.
 *
 * <p>🐛 <b>예전에는 이 컴포넌트가 마운트된 뒤 `useEffect` 로 배너를 받아 왔다.</b>
 * 그래서 화면이 이렇게 움직였다:
 *
 * <ol>
 *   <li>라우터 loader 가 끝나야 페이지가 그려지므로, 첫 페인트 시점에 <b>상품 섹션은
 *       이미 있다.</b> 상품 이미지가 곧바로 요청된다.</li>
 *   <li>캐러셀은 번들 폴백으로 그려진다(번들이라 빠르다).</li>
 *   <li>{@code getBanners()} 응답이 도착 → 이미지가 <b>원격 URL 로 통째로 교체</b>된다.
 *       아직 받지 않은 이미지라 높이가 0 으로 무너졌다가 다시 커지고, 그 아래 전체가
 *       위아래로 튄다.</li>
 * </ol>
 *
 * "상품 이미지가 배너보다 먼저 로드돼서 부자연스럽다" 는 것이 이 순서다.
 *
 * <p>두 가지를 고쳤다:
 *
 * <ul>
 *   <li>배너를 <b>홈 loader 에서</b> 받는다(pages/home/Home.jsx). 첫 페인트에 최종
 *       이미지가 들어가므로 교체 자체가 없어진다.</li>
 *   <li>컨테이너에 <b>종횡비를 고정</b>해 높이를 미리 예약한다. 이미지가 아직 안 왔어도
 *       자리는 그대로라 레이아웃이 튀지 않는다. 배너 원본이 3000×1200(5:2)이다.</li>
 * </ul>
 */

// 폴백. 서버에 배너가 하나도 없거나 API 가 실패했을 때만 쓴다.
const FALLBACK = [slider1, slider2, slider3, slider4, slider5].map((src, i) => ({
    id: `fallback-${i}`,
    // 대체 텍스트는 그릴 때 지금 언어로 만든다(아래 alt) — 모듈을 읽는 순간의 언어로 굳히지 않는다.
    title: null,
    imageUrl: src,
    linkUrl: null,
}));

/**
 * 배너의 종횡비. 폴백 이미지 5장이 모두 3000×1200 이다.
 *
 * <p>서버 배너의 실제 비율이 다르면 이미지가 잘리거나 여백이 생긴다. 그래도 높이를
 * 예약하지 않는 것보다 낫다 — 예약하지 않으면 <b>매 로드마다</b> 아래 콘텐츠가 튄다.
 * (배너 모델에 width/height 가 없어서 서버에서 알 방법이 없다.)
 */
const BANNER_ASPECT = "5 / 2";

const CarouselContainer = ({ banners }) => {
    const { t } = useTranslation();
    const arrowStyles = {
        position: "absolute",
        zIndex: 2,
        top: "calc(30% - 15px)",
        width: 50,
        height: 50,
        cursor: "pointer",
        filter: "drop-shadow(1px 3px 1px rgb(255 255 255 / 0.8))",
        color: "#404040",
    };

    // 빈 배열도 폴백 대상이다. "등록된 배너 없음" 과 "서버 장애" 를 화면에서 구분할
    // 이유가 없다 — 둘 다 첫 화면이 비면 안 된다.
    const slides = (Array.isArray(banners) && banners.length > 0 ? banners : FALLBACK)
        .map((banner, index) => {
            const image = (
                <img
                    src={banner.imageUrl}
                    alt={banner.title ?? t("home.banner.alt", { n: index + 1 })}
                    // 첫 장은 화면에 바로 보인다 — 늦게 받을 이유가 없다. 나머지는
                    // 넘겨야 보이므로 미룬다. 예전에는 다섯 장이 상품 이미지와 같은
                    // 우선순위로 한꺼번에 경쟁했다.
                    loading={index === 0 ? "eager" : "lazy"}
                    fetchpriority={index === 0 ? "high" : "low"}
                    // 컨테이너가 비율을 잡고 있으므로 이미지는 그 안을 채운다.
                    className="w-full h-full object-cover"
                />
            );

            // linkUrl 이 없으면 클릭되지 않는 배너다(공지성 이미지).
            return (
                <div key={banner.id} style={{ aspectRatio: BANNER_ASPECT }}>
                    {banner.linkUrl ? (
                        <a href={banner.linkUrl} rel="noopener noreferrer">
                            {image}
                        </a>
                    ) : (
                        image
                    )}
                </div>
            );
        });

    return (
        // 높이 예약. 이미지가 도착하기 전에도 이 자리가 유지되므로 아래 콘텐츠가
        // 튀지 않는다.
        <div style={{ aspectRatio: BANNER_ASPECT }} className="w-full">
            <Carousel
                renderArrowPrev={(onClickHandler, hasPrev, label) =>
                    hasPrev && (
                        <button
                            className=""
                            type="button"
                            onClick={onClickHandler}
                            title={label}
                            style={{ ...arrowStyles, left: 15 }}
                        >
                            <ChevronLeftIcon />
                        </button>
                    )
                }
                renderArrowNext={(onClickHandler, hasNext, label) =>
                    hasNext && (
                        <button
                            type="button"
                            onClick={onClickHandler}
                            title={label}
                            style={{ ...arrowStyles, right: 15 }}
                        >
                            <ChevronRightIcon />
                        </button>
                    )
                }
                // 화살표의 title·aria-label 은 라이브러리 기본값이 영어다("previous slide / item").
                labels={{
                    leftArrow: t("home.banner.prev"),
                    rightArrow: t("home.banner.next"),
                    item: t("home.banner.item"),
                }}
                showStatus={false}
                showArrows={true}
                infiniteLoop={true}
                emulateTouch={true}
                autoPlay={true}
                showIndicators={false}
                showThumbs={false}
            >
                {slides}
            </Carousel>
        </div>
    );
};

export default CarouselContainer;
