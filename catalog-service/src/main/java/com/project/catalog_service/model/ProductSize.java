package com.project.catalog_service.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonBackReference;

import io.hypersistence.utils.hibernate.id.Tsid;
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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@Entity
@SuperBuilder // @Builder 대신 @SuperBuilder 사용 ／ [JA] @Builder の代わりに @SuperBuilder を使う
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="product_size",  schema="product",
        indexes = {
        @Index(columnList = "size", name = "idx_product_size") })
public class ProductSize extends BaseEntity { 

    

    @Id //@Tsid
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "size_id")
    private Long sizeId;

    private String size;

    private int quantity;

    private BigDecimal price;

    /**
     * 이 줄의 SKU — 재고를 따로 세는 단위의 코드(예: {@code FAS-100042-XL}). 스타일(색상) 코드는
     * {@link ProductSku#getSku()} 이고, 이것은 그 아래 사이즈 한 줄의 코드다.
     *
     * <p>애플리케이션은 이 값을 <b>쓰지 않는다.</b> 규칙은 DB 함수 {@code product.assign_size_sku_codes()}
     * (V17) 한 곳에 있고, 저장 뒤 그 함수를 불러 채운다({@link com.project.catalog_service.repository.jpa.data.ProductSizeRepository#assignSkuCodes()}).
     * 한 번 정해지면 바뀌지 않는다 — 과거 주문 · 반품이 이 코드를 들고 있다.
     *
     * <p>[JA] この行の SKU — 在庫を別に数える単位のコード(例: FAS-100042-XL)。スタイル(色)のコードは ProductSku#getSku() で、これはその下のサイズ一行のコードだ。
     * アプリケーションはこの値を書かない。規則は DB 関数 product.assign_size_sku_codes()(V17)一か所にあり、保存後にその関数を呼んで埋める
     * (ProductSizeRepository#assignSkuCodes())。一度決まったら変わらない — 過去の注文・返品がこのコードを持っている。
     */
    @Column(name = "sku_code", length = 96, insertable = false, updatable = false)
    private String skuCode;

    @JsonBackReference // ProductSku로의 순환 참조를 끊습니다. ／ [JA] ProductSku への循環参照を断つ。
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="skuproduct_id", referencedColumnName = "skuproduct_id", nullable=true)
    private ProductSku sku;
}
