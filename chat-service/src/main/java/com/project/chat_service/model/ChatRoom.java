package com.project.chat_service.model;

import lombok.*;

import  jakarta.persistence.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom implements Serializable {

    private static final long serialVersionUID = 6494678977089006639L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    private Long id;

    @Column(nullable = false)
    private String roomUid;

    @Column(nullable = false)
    private String roomName;

    @OneToMany(mappedBy = "room")
    private List<ParticipantChatRoom> roomList = new ArrayList<ParticipantChatRoom>();

    public static ChatRoom create(String name){
        return get(name);
    }

    private ChatRoom(String roomName) {
        this.roomUid = UUID.randomUUID().toString();
        this.roomName = roomName;
    }

    public static ChatRoom get(String roomName) {
        return new ChatRoom(roomName);
    }
}
