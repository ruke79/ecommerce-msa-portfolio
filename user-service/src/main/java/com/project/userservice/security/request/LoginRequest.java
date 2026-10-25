package com.project.userservice.security.request;


import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class LoginRequest {
    private String username;
    private String email;
    private String password;

    /** 가입과 같은 NFKC — 전각으로 친 이메일로도 로그인된다. 비밀번호는 바꾸지 않는다(글자 그대로가 비밀번호다). */
    // [JA] 登録と同じ NFKC — 全角のメールでもログインできる。パスワードは変えない(文字そのものがパスワード)。
    public void setEmail(String email) {
        this.email = com.project.common.text.Nfkc.of(email);
    }
};