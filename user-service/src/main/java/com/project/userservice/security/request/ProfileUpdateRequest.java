package com.project.userservice.security.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 프로필 수정 요청.
 *
 * <p>바꿀 수 있는 것은 <b>표시 이름 하나</b>다. 이메일과 로그인 id 는 여기에 없다 —
 * 둘은 신원 그 자체이고 JWT 의 subject 이며 다른 서비스가 사용자를 찾는 키다
 * (order·cart·coupon 이 전부 이메일로 조회한다). 필드를 아예 두지 않으면 나중에
 * "받아만 두고 무시한다" 같은 애매한 상태가 생기지 않는다.
 */
@Getter
@Setter
public class ProfileUpdateRequest {

    @NotBlank(message = "이름을 입력해 주세요.")
    @Size(max = 50, message = "이름은 50자까지 입력할 수 있습니다.")
    private String name;
}
