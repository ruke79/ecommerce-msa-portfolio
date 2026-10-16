package com.project.common.dto;

import java.util.List;



import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Data
// 복사는 toBuilder() 로 — 예전 손으로 쓴 복사 생성자는 필드를 더할 때마다 함께 고쳐야 했고, V17 의 skuCode 를 빠뜨려
// 장바구니 응답에서 늘 비어 있었다. Lombok 이 모든 필드를 옮긴다.
// [JA] コピーは toBuilder() で — 以前の手書きのコピーコンストラクタは V17 の skuCode を漏らし、カートの応答で常に空だった。
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class ProductInfoDto {

    private String id;

    private int style;

    private String name;

    private String description;

    private String brand;

    private String slug;

    /** 스타일(색상 변형) 코드 — {@code product_sku.sku}. 재고 단위가 아니다. */
    private String sku;

    /**
     * 고른 사이즈 한 줄의 SKU — 재고를 따로 세는 단위의 코드(예: {@code FAS-100042-XL}, catalog V17).
     * 사이즈 없는 상품은 스타일 코드와 같다. 아직 코드가 없는 옛 행이면 null.
     */
    private String skuCode;

    private String shipping;

    private List<String> images;

    private ProductColorDto color;

    private String size;

    private String category;    
    private List<String> subCategories;

    private String price;

    private String priceBefore;

    private int qty;

    private int quantity;   

    /** 실물 중 다른 주문이 결제 전 예약으로 잡아 둔 수(docs/59 §5). {@code quantity} 는 이미 뺀 값이다. 0 이면 싣지 않는다. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
    private int onHold;

    private List<ProductDetailsDto> details; 
        
    private List<ProductQADto> questions;
        
    private int discount;     
    
    private String _uid;

}
