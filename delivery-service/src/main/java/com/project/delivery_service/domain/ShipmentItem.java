package com.project.delivery_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이 상자에 무엇이 몇 개 들었는가.
 *
 * <p>분할 배송에서 필수다. 상자만 나누고 내용물을 기록하지 않으면 사용자는 "먼저 온 상자에
 * 뭐가 들어 있는지" 알 수 없다 — 아마존이 패키지별로 상품 목록을 보여주는 이유다.
 *
 * <p><b>상품명을 복사해 둔다.</b> catalog-service 를 조회하지 않는 이유는 두 가지다:
 * 배송 이력은 몇 년 뒤에도 조회되는데 그때 상품이 삭제됐거나 이름이 바뀌었을 수 있고,
 * 배송 조회 화면이 카탈로그 장애에 끌려 들어갈 이유도 없다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "shipment_item", schema = "delivery",
       indexes = @Index(name = "idx_shipment_item_shipment", columnList = "shipment_id"))
public class ShipmentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shipment_item_id")
    private Long shipmentItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "order_product_id")
    private Long orderProductId;

    @Column(name = "product_id")
    private Long productId;

    /** 주문 시점의 상품명 스냅샷. */
    @Column(name = "product_name", length = 300)
    private String productName;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** 주문 시점의 SKU 스냅샷(V4) — 창고는 이름이 아니라 이 코드로 집는다. SKU 가 없던 주문이면 null. */
    @Column(name = "sku_code", length = 96)
    private String skuCode;

    public ShipmentItem(Shipment shipment, Long orderProductId, Long productId,
                        String productName, String imageUrl, int quantity, String skuCode) {
        this.skuCode = skuCode;
        this.shipment = shipment;
        this.orderProductId = orderProductId;
        this.productId = productId;
        this.productName = productName;
        this.imageUrl = imageUrl;
        this.quantity = quantity;
    }
}
