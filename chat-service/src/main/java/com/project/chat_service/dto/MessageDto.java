package com.project.chat_service.dto;


import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

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

    private String nickname;
               
    private String content;    

    private String imageUrl;
        
    private Integer readCount;
    
    private Long createdAt;
  

    public void setSendTimeAndSender(LocalDateTime sendTime, String nickName, Long senderId, Integer readCount) {
        this.senderId = senderId;
        this.nickname = nickName;
        this.createdAt = sendTime.atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();        
        this.readCount = readCount;
    }
    
    
}
