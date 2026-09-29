package com.project.userservice.dto;


import com.project.userservice.constants.NotificationEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class AlarmEventDto implements Serializable {
    private Integer senderNo;
    private Integer receiverNo;
    private NotificationEnum type;
    private String resource;
    private String content;

}
