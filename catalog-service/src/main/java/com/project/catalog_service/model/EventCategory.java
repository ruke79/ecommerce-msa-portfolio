package com.project.catalog_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 기획전의 대상 <b>카테고리</b> 하나와 그 할인율.
 *
 * <p><b>왜 상품 단위만으로는 부족한가.</b> "여성 의류 전체 20%" 를 상품 단위로 하려면 그
 * 안의 상품을 하나씩 담아야 하고, 한 기획전 500개 상한에 금방 닿는다. 더 나쁜 것은
 * <b>기획전이 시작된 뒤 등록된 상품이 빠진다</b>는 점이다 — 관리자는 카테고리를 걸었다고
 * 생각하는데 신상품만 정가로 남고, 그 사실이 드러나는 것은 고객 문의로다.
 *
 * <p>카테고리는 <b>조회 시점에</b> 상품으로 펼친다({@code EventPricing}). 그래서 나중에
 * 들어온 상품도 자동으로 포함된다 — 목록을 저장해 두면 그 순간 다시 굳는다.
 *
 * <p>{@link EventProduct} 와 같은 이유로 {@code @Embeddable} 이다: 기획전을 떠나서는
 * 의미가 없고, 아무도 이 행을 가리키지 않는다.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class EventCategory {

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** 퍼센트(0~100). {@code ProductSku.discount} 와 같은 단위여야 한다. */
    @Column(name = "discount_rate", nullable = false)
    private int discountRate;
}
