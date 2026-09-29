/**
 * Swiper 대역.
 *
 * <p>swiper 11 은 package.json 의 `exports` 맵으로만 하위 경로를 노출한다
 * (`./react`, `./modules` 는 디스크에 그런 이름의 디렉터리가 없다). jest 27 의 resolver 는
 * `exports` 를 읽지 않으므로 `Cannot find module 'swiper/modules'` 로 죽는다 — 상품 카드를
 * 거치는 테스트가 전부 막힌다.
 *
 * <p>캐러셀의 동작은 어느 테스트의 검증 대상도 아니다. 슬라이드를 그대로 그리는 대역으로
 * 바꾼다. 실제 Swiper 를 쓰고 싶어지면 그때 resolver 를 손보면 된다.
 */
const React = require("react");

// forwardRef 인 이유: ProductSwiper 가 `ref={swiperRef}` 를 넘긴다. 함수 컴포넌트에
// ref 를 주면 React 가 "Function components cannot be given refs" 를 찍는다. div 에
// 붙여 두면 `swiperRef.current?.swiper?.…` 는 옵셔널 체이닝으로 조용히 통과한다.
const Swiper = React.forwardRef(({ children, ...rest }, ref) =>
  React.createElement(
    "div",
    { "data-testid": "swiper", ref, ...stripSwiperProps(rest) },
    children
  )
);
Swiper.displayName = "Swiper";

const SwiperSlide = ({ children, ...rest }) =>
  React.createElement("div", { "data-testid": "swiper-slide", ...stripSwiperProps(rest) }, children);

// Swiper 전용 props 를 그대로 div 에 넘기면 React 가 "unknown prop" 경고를 쏟아낸다.
// 목록이 빠지면 조용히 실패하지 않고 **경고로 테스트 출력을 덮어** 정작 봐야 할 것을
// 가린다. 실제로 쓰는 prop 을 늘릴 때는 여기에도 넣는다.
function stripSwiperProps({
  modules,
  slidesPerView,
  slidesPerGroup,
  spaceBetween,
  centeredSlides,
  autoplay,
  navigation,
  pagination,
  breakpoints,
  speed,
  loop,
  ...rest
}) {
  return rest;
}

// swiper/modules 의 플러그인들. 존재하기만 하면 된다.
const Autoplay = "Autoplay";
const Navigation = "Navigation";
const Pagination = "Pagination";
const Scrollbar = "Scrollbar";
const A11y = "A11y";
const Thumbs = "Thumbs";
const FreeMode = "FreeMode";
const EffectFade = "EffectFade";

module.exports = {
  __esModule: true,
  Swiper,
  SwiperSlide,
  Autoplay,
  Navigation,
  Pagination,
  Scrollbar,
  A11y,
  Thumbs,
  FreeMode,
  EffectFade,
  default: Swiper,
};
