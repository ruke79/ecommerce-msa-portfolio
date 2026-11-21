package com.project.common.dto.cart.request;


import java.util.List;

import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.catalog.request.ProductInfoRequest;

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
// [JA] 🔒 email フィールドはない — 誰のカートかは検証済みトークンの uid(User-Id ヘッダー)が決める。以前は本文の email を信じたため
//      他人のカートを上書きできた(docs/38 §1.6)。古いクライアントが送る email は無視する。
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class CartRequest {

    @NotNull
    // 한 요청의 줄 수 상한 — 줄마다 catalog 조회가 붙는다. 🐛 2026-11-21 까지 제한이 없었다(docs/93 ③ 3단계 · docs/38 §2.5).
    // [JA] 一回の要求の行数の上限 — 行ごとに catalog の照会が付く。以前は制限がなかった。
    @jakarta.validation.constraints.Size(max = ProductInfoRequest.MAX_LINES, message = "장바구니 줄은 한 번에 100개까지입니다.")
    private List<ProductInfoDto> products;
 }
