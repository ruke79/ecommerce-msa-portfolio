package com.project.chat_service.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.project.chat_service.model.ParticipantChatRoom;

@Repository
public interface ParticipantChatRoomRepository extends JpaRepository<ParticipantChatRoom, Long> {

    /** 이미 참여 중인가 — 회원은 member_id(uid)로 가린다. */
    boolean existsByRoom_RoomUidAndMember_Id(String roomUid, Long memberId);
}
