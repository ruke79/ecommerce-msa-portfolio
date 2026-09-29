package com.project.catalog_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 기획전의 대상 상품 하나와 그 할인율.
 *
 * <p><b>엔티티가 아니라 {@code @Embeddable} 인 이유.</b> 이 행은 <b>스스로의 정체가
 * 없다</b> — "7번 기획전의 42번 상품"일 뿐이고, 기획전을 떠나서는 의미가 없으며 다른
 * 무엇도 이 행을 가리키지 않는다. 엔티티로 만들면 자기 id 와 생명주기가 생기고, 기획전을
 * 지울 때 이 행이 남지 않도록 cascade·orphanRemoval 을 정확히 걸어야 한다. 값 타입이면
 * 그 문제가 애초에 없다 — 컬렉션에서 빠지면 행도 사라진다.
 *
 * <p>대상 목록을 통째로 갈아 끼우는 것이 이 컬렉션의 유일한 수정 방식이기도 하다(관리자
 * 화면이 대상 전체를 보내 온다). 값 타입 컬렉션의 delete-and-reinsert 동작이 그것과
 * 정확히 맞는다.
 *
 * <p>{@code productId} 를 {@code Product} 연관이 아니라 <b>id 값</b>으로 두는 것도
 * 의도적이다. 이 테이블은 {@code content} 스키마에 있고 상품은 {@code product} 스키마에
 * 있다. 스키마를 가로지르는 FK 를 걸면 "content 를 언젠가 떼어낼 수 있게 나눠 둔" 경계가
 * DB 제약으로 굳는다(V9·V11 주석).
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class EventProduct {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /**
     * 할인율. <b>퍼센트</b>(0~100)다.
     *
     * <p>{@code ProductSku.discount} 와 같은 단위여야 한다 — 하나는 퍼센트, 하나는 비율로
     * 두면 합성하는 쪽에서 반드시 틀린다.
     */
    @Column(name = "discount_rate", nullable = false)
    private int discountRate;
}
