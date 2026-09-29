package com.project.chat_service.model;

import lombok.Getter;

import com.project.common.constants.AlarmEnum;

import  jakarta.persistence.*;
/**
 * mysql에 저장할 notification entity
 * 채팅 알림 도메인
 * 엔티티에는 @NoArgsConstructor(access = AccessLevel.PROTECTED)(access = AccessLevel.PROTECTED)
 * @AllArgsConstructor , @Setter지양
 * @DynamicInsert로 수정되는 컬럼만 쿼리
 */
@Entity
@Getter
@Table(name = "alarm")
public class Alarm extends BaseEntity{
    @Id
    @Column(name = "alarm_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long alarmId;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "type")
    private AlarmEnum typeEnum;

    @Column(name = "url")
    private String url;

    @Column(name = "content")
    private String content;

    @Column(name = "delete_status")
    private boolean isDel;

    @Column(name = "read_status")
    private boolean isRead;

    @Column(name = "sender_id")
    private Long senderId;

    @Column(name = "receiver_id")
    private Long receiverId;

    @Column(name = "sender_name")
    private String senderName;

    
    public void read() {
        this.isRead = true;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }
}
