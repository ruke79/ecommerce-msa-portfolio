package com.project.common.dto.notification;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

import java.io.Serializable;

import com.project.common.constants.AlarmEnum;


@Data
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class AlarmEventDto implements Serializable {
    private Integer senderNo;
    private Integer receiverNo;
    private AlarmEnum type;
    private String resource;
    private String content;

}
