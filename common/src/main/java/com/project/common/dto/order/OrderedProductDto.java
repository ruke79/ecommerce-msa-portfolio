package com.project.common.dto.order;


import java.math.BigDecimal;

import com.project.common.dto.ProductColorDto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class OrderedProductDto {

    private String id;

     private String name;

     private String image;

     private String size;

    /** 사이즈 한 줄의 SKU(catalog V17) — 주문 시점 값. 이전 주문은 null. ／ [JA] サイズ一行の SKU(catalog V17) — 注文時点の値。以前の注文は null。 */
    private String skuCode;

     private int qty;
        
     private ProductColorDto color;

    private BigDecimal price;

    /** 부분 환불·반품(docs/49 §10). 환불된 수량과 돌아온 수량 — 화면이 "남은 수량" 을 그린다. ／ [JA] 部分返金 · 返品(docs/49 §10)。返金された数量と戻ってきた数量 — 画面が「残り数量」を描く。 */
    private int refundedQty;

    private int returnedQty;

}
