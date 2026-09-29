package com.project.common.constants;

import lombok.Getter;

@Getter
public enum AlarmEnum {

    CHAT("chatroom/","chat");
    

    private final String path;
    private final String alias;

    AlarmEnum(String path, String alias) {
        this.path = path;
        this.alias = alias;
    }
}
