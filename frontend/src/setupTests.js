// toBeInTheDocument, toBeDisabled 같은 DOM 전용 매처를 등록한다.
import "@testing-library/jest-dom";

// 테스트가 조용히 통과하지 않도록, 처리되지 않은 프라미스 거부를 드러낸다.
process.on("unhandledRejection", (reason) => {
  throw reason;
});

/**
 * @testing-library/react 13.4 는 react-dom/test-utils 의 act 를 쓰는데, React 18.3 이 그걸
 * deprecated 로 경고한다. 우리 코드의 문제가 아니고 렌더마다 스택 트레이스가 찍혀서, 진짜
 * 오류가 그 사이에 묻힌다.
 *
 * ⚠️ **이 한 가지 메시지만** 막는다. console.error 를 통째로 죽이면 PropTypes 경고나
 * "Cannot update state on unmounted component" 같은 진짜 신호까지 사라진다 — 그건 테스트를
 * 조용하게 만드는 대신 쓸모없게 만드는 짓이다.
 */
const originalError = console.error;
const originalWarn = console.warn;

const SILENCED = [
  // RTL 13.4 + React 18.3 조합에서 렌더마다 나온다. 우리 코드와 무관하다.
  "ReactDOMTestUtils.act` is deprecated",
  // react-router v6 이 v7 변경 예고로 띄우는 안내. 라우팅 동작과 무관하고 렌더마다 반복된다.
  "React Router Future Flag Warning",
  "v7_startTransition",
  "v7_relativeSplatPath",
];

const isSilenced = (args) =>
  typeof args[0] === "string" && SILENCED.some((needle) => args[0].includes(needle));

console.error = (...args) => {
  if (isSilenced(args)) return;
  originalError(...args);
};

console.warn = (...args) => {
  if (isSilenced(args)) return;
  originalWarn(...args);
};
