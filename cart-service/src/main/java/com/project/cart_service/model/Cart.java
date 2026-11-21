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
// [JA] ユーザーあたり 1 つ(V5) — 以前の通常インデックスでは最初の追加が同時に起きるとカートが 2 つできた(CartService.cartOf)。
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
    // [JA] MERGE も — 以前は PERSIST だけだったので、既に保存済みのカートに行を付けて merge で保存すると新しい行が黙って抜けた
    //      (実際に「追加できない」バグだった — CartService が新しい行を直接 save して防いでいる。その回避を忘れる新しいコードでも安全なように)。
    cascade = {CascadeType.PERSIST, CascadeType.MERGE}, targetEntity = CartProduct.class, orphanRemoval = true)
    // 담은 순서로 고정한다 — 줄 id 는 TSID(만든 시각 순으로 커진다). 🐛 2026-11-21 운영: 수량을 바꾸면 그 상품이 목록 맨 뒤로 갔다.
    //    순서가 없으면 DB 가 돌려주는 순서인데, PostgreSQL 은 UPDATE 한 행을 새 버전으로 테이블 뒤쪽에 써서 방금 고친 줄이 뒤로 나온다
    //    (수량 PATCH 의 응답 = loadCart → 화면이 그 순서를 그대로 쓴다). 시험 CartLineOrderTest.
    // [JA] 入れた順に固定する — 行 id は TSID(作成時刻順に大きくなる)。以前は順序がなく、PostgreSQL は UPDATE した行を新しい版として
    //      テーブルの後ろに書くので、数量を変えた商品が一覧の末尾へ動いた。
    @jakarta.persistence.OrderBy("cartproductId ASC")
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
