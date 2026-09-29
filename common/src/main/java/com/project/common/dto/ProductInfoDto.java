package com.project.common.dto;

import java.util.List;



import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Data
@Builder
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


    public ProductInfoDto(ProductInfoDto src) {
        this.id = src.getId();
        this.name = src.getName();
        this.style = src.getStyle();
        this.brand = src.getBrand();
        this.slug = src.getSlug();
        this.sku = src.getSku();
        this.category = src.getCategory();
        this.subCategories = src.getSubCategories();
        this.description = src.getDescription();
        this.color = src.getColor();
        this.size = src.getSize();
        this.details = src.getDetails();
        this.qty = src.getQty();
        this.quantity = src.getQuantity();
        this.onHold = src.getOnHold();
        this.discount = src.getDiscount(); 
        this.images = src.getImages();
        this.questions = src.getQuestions();
        this.shipping = src.getShipping();
        this.price = src.getPrice();
        this.priceBefore = src.getPriceBefore();
        this._uid = src.get_uid();
    }

}
