package com.project.chat_service.model;

import lombok.*;
import org.springframework.data.mongodb.core.mapping.Document;

import com.project.common.constants.chat.MessageType;

import jakarta.persistence.Column;
import  jakarta.persistence.Id;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Document(collection="chat")
@Getter
@Setter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
//MongoDB 채팅 모델
public class ChatMessage {

    @Version
    @Column(name = "version")
    private Long version;

    @Id 
    private String id;
    
    private MessageType type;    
    
    private String roomId;

    /** 발신자 member_id(= user-service userId). v2 부터. */
    private Long senderId;

    // 발신자 이메일 필드는 없다 — 신원은 senderId 다. 옛 문서(v1)의 이메일은 지난 릴리스의 기동 백필이 senderId 로
    // 바꾸고 지웠다(docs/48 §6-⑥).
    private String nickname;
               
    private String content;    

    private String imageUrl;
        
    private Integer readCount;
    
    private String createdAt;

    private ChatMessage(MessageType type, String nickname, Long senderId, String roomId, String content, String imageUrl, Integer readCount,  String createdAt) {
        this.type = type;
        this.nickname = nickname;
        this.senderId = senderId;
        this.roomId = roomId;
        this.content = content;
        this.imageUrl = imageUrl;
        this.createdAt = createdAt;        
        this.readCount = readCount;
    }

    public static ChatMessage get(MessageType type, String nickname, Long senderId, String roomId, String content, String imageUrl, Integer readCount, String createdAt) {
        return new ChatMessage(type, nickname, senderId, roomId, content, imageUrl, readCount, createdAt);
    }
}
