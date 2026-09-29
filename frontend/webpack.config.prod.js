const webpack = require("webpack");
const HtmlWebPackPlugin = require("html-webpack-plugin");
const { merge } = require("webpack-merge");
const baseConfig = require("./webpack.config.base");
const CssMinimizerPlugin = require("css-minimizer-webpack-plugin");
const TerserPlugin = require("terser-webpack-plugin");
const CopyPlugin = require("copy-webpack-plugin");
const dotenv = require("dotenv");
const { clientEnv } = require("./webpack.env");

dotenv.config({ path: "./.env.production" });

module.exports = merge(baseConfig, {
  mode: "production",

  // 🔒 `source-map` 이었다. 그러면 dist/*.map 이 만들어지고 nginx 가 **그대로 서빙한다** —
  // 주석까지 포함한 원본 소스가 누구에게나 공개된다. 이 저장소의 주석에는 왜 그렇게
  // 고쳤는지, 어떤 공격 경로를 막았는지가 적혀 있다. 공격자에게 줄 만한 글이 아니다.
  //
  // `hidden-source-map` 은 맵을 만들되 번들에 `//# sourceMappingURL=` 주석을 넣지 않는다.
  // 브라우저는 맵을 찾지 않고, 오류 추적 도구(Sentry 등)에는 빌드 산출물로 따로 올릴 수
  // 있다. 맵 자체를 없애면(`false`) 나중에 스택트레이스를 풀 방법이 사라진다.
  //
  // ⚠️ 맵 파일은 여전히 dist/ 에 만들어진다. 주석이 없을 뿐이라 `/main.bundle.js.map` 을
  // 직접 찍으면 그대로 받아진다. 그래서 nginx.conf 가 `.map` 요청을 404 로 막는다 —
  // 둘 중 하나만으로는 부족하다.
  devtool: "hidden-source-map",

  // 🔴 파일 이름에 **내용 해시**를 넣는다. 이게 없으면 배포해도 사용자는 옛 화면을 본다.
  //
  // base 설정은 `filename: "[name].bundle.js"` 라 번들 이름이 **언제나 main.bundle.js**
  // 였다. 그런데 nginx.conf 의 캐시 규칙은 CRA 처럼 해시 붙은 파일이 `/static/` 에 있다고
  // 전제한다(그 주석에 그렇게 적혀 있다). 실제 산출물은 루트의 `main.bundle.js` 라
  // 그 규칙에 **하나도 걸리지 않았고**, Cache-Control 헤더가 아예 붙지 않았다.
  //
  // 헤더가 없으면 브라우저는 *휴리스틱 캐싱*을 한다 — 보통 Last-Modified 로부터 지난
  // 시간의 10% 를 제 마음대로 캐시한다. index.html 은 no-cache 라 새로 받아오지만 그
  // 안의 `<script src="/main.bundle.js">` 는 이름이 그대로라, 브라우저는 **캐시에 있는
  // 옛 번들을 그대로 쓴다.** 개발자도구에는 `200 OK (from disk cache)` 로 보이고 서버는
  // 새 파일을 잘 서빙하고 있어서, "배포했는데 화면이 그대로다" 로만 나타난다.
  //
  // 이름에 해시가 들어가면 내용이 바뀔 때마다 경로가 바뀐다 — 캐시가 낄 자리가 없다.
  // `/static/` 아래로 내보내 nginx 의 기존 immutable 규칙이 의도대로 걸리게 한다.
  // HtmlWebPackPlugin 이 script 태그를 새 이름으로 다시 쓰므로 수동 작업은 없다.
  output: {
    // 🧹 빌드 전에 dist/ 를 비운다. 해시가 붙으면 더 중요해진다 — 없으면 옛 해시 파일이
    // 무한히 쌓여 이미지가 계속 커진다.
    clean: true,
    filename: "static/[name].[contenthash:8].bundle.js",
    chunkFilename: "static/[name].[contenthash:8].bundle.js",
  },
  plugins: [
    new HtmlWebPackPlugin({
      template: "./public/index.html",
    }),
    // REACT_APP_* 전부 + PUBLIC_URL + NODE_ENV. 목록을 손으로 관리하지 않는다 — 이유는
    // webpack.env.js 에. 빠진 이름 하나가 첫 화면의 `process is not defined` 였다.
    new webpack.DefinePlugin(clientEnv("production")),

    // ⚠️ public/ 은 **루트로** 복사한다. 그리고 assets/ 를 빼지 않는다.
    //
    // 예전 설정은 `to: "public"` + `ignore: ["**/assets/**"]` 이었다. 둘 다 틀렸다.
    //
    //   ① `to: "public"` — 브라우저가 `/favicon.ico` 를 찾는데 파일은 `/public/favicon.ico`
    //      에 놓인다. public/ 의 내용은 **루트에서** 서빙되는 것이 CRA·웹팩의 관례다.
    //
    //   ② `ignore: ["**/assets/**"]` — 번들러가 `assets/images/[name].[ext]` 로 내보내니
    //      겹친다고 본 것인데, 번들러가 내보내는 것은 **import 한 이미지뿐**이다.
    //      문자열 경로로만 쓰는 이미지는 import 가 없어 아무도 만들지 않고, Copy 도
    //      건너뛰어 운영에서 404 가 됐다:
    //
    //        · /assets/images/{paypal,credit_card,cash}.png  → 결제수단 아이콘 전부
    //          (order/Payment.jsx 의 import 는 주석 처리돼 있다 — 그래서 안 나온다)
    //        · /assets/images/{visa,mastercard,jcb,…}.png    → 카드 브랜드 로고 6종.
    //          이것들은 public/ 에만 있어 번들러가 만들 수가 없다
    //        · /assets/images/<brand>.png                    → /browse 의 브랜드 로고
    //
    //      개발에서는 멀쩡했다. webpack.config.dev.js 에는 CopyPlugin 이 없고 dev-server 가
    //      public/ 을 그대로 서빙하기 때문이다 — "로컬에선 되는데 배포하면 깨진다"의 전형.
    //
    // 이름이 겹치는 31개는 src/assets/images 와 **바이트까지 동일한 사본**임을 확인했다.
    // CopyPlugin 은 기본값(force:false)이라 컴파일이 이미 만든 자산을 덮어쓰지 않으므로,
    // 겹치면 번들러 쪽이 남는다 — 같은 파일이라 어느 쪽이든 결과가 같다.
    //
    // index.html 만 뺀다. 그건 HtmlWebPackPlugin 이 해시 붙은 번들을 가리키도록 만든다 —
    // 템플릿 원본을 덮어쓰면 스크립트 태그가 없는 빈 페이지가 된다.
    new CopyPlugin({
      patterns: [
        {
          from: "public",
          to: ".",
          globOptions: {
            ignore: ["**/index.html"],
          },
        },
      ],
    }),
  ],

  optimization: {
    minimize: true,
    minimizer: [
      new TerserPlugin({
        terserOptions: {
          compress: {
            // 🔴 `drop_console: true` 였다. 그건 console.log 만이 아니라
            //    **console.error·console.warn 까지 전부** 지운다 — 운영에서 클라이언트
            //    진단이 0 이 된다.
            //
            //    실제로 지워지던 것들: "[auth] 강제 로그아웃 — 원인 요청: …"(토큰 갱신),
            //    ErrorBoundary 가 찍는 컴포넌트 스택, axios 인터셉터의 실패 사유.
            //    사용자는 토스트 한 줄을 보고 우리는 아무것도 못 본다.
            //
            //    소음(log·debug)만 지우고 진단(error·warn·info)은 남긴다.
            drop_debugger: true,
            pure_funcs: ["console.log", "console.debug", "console.trace"],
            warnings: false,
          },
          mangle: {
            // 코드 변수명 난독화
            toplevel: true, // 최상위 변수 이름도 변경
          },
          format: {
            // 출력 포맷
            comments: false, // 주석 제거
          },
        },
      }),
      new CssMinimizerPlugin(),
    ],
  },
});
