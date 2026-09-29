# 프론트엔드

[日本語](README.md) | **한국어**

React 18 SPA 입니다. 빌드는 **webpack 5** 를 직접 씁니다(Create React App 은 걷어냈습니다).

## 명령

```bash
npm ci
npm start              # 개발 서버(webpack-dev-server, webpack.config.dev.js)
npm test               # Jest + Testing Library
npm run lint           # ESLint(경고 상한을 래칫으로 고정)
npm run build          # 운영 빌드 → dist/(webpack.config.prod.js)
```

## 설정

- `REACT_APP_*` 는 **빌드할 때** 번들에 박힙니다(`webpack.env.js`). k8s 매니페스트에는 두지 않습니다(가드 B-51).
- 값 예시는 [`ops/frontend.env.example`](../ops/frontend.env.example) 에 있습니다. CI 는 저장소 Variables 에서 읽습니다.
- 운영 빌드의 파일 이름에는 해시가 붙고(`static/[name].[contenthash].bundle.js`), nginx 가 `immutable` 로 캐시합니다.

## 구성 요점

- 데이터 조회는 React Query v5, 장바구니는 Redux Toolkit. 장바구니는 서버가 기준입니다(로그인·로그아웃 때 동기화).
- 문구는 `src/i18n/messages/` 사전(한국어·일본어). 화면에 박힌 문구는 `hardcodedText.test` 가 찾습니다.
- import 경로가 webpack 과 jest 에서 같게 풀리는지 `importPaths.test` 가 확인합니다.

자세한 내용은 [docs/README](../docs/README.ko.md) 의 "프론트엔드" 장을 보세요.
