# 品質ハードニングラウンド（テスト・OpenAPI・オートスケール・フロントビルド検証）

**日本語** | [한국어](10-quality-hardening.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** Testcontainers・NetworkPolicy・HPA はその後導入しました（[17](17-testing.md) · [57](57-remaining-priorities.md) · [21](21-horizontal-scaling.md)）。

> 作成日: 2026-07-27 · 背景: MSA 最終点検で確認された空白（G11〜G13、G18）のうち、当時の検証環境で安全に対処できる項目の適用記録

## 1. コア経路の単体テスト（G11 一部解消）

今回の作業で修正したバグが再発しないよう、**回帰テストで固定**した。

| テスト | 守るもの |
|---|---|
| `catalog-service` `ProductServiceUnitTest` | ① **複数項目の注文で全項目の在庫／販売数を反映**（CAT-13 — 以前は最初の項目だけを処理） ② null リクエストの無害性 ③ **キャッシュ無効化の契約**: `productUpdated`/`addProduct` に `@CacheEvict(search_cache…, allEntries)` が自己呼び出し問題を避けて外部エントリーポイントに付いているか（CAT-2/12） |
| `catalog-service` `SearchServiceUnitTest` | ① `page=0` のデフォルトリクエストが 500 にならず offset 0/limit 20 にクランプ（CAT-3） ② `pageSize=99999` → 上限 100（deep pagination DoS の防止） ③ category の **名前** を入力した場合、NumberFormatException なしで名前経路へ（CAT-4） ④ 数値なら id 経路 |
| `gateway-server` `CustomUserKeyResolverTest` | レートリミッターのバケットキーが user→XFF→ソケットIP→anonymous の順でフォールバックし、**どんな場合でも null にならない**（既存の NPE バグ） |

依然として不足しているもの（正直に）: cart/order/pay の Saga・Outbox 経路、フロントの 401 インターセプターは未カバー。統合テスト（Testcontainers）と契約テストはロードマップ（G14）。

## 2. OpenAPI の導入（G13 解消）

- MVC サービス 6 つ（user/catalog/cart/order/pay/coupon）に `springdoc-openapi-starter-webmvc-ui:2.8.5` を追加 → 各サービスで `/v3/api-docs`、`/swagger-ui.html` を提供。
- **chat-service の springfox 3.0 を削除**: Spring Boot 3 と互換性のない廃れたライブラリで、Boot 2.6 用の回避ハック（`springfoxHandlerProviderBeanPostProcessor` リフレクション BeanPostProcessor）まで一緒にあった。springdoc に置き換え、該当の Config クラスを削除。
- 参考: ゲートウェイ（WebFlux）での統合ドキュメント集約は別作業（ロードマップ）。API バージョニング（G12）は URL 契約の変更なので今回の範囲から除外。

## 3. k8s オートスケール／無停止（G18 一部解消）

`k8s/70-autoscaling.yaml`:
- **HPA**（CPU 70%、1→3）: gateway + 6 つのバックエンド + frontend。k3s 内蔵の metrics-server を使用。
- **意図的な除外**: chat-service（インメモリ STOMP ブローカー — スケールアウト時にメッセージが断片化、docs/03 F-7）、eureka（peer-awareness が先に必要）。
- **PDB**（`maxUnavailable: 1`）: 全サービス。レプリカが 1 のときはドレインを妨げず、HPA で増えてから最低可用性の保証が実質的に機能する。
- NetworkPolicy は依然としてロードマップ。

## 4. フロントエンドのプロダクションビルド検証

- `npm install` 後の `npm run build`（webpack prod）**成功** — 前ラウンドのフロントのリファクタリング（tokenUtil の削除、axios ラッパーの書き直し、TokenRefresher の 401 キュー修正、依存関係の削除）がバンドルレベルで検証された。
- `package-lock.json` を `package.json` の変更（save-dev/toast の削除）と **同期**してコミット（以前はずれていて `npm ci` が失敗する状態だった）。
- 警告: `main.bundle.js` 1.92MiB — コード分割の余地あり（ロードマップ）。

## 検証まとめ

- 新規テスト 3 クラス **11 ケースすべてが Gradle 実行で通過**（catalog 7 + gateway 4、failures 0）。
- springfox→springdoc の置き換えを含め、全サービスの `compileJava` + catalog の `compileTestJava` が **BUILD SUCCESSFUL**。
- フロントの `npm run build`（webpack production）**成功**、`package-lock.json` を同期。
- `70-autoscaling.yaml` の 17 リソース（HPA 8 + PDB 9）のパースを検証。

### 併せて発見／修正したもの

- **レガシーテスト 4 ファイルがコンパイルすらできない状態**だったことが判明した（存在しない `repository.*` パッケージのワイルドカード import — パッケージ再構成の際に放置されたもの）。死んだ import 2 行を削除してコンパイルは復旧した。
- ただし、レガシーの `@SpringBootTest` 系（コンテキストのロードに実際の DB/ES/Kafka が必要）は当時の検証環境では **実行できず**、CI でもインフラなしでは失敗する → Testcontainers への移行が必要（ロードマップ）。新規テストは純粋な単体テストなのでどこでも動く。
