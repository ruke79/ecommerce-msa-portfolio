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
    // 🐛 예전: 50자까지 받았는데 열(users.name)과 엔티티는 20자다 — 21~50자는 검증을 지나 저장에서 실패해(자동 flush 의
    //    ConstraintViolation) 404 로 보였다. 소셜 가입도 20자로 자른다(CustomOAuth2UserService.USERNAME_MAX).
    // [JA] 以前は 50 文字まで受けたが列とエンティティは 20 文字 — 21〜50 文字は保存で失敗して 404 に見えた。
    @Size(max = 20, message = "이름은 20자까지 입력할 수 있습니다.")
    private String name;
}
