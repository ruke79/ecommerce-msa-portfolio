package com.project.common.dto.chat;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class ChatRoomDto implements Serializable {
    private String roomId;
    private String name;    
}