package com.project.userservice.security.request;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordRequest {
    private String current_password;
    private String new_password;
}
