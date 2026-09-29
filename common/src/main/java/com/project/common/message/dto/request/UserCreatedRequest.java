package com.project.common.message.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
// 이메일 필드는 없다(docs/48 §6-⑥) — 소비자(coupon·chat) 누구도 쓰지 않는다. 옛 프로듀서·CDC·e2e 의 JSON 에
// email 이 있어도 무시한다.
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class UserCreatedRequest implements Serializable{

    
    private Long userId;
    private String image;
    private String username; 
    private String nickname; 
}
