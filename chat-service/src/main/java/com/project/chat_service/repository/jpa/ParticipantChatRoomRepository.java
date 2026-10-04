package com.project.chat_service.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.project.chat_service.model.ParticipantChatRoom;

@Repository
public interface ParticipantChatRoomRepository extends JpaRepository<ParticipantChatRoom, Long> {

    /** 이미 참여 중인가 — 회원은 member_id(uid)로 가린다. */
    boolean existsByRoom_RoomUidAndMember_Id(String roomUid, Long memberId);

    /**
     * 참여 행을 없을 때만 넣는다 — 같은 (방, 회원)이 이미 있으면(다른 탭이 방금 넣었어도) 아무것도 하지 않는다(V9 유니크).
     *
     * @return 넣었으면 1, 이미 있었으면 0
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(nativeQuery = true,
            value = "INSERT INTO participant_chat_room (room_id, member_id) VALUES (:roomId, :memberId) ON CONFLICT DO NOTHING")
    int insertIfAbsent(@org.springframework.data.repository.query.Param("roomId") Long roomId,
            @org.springframework.data.repository.query.Param("memberId") Long memberId);
}
