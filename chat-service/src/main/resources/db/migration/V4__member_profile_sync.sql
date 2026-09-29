-- 채팅 회원 복제본이 원본의 프로필 변경·탈퇴를 따라가게 한다 (docs/48 §6-⑤, §7).
--
-- 1) 닉네임의 UNIQUE 를 없앤다.
--    chat.member 는 user-service 의 복제본이다. 원본의 이름(name)은 중복을 허용하는데 복제본에만 UNIQUE 가 걸려
--    있어, 같은 이름으로 가입하거나 바꾼 두 번째 사용자의 복제가 제약 위반으로 실패했다 — 가입 이벤트는 DLT 로,
--    이제 생기는 프로필 변경(user-updated)도 같은 이유로 실패한다. 탈퇴 익명화("탈퇴한 사용자")는 여러 명이 같은
--    이름을 가져야 한다. 원본의 제약을 복제본에 흉내 내면 원본이 바뀔 때 어긋난다.
--
-- 2) profile_updated_at — 마지막으로 반영한 변경의 원본 시각(epoch ms). 늦게 도착한 옛 변경을 거른다.
--    NULL 은 "가입 뒤 변경을 받은 적 없음" 이다.
--
-- ROLLBACK-UNSAFE: 제약을 지우는 것 자체는 옛 이미지와 호환된다(옛 코드는 이 제약에 기대지 않는다 — JPA 의
--   @UniqueConstraint 는 DDL 메타데이터일 뿐 validate 가 보지 않는다. 새 컬럼도 옛 엔티티가 모르는 채로 둔다).
--   다만 이 버전이 도는 동안 같은 닉네임이 생기면 이 제약을 **다시 걸 수 없다**. 되돌릴 일이 생기면 제약은 다시
--   걸지 않는다 — 조회용 인덱스 idx_member_nickname 은 그대로 남는다.

ALTER TABLE chat.member DROP CONSTRAINT IF EXISTS uk_member_nickname;

ALTER TABLE chat.member ADD COLUMN IF NOT EXISTS profile_updated_at bigint;
