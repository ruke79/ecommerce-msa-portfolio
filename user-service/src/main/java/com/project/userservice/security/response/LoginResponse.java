package com.project.userservice.security.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 로그인 성공 응답.
 *
 * <h3>🐛 문구가 서버에서 영어로 내려왔다</h3>
 *
 * <p>{@code message} 는 서버가 만든 영어 문장이었고
 * ({@code "User attempted to log in multiply, logging out from previous logged in state"}),
 * 프론트는 그것을 {@code toast.success(response.data.message)} 로 <b>그대로</b> 띄웠다.
 * 화면 나머지는 한국어·일본어인데 로그인 직후에만 영어가 나오는 이유가 이것이다.
 *
 * <p>문구를 서버에서 번역하지 않는 이유: 서버는 <b>사용자의 표시 언어를 모른다.</b>
 * 언어는 브라우저가 고르고 {@code localStorage} 에 들어 있다(docs/45). {@code Accept-Language}
 * 로 추측할 수는 있지만 사용자가 화면에서 고른 값과 어긋난다.
 *
 * <p>그래서 서버는 <b>무슨 일이 있었는지</b>만 코드로 알리고, 문구는 프론트의 사전이 고른다.
 * 실패 경로에서 이미 같은 방식을 쓰고 있다({@code AuthLoginFilter.Failure.code},
 * {@code email_not_verified}).
 *
 * <p>{@code message} 는 <b>남겨 둔다.</b> 지우면 코드를 모르는 클라이언트가 아무것도 받지
 * 못한다. 프론트는 코드를 먼저 보고, 모르는 코드면 이 문구로 떨어진다.
 *
 * <p>[JA] ログイン成功の応答。
 * 🐛 文言がサーバーから英語で降りてきていた。{@code message} はサーバーが作った英語の文
 * ({@code "User attempted to log in multiply, logging out from previous logged in state"})で、フロントはそれを
 * {@code toast.success(response.data.message)} でそのまま表示していた。画面の他の部分は韓国語・日本語なのに、ログイン直後
 * だけ英語が出る理由がこれである。
 * 文言をサーバーで翻訳しない理由: サーバーはユーザーの表示言語を知らない。言語はブラウザが選び {@code localStorage} に入っている
 * (docs/45)。{@code Accept-Language} で推測はできるが、ユーザーが画面で選んだ値とずれる。
 * そこでサーバーは何が起きたかだけをコードで知らせ、文言はフロントの辞書が選ぶ。失敗経路ですでに同じ方式を使っている
 * ({@code AuthLoginFilter.Failure.code}, {@code email_not_verified})。
 * {@code message} は残しておく。消すとコードを知らないクライアントが何も受け取れない。フロントはまずコードを見て、知らない
 * コードならこの文言にフォールバックする。
 */
@Setter
@Getter
public class LoginResponse {

    private String email;
    private List<String> roles;

    /** 사람이 읽는 문구(영어). 코드를 모르는 클라이언트를 위한 폴백이다. */
    // [JA] 人が読む文言(英語)。コードを知らないクライアントのためのフォールバックである。
    private String message;

    /**
     * 무슨 일이 있었는지. 프론트가 이 값으로 사전에서 문구를 고른다.
     *
     * <p>[JA] 何が起きたか。フロントがこの値で辞書から文言を選ぶ。
     *
     * @see com.project.userservice.security.AuthLoginFilter#LOGIN_SUCCESS_CODE
     * @see com.project.userservice.security.AuthLoginFilter#MULTI_LOGIN_CODE
     */
    private String code;

    /**
     * 비밀번호 권고 — {@code breached} 면 이 비밀번호가 유출 · 흔한 목록에 있다(로그인은 됐다, 화면이 변경을 권한다). 없으면 빠진다.
     * [JA] パスワードの勧告 — breached なら漏えい · ありふれた一覧にある(ログインは成功、画面が変更を勧める)。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String passwordAdvice;

    public LoginResponse(String email, List<String> roles, String message, String code) {
        this.email = email;
        this.roles = roles;
        this.message = message;
        this.code = code;
    }
}
