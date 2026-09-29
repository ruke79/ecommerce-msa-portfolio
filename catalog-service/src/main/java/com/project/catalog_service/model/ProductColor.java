package com.project.catalog_service.model;

import java.util.ArrayList;
import java.util.List;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.AccessLevel;

@Entity
@Getter
@Setter
@SuperBuilder // @Builder 대신 @SuperBuilder 사용
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_color",  schema="product",
    indexes = {
        @Index(columnList = "color", name = "idx_product_color") })
public class ProductColor extends BaseEntity {

    @Id
    //@Tsid
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "color_id")
    private Long colorId;
    private String color;

    // @Lob
    // @Column(length = 1048576)
    private String colorImage;

    // @OneToMany(mappedBy = "color", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST, targetEntity = OrderedProduct.class, orphanRemoval = true)
    // private List<OrderedProduct> orderedProducts;

    // @OneToMany(mappedBy = "color", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST, targetEntity = CartProduct.class, orphanRemoval = true)
    // private List<CartProduct> cartProducts;
}
