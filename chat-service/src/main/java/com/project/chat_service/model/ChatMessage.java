package com.project.chat_service.model;

import lombok.*;
import org.springframework.data.mongodb.core.mapping.Document;

import com.project.common.constants.chat.MessageType;

import org.springframework.data.annotation.Id;


@Document(collection="chat")
@Getter
@Setter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
//MongoDB 채팅 모델
public class ChatMessage {

    // 🐛 예전에는 JPA 의 @Id · @Version(jakarta.persistence)이었다 — 몽고 매핑은 그것을 모른다. id 는 필드 이름 덕에 우연히
    //    _id 가 됐고, version 은 늘 null 인 평범한 필드로 모든 문서에 저장됐다. 쓰지 않는 version 은 없앴다(옛 문서의 값은 무시된다).
    @Id 
    private String id;
    
    private MessageType type;    
    
    private String roomId;

    /** 발신자 member_id(= user-service userId). v2 부터. */
    private Long senderId;

    // 발신자 이메일 필드는 없다 — 신원은 senderId 다. 옛 문서(v1)의 이메일은 지난 릴리스의 기동 백필이 senderId 로
    // 바꾸고 지웠다(docs/48 §6-⑥).
    @ToString.Exclude   // 사람을 가리키는 값 — 로그에 싣지 않는다
    private String nickname;

    @ToString.Exclude   // 대화 본문 — 로그에 싣지 않는다(MessageDto 와 같다)
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
