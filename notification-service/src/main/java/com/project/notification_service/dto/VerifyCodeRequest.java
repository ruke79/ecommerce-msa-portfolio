package com.project.notification_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyCodeRequest(
        @NotBlank @Size(max = 30) String phone,
        @Size(max = 2) String region,
        // 6자리 숫자만. 형식이 틀린 입력은 DB 조회까지 갈 이유가 없다.
        @NotBlank @Pattern(regexp = "\\d{6}", message = "인증번호는 6자리 숫자입니다") String code) {
}
