package com.project.userservice.security.passkey;

/** 패스키 등록 · 삭제가 거절됐다 — 문구는 사용자에게 보인다(client-text.ja.txt). */
// [JA] パスキーの登録・削除が拒否された — 文言はユーザーに見える(client-text.ja.txt)。
public class PasskeyException extends RuntimeException {
    public PasskeyException(String message) {
        super(message);
    }
}
