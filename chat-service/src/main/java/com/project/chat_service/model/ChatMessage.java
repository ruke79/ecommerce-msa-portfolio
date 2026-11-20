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
// [JA] MongoDB チャットモデル
public class ChatMessage {

    // 🐛 예전에는 JPA 의 @Id · @Version(jakarta.persistence)이었다 — 몽고 매핑은 그것을 모른다. id 는 필드 이름 덕에 우연히
    //    _id 가 됐고, version 은 늘 null 인 평범한 필드로 모든 문서에 저장됐다. 쓰지 않는 version 은 없앴다(옛 문서의 값은 무시된다).
    // [JA] 🐛 以前は JPA の @Id・@Version(jakarta.persistence)だった — Mongo マッピングはそれを知らない。id はフィールド名のおかげで偶然
    //      _id になり、version は常に null の普通のフィールドとしてすべてのドキュメントに保存されていた。使わない version はなくした(旧ドキュメントの値は無視される)。
    @Id 
    private String id;
    
    private MessageType type;    
    
    private String roomId;

    /** 발신자 member_id(= user-service userId). v2 부터. */
    // [JA] 送信者 member_id(= user-service の userId)。v2 から。
    private Long senderId;

    // 발신자 이메일 필드는 없다 — 신원은 senderId 다. 옛 문서(v1)의 이메일은 지난 릴리스의 기동 백필이 senderId 로
    // 바꾸고 지웠다(docs/48 §6-⑥).
    // [JA] 送信者メールのフィールドはない — 身元は senderId である。旧ドキュメント(v1)のメールは前回リリースの起動時バックフィルが senderId に
    //      置き換えて消した(docs/48 §6-⑥)。
    @ToString.Exclude   // 사람을 가리키는 값 — 로그에 싣지 않는다 ／ [JA] 人を指す値 — ログに載せない
    private String nickname;

    @ToString.Exclude   // 대화 본문 — 로그에 싣지 않는다(MessageDto 와 같다) ／ [JA] 会話本文 — ログに載せない(MessageDto と同じ)
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
