package com.project.chat_service.mapper;

import com.project.chat_service.model.Alarm;
import com.project.common.dto.notification.AlarmResponse;

import org.springframework.stereotype.Component;

@Component
public class AlarmMapper {

    static public AlarmResponse toDto(Alarm alarm) {
        if (alarm == null) {
            return null;
        }

        return AlarmResponse.builder()
                .id(alarm.getAlarmId())
                .type(alarm.getTypeEnum().getAlias())
                .content(alarm.getContent())
                .url(alarm.getUrl())
                .publishedAt(alarm.getRegDate())
                .read(alarm.isRead())
                .del(alarm.isDel())
                .senderId(alarm.getSenderId())
                .build();
    }
}