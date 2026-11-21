package com.project.common.dto.catalog.request;

import java.util.List;

import com.project.common.dto.ProductInfoDto;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class ProductInfoRequest {

    /** 한 요청의 장바구니 줄 상한. [JA] 一回の要求のカート行の上限。 */
    public static final int MAX_LINES = 100;

    // 한 요청의 줄 수 상한 — 줄마다 catalog 조회가 붙는다. 🐛 2026-11-21 까지 제한이 없었다(docs/93 ③ 3단계 · docs/38 §2.5).
    // [JA] 一回の要求の行数の上限 — 行ごとに catalog の照会が付く。以前は制限がなかった。
    @jakarta.validation.constraints.Size(max = MAX_LINES, message = "장바구니 줄은 한 번에 100개까지입니다.")
    private List<ProductInfoDto> products;
}
