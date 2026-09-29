package com.project.userservice.constants;

import lombok.Getter;

@Getter
public enum NotificationEnum {
    
    LOG_IN("login/","login"),
    LOG_OUT("logout/","logout");

    private final String path;
    private final String alias;

    NotificationEnum(String path, String alias) {
        this.path = path;
        this.alias = alias;
    }
}
