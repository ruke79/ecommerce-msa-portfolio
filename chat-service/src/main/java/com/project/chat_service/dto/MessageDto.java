package com.project.chat_service.dto;


import java.io.Serializable;
import java.time.Instant;

import com.project.common.constants.chat.MessageType;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import lombok.AccessLevel;

@Slf4j
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
// 🔒 이메일 필드는 없다(docs/48 §6-⑥). 옛 클라이언트가 본문에 email 을 실어 보내도 무시한다 — 받지도, 내보내지도 않는다.
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class MessageDto implements Serializable {

    @Transient
    public static final String SEQUENCE_NAME = "message_sequence";
    
    @Id    
    private String id;
    private MessageType type;        
    private String roomId;

    /**
     * 발신자 = CONNECT 때 검증된 토큰의 {@code uid}(chat member_id). 서버가 채운다 — 클라이언트가 보낸 값은 덮어쓴다.
     *
     * <p>화면에는 <b>문자열</b>로 나간다. TSID 는 19자리라 JS {@code Number}(2^53)에 담기면 끝자리가 뭉개지고,
     * "내 메시지인가" 비교가 조용히 틀린다(docs/25 §11.6 의 주문 번호와 같은 함정).
     */
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long senderId;

    @ToString.Exclude   // 사람을 가리키는 값 — 로그에 싣지 않는다
    private String nickname;
               
    @ToString.Exclude   // 대화 본문(상담 내용 포함) — 로그에 싣지 않는다
    private String content;    

    private String imageUrl;
        
    private Integer readCount;
    
    private Long createdAt;
  

    /**
     * @param sendTime 보낸 순간. 🐛 예전 인자는 {@code LocalDateTime.now()} 를 서울 시간으로 해석했는데, 컨테이너 JVM 은 UTC 라
     *                 모든 메시지 시각이 <b>9시간 일렀다</b>(상담 스레드의 마지막 메시지 시각 · Instant 와도 어긋났다).
     *                 순간(Instant)을 받아 시간대 해석 없이 epoch 밀리초로 둔다. 이미 저장된 메시지는 그대로다.
     */
    // [JA] 以前は LocalDateTime.now() をソウル時間として解釈していたが、コンテナの JVM は UTC なので全メッセージ時刻が 9 時間早かった。
    public void setSendTimeAndSender(Instant sendTime, String nickName, Long senderId, Integer readCount) {
        this.senderId = senderId;
        this.nickname = nickName;
        this.createdAt = sendTime.toEpochMilli();
        this.readCount = readCount;
    }
    
    
}
