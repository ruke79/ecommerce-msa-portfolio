package com.project.userservice.security.request;

import java.util.Set;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
public class SignupRequest {
    @NotBlank
    @Size(min = 3, max = 20)
    private String username;

    @Size(min = 3, max = 20)
    private String name;    

    @NotBlank
    @Size(max = 50)
    @Email
    private String email;

    /** 이메일은 받을 때 NFKC — IME 의 전각 `ｕｓｅｒ＠ｅｘａｍｐｌｅ．ｃｏｍ` 이 `user@example.com` 으로 저장된다(로그인도 같은 값으로 찾는다). */
    // [JA] メールは受け取り時に NFKC — IME の全角メールが半角で保存される(ログインも同じ値で探す)。
    public void setEmail(String email) {
        this.email = com.project.common.text.Nfkc.of(email);
    }

    @Setter
    @Getter
    private Set<String> role;

    @NotBlank
    @Size(min = 6, max = 40)
    private String password;
}

