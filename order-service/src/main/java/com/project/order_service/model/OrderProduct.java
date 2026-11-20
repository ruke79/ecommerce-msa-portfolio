package com.project.order_service.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.swing.text.AttributeSet.ColorAttribute;

import com.fasterxml.jackson.annotation.JsonBackReference;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.AccessLevel;

@Getter
@Setter
@Entity
@SuperBuilder // @Builder 대신 @SuperBuilder 사용 ／ [JA] @Builder の代わりに @SuperBuilder を使用
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="order_product", schema = "orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_order_product_orderProductId", columnNames = {"order_product_id"})
        },
        indexes = {
        @Index(columnList = "name, size, productId, price", name = "idx_order_product") })
public class OrderProduct extends BaseEntity {
        
        @Id @Tsid        
        @Column(name = "order_product_id", unique = true)
        private Long  orderProductId; 

        private String name;

        private String image;

        private String size;

        /**
         * 이 품목의 SKU — 사이즈 한 줄의 재고 단위 코드(catalog V17). 주문 시점의 값을 복사해 둔다(V24).
         * 식별자가 아니라 기록이다 — 재고 이동은 여전히 (상품, 색상, 사이즈 이름)으로 한다.
         *
         * <p>[JA] この品目の SKU — サイズ 1 行の在庫単位コード(catalog V17)。注文時点の値をコピーしておく(V24)。
         * 識別子ではなく記録だ — 在庫の移動は依然として(商品, 色, サイズ名)で行う。
         */
        @Column(name = "sku_code", length = 96)
        private String skuCode;

        private int qty;

        
        private Long colorId;

        private BigDecimal price;       
        
        private Long productId;

        @JsonBackReference // 부모인 Order로의 순환 참조를 끊습니다. ／ [JA] 親の Order への循環参照を断つ。
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "order_id", referencedColumnName = "order_id", nullable = false)
        private Order order;

        /**
         * 부분 환불·반품의 수량(V9). 넷 다 0 에서 시작해 {@code qty} 를 넘지 않는다.
         *
         * <ul>
         *   <li>{@code refundedQty} — 돈이 돌아간 수</li>
         *   <li>{@code returnedQty} — 물건이 돌아온 수. <b>발송 전에 환불된 수량도 든다</b>
         *       (창고를 떠나지 않았으니 이미 돌아와 있다)</li>
         *   <li>{@code restockedQty} — 재고로 되돌린 수(catalog 에 복원 이벤트를 보낸 수)</li>
         *   <li>{@code discardedQty} — 파손 등으로 재고에 넣지 않기로 한 수</li>
         *   <li>{@code shortQty} — 결제됐지만 재고가 모자라 <b>배정되지 못한</b> 수(V21, docs/53). 차감된 적이 없으므로
         *       돌아온 수·복원할 수·보낼 수에서 빠진다. 환불은 이 수를 먼저 소진한다</li>
         * </ul>
         *
         * <p>[JA] 部分返金・返品の数量(V9)。どれも 0 から始まり {@code qty} を超えない。
         * {@code refundedQty} — お金が戻った数 / {@code returnedQty} — 品物が戻った数。<b>発送前に返金された数量も含む</b>(倉庫を出ていないのですでに戻っている)
         * / {@code restockedQty} — 在庫に戻した数(catalog に復元イベントを送った数) / {@code discardedQty} — 破損などで在庫に入れないことにした数
         * / {@code shortQty} — 決済されたが在庫が足りず <b>割り当てられなかった</b> 数(V21, docs/53)。減算されたことがないので
         * 戻った数・復元する数・送る数から除かれる。返金はこの数を先に消化する。
         */
        @Column(nullable = false)
        private int refundedQty;

        @Column(nullable = false)
        private int returnedQty;

        @Column(nullable = false)
        private int restockedQty;

        @Column(nullable = false)
        private int discardedQty;

        @Column(nullable = false)
        private int shortQty;

        /** 아직 환불되지 않은 수. */
        // [JA] まだ返金されていない数。
        public int remainingToRefund() {
                return Math.max(0, qty - refundedQty);
        }

        /** 아직 돌아오지 않은 수 — 반품 입고로 기록할 수 있는 최대. 배정되지 못한 수는 나간 적이 없다. */
        // [JA] まだ戻っていない数 — 返品入庫として記録できる最大。割り当てられなかった数は出たことがない。
        public int remainingToReturn() {
                return Math.max(0, qty - shortQty - returnedQty);
        }

        /** 실제로 배정된(재고에서 빠진) 수 — 보낼 수의 상한. */
        // [JA] 実際に割り当てられた(在庫から引かれた)数 — 送る数の上限。
        public int allocatedQty() {
                return Math.max(0, qty - shortQty);
        }

        /**
         * 환불된 수 중 <b>배정된</b> 몫 — 물건이 돌아와야(또는 창고에 있어야) 하는 수. 환불은 배정되지 못한 수를 먼저
         * 소진한다: 모자란 1개를 환불받았다면 그것은 창고에 없는 1개다.
         *
         * <p>[JA] 返金された数のうち <b>割り当てられた</b> 分 — 品物が戻るべき(または倉庫にあるべき)数。返金は割り当てられなかった数を先に
         * 消化する: 足りない 1 個を返金されたなら、それは倉庫にない 1 個だ。
         */
        public int refundedAllocatedQty() {
                return Math.max(0, refundedQty - shortQty);
        }

        /** 돌아왔지만 아직 재고로도 폐기로도 처리되지 않은 수 — 복원 이벤트에 실을 수. */
        // [JA] 戻ったがまだ在庫にも廃棄にも処理されていない数 — 復元イベントに載せる数。
        public int pendingRestock() {
                return Math.max(0, returnedQty - restockedQty - discardedQty);
        }

}
