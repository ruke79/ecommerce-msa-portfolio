package com.project.userservice.security.password;

/** 유출됐거나 너무 흔한 비밀번호 — 가입 · 변경 · 재설정 컨트롤러가 400 으로 바꾼다(docs/68 §18). */
// [JA] 漏えい済みまたはありふれたパスワード。登録 · 変更 · 再設定のコントローラーが 400 にする。
public class BreachedPasswordException extends RuntimeException {

    public BreachedPasswordException() {
        super("유출됐거나 너무 흔해서 쉽게 추측되는 비밀번호입니다. 다른 비밀번호를 정해 주세요.");
    }
}
