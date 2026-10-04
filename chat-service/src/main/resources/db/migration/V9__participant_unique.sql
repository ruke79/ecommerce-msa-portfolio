-- 회원은 한 방에 한 번 참여한다 — (room_id, member_id) 유니크(docs/62 §18).
--
-- 🐛 예전에는 제약이 없었고, 참여는 "있나 보고 없으면 넣기" 였다. 두 탭이 동시에 구독하면 둘 다 "없음" 을 읽어 같은 참여가
-- 두 줄 생겼다. 이제 넣기는 INSERT … ON CONFLICT DO NOTHING(ParticipantChatRoomRepository.insertIfAbsent)이다.
-- [JA] 会員は一つのルームに一度だけ参加する。同時購読で同じ参加が二行できていた。
--
-- 순서: ① 중복을 지운다(가장 먼저 생긴 행을 남긴다 — 참여 행에는 (방, 회원) 말고 담긴 것이 없다) → ② 유니크 인덱스.
-- 테이블 이름은 V3 와 같이 스키마 없이 쓴다(엔티티도 스키마를 적지 않는다).
--
-- 되돌리기: 이미지만 되돌려도 된다 — 옛 코드는 유니크가 있어도 동작한다(동시 구독이면 한쪽이 실패할 뿐).

DELETE FROM participant_chat_room p
 USING participant_chat_room q
 WHERE p.room_id = q.room_id
   AND p.member_id = q.member_id
   AND p.participant_id > q.participant_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_participant_room_member ON participant_chat_room (room_id, member_id);
