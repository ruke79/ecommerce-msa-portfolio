package com.project.chat_service.model;



import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.Setter;

@Entity
// 회원은 한 방에 한 번 참여한다(V9) — 예전에는 제약이 없어 동시 구독이 같은 참여를 두 번 남겼다.
@jakarta.persistence.Table(name = "participant_chat_room", indexes = {
        @jakarta.persistence.Index(name = "uq_participant_room_member", columnList = "room_id, member_id", unique = true) })
@Setter
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParticipantChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private ChatRoom room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private ChatMember member;

    private ParticipantChatRoom(ChatRoom room, ChatMember member) {
        this.room = room;
        this.member = member;        
    }

    public static ParticipantChatRoom get(ChatRoom room, ChatMember member) {
        return new ParticipantChatRoom(room, member);
    }
}
