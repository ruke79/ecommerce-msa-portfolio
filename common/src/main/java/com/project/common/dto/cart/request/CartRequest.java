package com.project.common.dto.cart.request;


import java.util.List;

import com.project.common.dto.ProductInfoDto;

import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
// 🔒 email 필드는 없다 — 누구의 장바구니인가는 검증된 토큰의 uid(User-Id 헤더)가 정한다. 예전에는 본문의 email 을
// 믿어 남의 장바구니를 덮어쓸 수 있었다(docs/38 §1.6). 옛 클라이언트가 보내는 email 은 무시한다.
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class CartRequest {

    @NotNull
    private List<ProductInfoDto> products;
 }
