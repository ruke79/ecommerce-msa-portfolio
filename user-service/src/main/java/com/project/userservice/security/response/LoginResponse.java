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
 */
@Setter
@Getter
@AllArgsConstructor
public class LoginResponse {

    private String email;
    private List<String> roles;

    /** 사람이 읽는 문구(영어). 코드를 모르는 클라이언트를 위한 폴백이다. */
    private String message;

    /**
     * 무슨 일이 있었는지. 프론트가 이 값으로 사전에서 문구를 고른다.
     *
     * @see com.project.userservice.security.AuthLoginFilter#LOGIN_SUCCESS_CODE
     * @see com.project.userservice.security.AuthLoginFilter#MULTI_LOGIN_CODE
     */
    private String code;
}
