/**
 * 프론트엔드 테스트 설정.
 *
 * <p>jest 27 · jest-environment-jsdom · babel-jest · identity-obj-proxy 는 package.json 에 버전을 고정해 두었다 —
 * 예전에는 react-scripts 가 간접으로 끌어왔는데, 쓰지 않는 CRA 가 취약점 수십 건을 달고 있어 걷어냈다.
 * 네트워크가 막힌 환경에서도 바로 돌아야 하기 때문이다.
 */
module.exports = {
  testEnvironment: "jsdom",

  setupFilesAfterEnv: ["<rootDir>/src/setupTests.js"],

  // 시간대를 Asia/Seoul 로 고정한다 — UTC 에서는 현지↔UTC 변환 누락이 드러나지 않는다(jest.globalSetup.js).
  globalSetup: "<rootDir>/jest.globalSetup.js",

  // webpack 의 alias 와 같은 해석 규칙을 준다. webpack alias 는 전부 src 하위를 가리키므로
  // moduleDirectories 에 src 를 넣는 것만으로 api/, components/, util/ … 이 모두 해석된다.
  // (jsconfig.json 의 baseUrl 은 에디터 전용이라 jest 에는 아무 영향이 없다.)
  moduleDirectories: ["node_modules", "src"],

  moduleNameMapper: {
    // CSS 는 클래스명을 그대로 돌려주는 프록시로 바꾼다. 실제 스타일은 테스트 대상이 아니다.
    "\\.(css|less|scss|sass)$": "identity-obj-proxy",
    // 이미지·폰트는 파일 이름만 있으면 충분하다.
    "\\.(png|jpe?g|gif|svg|webp|woff2?|ttf|eot)$": "<rootDir>/src/__mocks__/fileMock.js",
    // axios 1.x 의 진입점은 ESM 이다. jest 27 은 node_modules 를 변환하지 않으므로
    // "Cannot use import statement outside a module" 로 죽는다. 같은 버전이 함께
    // 배포하는 CJS 빌드를 가리킨다 — 동작은 같고 변환이 필요 없다.
    "^axios$": "axios/dist/node/axios.cjs",
    // swiper 11 은 하위 경로를 package.json 의 `exports` 로만 노출한다. jest 27 의
    // resolver 는 `exports` 를 읽지 않아 `swiper/react` · `swiper/modules` 를 찾지 못하고,
    // 그러면 ProductCard 를 거치는 테스트가 전부 "Cannot find module" 로 죽는다.
    // 캐러셀 동작은 검증 대상이 아니므로 대역으로 바꾼다(css 는 위 규칙이 이미 처리한다).
    "^swiper/(react|modules)$": "<rootDir>/src/__mocks__/swiperMock.js",
    // `swiper/css` 는 확장자가 없어 위의 `\.css$` 규칙에 걸리지 않는다.
    "^swiper/css.*$": "identity-obj-proxy",
  },

  // 기본값은 node_modules 전체를 변환에서 제외한다. ESM 으로만 배포되는 패키지는
  // 여기서 예외로 열어 준다. axios 는 위에서 CJS 로 매핑해 해결했고, 아래 목록은
  // ESM 전용으로 배포되는 것들이다.
  transformIgnorePatterns: [
    "node_modules/(?!(swiper|ssr-window|dom7|@stomp|sockjs-client)/)",
  ],

  testMatch: ["<rootDir>/src/**/*.test.{js,jsx}"],

  collectCoverageFrom: [
    "src/**/*.{js,jsx}",
    "!src/index.jsx",
    "!src/**/*.test.{js,jsx}",
    "!src/__mocks__/**",
  ],

  // 조용한 실패를 막는다 — 테스트 안에서 처리되지 않은 프라미스 거부가 나면 드러나야 한다.
  errorOnDeprecated: true,
};
