package com.project.common.dto.chat.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.AccessLevel;

@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoomRequest {

    private String roomName;
}
