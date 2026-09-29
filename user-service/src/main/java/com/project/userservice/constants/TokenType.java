package com.project.userservice.constants;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TokenType {

    ACCESS("ACCESS"),
    REFRESH("REFRESH"),
    OAUTH2("OAUTH2");
    
    private String type;

    TokenType(String type) {
        this.type = type;
    }
}
