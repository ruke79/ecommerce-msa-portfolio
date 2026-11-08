-- 장바구니 줄의 외래 키 열 인덱스 — cart.cart_product (cart_id).
--
-- PostgreSQL 은 외래 키 열에 인덱스를 자동으로 만들지 않는다. 장바구니 읽기(findByUserId 의 @EntityGraph — 화면 · 체크아웃 · 담기 ·
-- 수량 · 빼기 · Kafka 리스너 셋 모두)는 cart LEFT JOIN cart_product ON cart_id 라, 이 인덱스가 없으면 요청마다 줄 표 전체를 훑는다.
-- 장바구니를 지울 때도 외래 키 확인으로 한 번 더. order V28 · catalog V22 와 같은 결함 — cart 만 그 점검을 받지 않았다(운영 줄 표가
-- 작아 보이지 않았다). 있던 idx_cart_product(_uid, name, size, style)는 그 열로 찾는 질의가 없다 — contract 릴리스의 삭제 후보.
--
-- 운영 줄 수가 작아(2026-10 수십 줄) 그냥 CREATE INDEX — 잠깐의 쓰기 잠금. 큰 표라면 CONCURRENTLY + .sql.conf(docs/85 §8-1).
-- ROLLBACK-SAFE: 인덱스만 더한다. 옛 코드는 이 인덱스를 몰라도 같은 질의를 낸다.
-- [JA] カート行の外部キー列のインデックス。PostgreSQL は外部キー列に自動でインデックスを作らない。カート読み込み(画面 · チェックアウト ·
--      追加 · 数量 · 削除 · リスナー三つ)は cart_id の結合で、これがないと要求ごとに行テーブル全体を走査する。

CREATE INDEX IF NOT EXISTS idx_cart_product_cart ON cart.cart_product (cart_id);
