package com.project.common.dto.catalog.request;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 상품 기본 정보 부분 수정. null/빈 필드는 건드리지 않는다.
 *
 * <p>여기 없는 것들과 그 이유:
 * <ul>
 *   <li>slug — 상세 URL 이라 바꾸면 기존 링크가 전부 죽는다(서비스 주석 참조)
 *   <li>category/subcategories — 연관 재배선은 단순 필드 수정과 실패 방식이 달라
 *       별도 요청으로 분리하는 편이 안전하다(지금은 미지원)
 *   <li>이미지·색상·사이즈 — SKU 수준이라 상품 수정이 아니라 변형 수정이다
 * </ul>
 */
@Setter
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductEditRequest {

    private String name;
    private String description;
    private String brand;
    private String shippingFee;
    private String refundPolicy;
}
