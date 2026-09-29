package com.project.common.dto;


import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductSizeDto {

    private String id;

    private String size;

    /**
     * 이 사이즈 줄의 SKU — 재고를 따로 세는 단위의 코드(catalog V17). 스타일 코드({@code ProductSkuDto.sku})와 다르다.
     * 이 필드가 없던 때 캐시된 DTO 에는 null 이다 — 캐시가 갱신되면 채워진다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String skuCode;

    private int quantity;

    private BigDecimal price;

    /**
     * 실물 중 다른 주문이 결제 전 예약으로 잡아 둔 수(docs/59 §5). {@link #quantity} 는 이미 이만큼 뺀 값이다 — 응답 직전에
     * 채운다. 캐시된 DTO 에는 늘 0 이고, 0 이면 JSON 에 싣지 않는다: 이 필드를 모르는 옛 파드가 캐시를 읽다 깨지지 않게.
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int onHold;

}
