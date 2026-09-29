package com.project.common.message.dto.request;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenRequest {
    
    // 토큰 소유자를 식별하는 사용자 ID
    private Long userId;

    // JWT와 같은 실제 토큰 값
    private String tokenValue;

    // 토큰의 종류 (예: "ACCESS_TOKEN", "REFRESH_TOKEN")
    private String tokenType;

    // 토큰 발행 또는 갱신 시간
    private LocalDateTime issuedAt;

    // 토큰 만료 시간
    private LocalDateTime expiresAt;
    
    // 이벤트를 식별하는 고유 ID
    private String correlationId;
}