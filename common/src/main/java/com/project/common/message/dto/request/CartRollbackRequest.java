package com.project.common.message.dto.request;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;

import java.io.Serializable;



@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class CartRollbackRequest implements Serializable {

    Long userId;
    int qty;
    // 사용자 액세스 토큰은 싣지 않는다(docs/54 §10.1).
}
