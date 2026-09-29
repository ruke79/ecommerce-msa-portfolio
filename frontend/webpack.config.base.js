const path = require("path");

module.exports = {
  infrastructureLogging: {
    level: "info",
  },
  entry: "./src/index.jsx",
  output: {
    publicPath: "/",
    path: path.resolve(__dirname, "dist"),
    filename: "[name].bundle.js",
    clean: true,
  },
  resolve: {
    alias: {
      // jsconfig.json 의 baseUrl:"src" 는 에디터에만 적용된다. webpack 은 여기 적힌
      // alias 로만 해석하므로, 새 최상위 폴더를 만들면 이곳에도 등록해야 한다 —
      // 빠뜨리면 'Module not found: Can't resolve ...' 로 빌드가 깨진다.
      api: path.resolve(__dirname, "./src/api"),
      components: path.resolve(__dirname, "./src/components"),
      pages: path.resolve(__dirname, "./src/pages"),
      util: path.resolve(__dirname, "./src/util"),
      reduxs: path.resolve(__dirname, "./src/redux"),
      store: path.resolve(__dirname, "./src/store"),
      hook: path.resolve(__dirname, "./src/hook"),
      constants: path.resolve(__dirname, "./src/constants"),
      error: path.resolve(__dirname, "./src/error"),
      assets: path.resolve(__dirname, "./src/assets"),
      // ⚠️ 바로 위 주석이 경고하는 그 함정에 실제로 걸렸다. jest 는
      //    moduleDirectories:["node_modules","src"] 로 src 아래를 전부 해석하므로
      //    테스트 426개가 전부 통과했는데, webpack 은 여기 적힌 것만 본다 —
      //    운영 빌드에서만 'Can't resolve i18n/I18nProvider' 로 깨졌다.
      i18n: path.resolve(__dirname, "./src/i18n"),
    },
    extensions: [".js", ".jsx"],
  },
  module: {
    rules: [
      {
        test: /\.(js|jsx)$/,
        exclude: ["/node_modules"],
        use: {
          loader: "babel-loader",
          options: {
            presets: [
              "@babel/preset-env",
              ["@babel/preset-react", { runtime: "automatic" }],
            ],
          },
        },
      },

      {
        test: /\.css$/,
        exclude: ["/node_modules"],
        use: ["style-loader", "css-loader", "postcss-loader"],
      },
      {
        test: /\.(jpg|jpeg|gif|png|svg|ico)?$/,
        exclude: ["/node_modules"],
        type: "asset",
        parser: {
          dataUrlCondition: {
            maxSize: 8 * 1024, // 8KB 미만은 인라인으로 처리
          },
        },
        generator: {
          filename: "assets/images/[name].[ext]",
        },
      },
    ],
  },
};
