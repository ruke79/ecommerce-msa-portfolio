package com.project.cart_service.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonManagedReference;

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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;

@Getter
@Setter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// 사용자당 하나(V5) — 예전 일반 인덱스로는 처음 담기가 동시에 일어나면 장바구니가 둘 생겼다(CartService.cartOf).
@Table(name="cart", schema = "cart",
       indexes = {
        @Index(columnList = "user_id", name = "uq_cart_user", unique = true) }
)
public class Cart extends BaseEntity {

    @Id @Tsid
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="cart_id")
    private Long cartId;

    @JsonManagedReference
    @OneToMany(mappedBy = "cart", fetch = FetchType.LAZY,
    // MERGE 도 — 예전 PERSIST 뿐이라, 이미 저장된 장바구니에 줄을 붙이고 merge 로 저장하면 새 줄이 조용히 빠졌다
    // (실제로 "담기가 안 되는" 버그였다 — CartService 가 새 줄을 직접 save 해서 막는다. 그 우회를 빼먹는 새 코드도 안전하게).
    cascade = {CascadeType.PERSIST, CascadeType.MERGE}, targetEntity = CartProduct.class, orphanRemoval = true)
    private List<CartProduct> cartProducts = new ArrayList<>();


    private BigDecimal cartTotal;
    private BigDecimal totalAfterDiscount;

    // @OneToOne(fetch=FetchType.cade=CascadeType.PERSIST, targetEntity=User.class)
    // @JoinColumn(name="user_id", referencedColumnName = "user_id", nullable = false)
    // private User user;
    private Long userId;

    public Cart(Long userId) {
        this.userId = userId;
    }
}
