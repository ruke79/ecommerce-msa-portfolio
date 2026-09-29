package com.project.common.dto.catalog.response;

/**
 * 상품(SKU) 생성 결과 — POST /api/product 의 응답.
 *
 * <p>예전에는 ProductSku **엔티티를 통째로** 직렬화해 돌려줬다. 문제가 둘이었다:
 * 지연 로딩 프록시가 직렬화 시점에 터질 수 있고, 정작 화면이 다음 단계(같은 상품에
 * 색상 변형 이어서 등록)에 필요한 {@code productId} 는 @JsonBackReference 에 잘려
 * **응답에 없었다.** 필요한 세 값만 명시적으로 준다.
 */
public record ProductCreatedResponse(String sku, Long productId, String slug) {
}
