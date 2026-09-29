package com.project.userservice.security.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 회원 탈퇴 신청.
 *
 * <p>{@code password} 는 <b>본인 확인</b>용이다. 로그인 상태만으로 탈퇴를 받으면, 잠깐
 * 자리를 비운 사이나 토큰이 탈취된 상태에서 계정이 지워질 수 있다 — 되돌릴 수 없는 동작
 * 앞에서는 한 번 더 묻는 것이 맞다. 소셜 로그인 계정에는 확인할 비밀번호가 없으므로
 * 서버가 그 경우에만 생략한다({@code WithdrawalService.requirePassword}).
 */
@Getter
@Setter
@NoArgsConstructor
public class WithdrawalRequest {

    private String password;

    /** 왜 떠나는지. 선택 사항이며 통계용이다. ⚠️ 자유 입력이므로 로그에 남기지 않는다. */
    @Size(max = 255)
    private String reason;
}
