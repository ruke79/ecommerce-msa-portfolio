-- 리뷰 본문 컬럼을 검증 한도(2,000자)에 맞춘다.
--
-- 🐛 화면과 서버 검증(ReviewService.validate)은 2,000자까지 받는데 컬럼은 varchar(255)였다. 255자를 넘는 리뷰는 저장 단계에서
-- DB 오류로 실패했고, 화면에는 "리뷰를 등록하지 못했습니다" 만 보였다. 넓히기만 한다 — 기존 행은 그대로다.
-- ROLLBACK-SAFE: varchar 를 넓히기만 한다 — 옛 이미지의 Hibernate validate 는 길이를 보지 않는다(B-54).
ALTER TABLE users.review ALTER COLUMN review TYPE varchar(2000);
