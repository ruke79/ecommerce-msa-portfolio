# フロントエンド

**日本語** | [한국어](README.ko.md)

React 18 の SPA です。ビルドは **webpack 5** を直接使います（Create React App は取り除きました）。

## コマンド

```bash
npm ci
npm start              # 開発サーバー（webpack-dev-server, webpack.config.dev.js）
npm test               # Jest + Testing Library
npm run lint           # ESLint（警告の上限をラチェットで固定）
npm run build          # 本番ビルド → dist/（webpack.config.prod.js）
```

## 設定

- `REACT_APP_*` は**ビルド時**にバンドルへ埋め込まれます（`webpack.env.js`）。k8s のマニフェストには置きません（ガード B-51）。
- 値の例は [`ops/frontend.env.example`](../ops/frontend.env.example) にあります。CI はリポジトリの Variables から読みます。
- 本番ビルドのファイル名にはハッシュが付き（`static/[name].[contenthash].bundle.js`）、nginx が `immutable` でキャッシュします。

## 構成の要点

- データ取得は React Query v5、カートは Redux Toolkit。カートはサーバーが基準です（ログイン・ログアウト時に同期）。
- 文言は `src/i18n/messages/` の辞書（韓国語・日本語）。画面にハードコードされた文言は `hardcodedText.test` が見つけます。
- import パスが webpack と jest で同じように解決されることを `importPaths.test` が確認します。

詳しくは [docs/README](../docs/README.md) の「フロントエンド」の章を参照してください。
