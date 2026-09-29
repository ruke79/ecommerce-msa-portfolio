package com.project.catalog_service.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.catalog_service.model.ProductSize;
import com.project.catalog_service.model.ProductSku;
import com.project.catalog_service.model.StockReservation;
import com.project.catalog_service.repository.jpa.data.ProductSizeRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.repository.jpa.data.StockReservationRepository;
import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 전 재고 예약 (docs/59).
 *
 * <h3>흐름</h3>
 *
 * <pre>
 *  주문 생성 ── reserve(orderId, 줄들, TTL) ── 전부 잡히면 HELD, 하나라도 모자라면 아무것도 안 잡고 "모자라다"
 *  결제 확정 ── product-update(order-&lt;id&gt;) 의 차감 ── commit(orderId): HELD → COMMITTED
 *  주문 취소 ── release(orderId): HELD → RELEASED
 *  아무도 안 옴 ── 만료 잡(releaseExpired): HELD 이고 expires_at 지남 → RELEASED
 * </pre>
 *
 * <p>남은 수량 = {@code product_size.quantity} − 살아 있는 HELD 의 합. 예약은 수량을 옮기지 않는다 — 풀 때 되돌릴 차감이 없다.
 *
 * <h3>동시성</h3>
 *
 * <p>잡을 때 SKU 행을 결제 뒤 차감과 <b>같은 잠금</b>({@code findForStockUpdate}, PESSIMISTIC_WRITE)으로 쥔다 — 같은 옵션의
 * 예약·차감이 줄을 서므로 "둘 다 남은 1개를 봤다" 가 없다. 여러 옵션은 <b>정해진 순서</b>(상품·색상·사이즈)로 잠근다 — 두
 * 주문이 A·B 와 B·A 를 거꾸로 잠그면 교착이 난다.
 *
 * <p>예약이 만료된 뒤에 결제가 확정되면? 예약 없이 차감한다 — 그때 모자라면 기존 부족분 자동 환불(docs/53 §7)이 받는다.
 * 예약은 초과 판매를 <b>막는</b> 장치고, 부족분 환불은 그래도 생긴 것을 <b>메우는</b> 장치다. 둘 다 둔다.
 *
 * <p>[JA] 決済前の在庫の仮押さえ (docs/59)。
 *
 * <h3>[JA] 流れ</h3>
 *
 * <p>[JA] 上の図のラベルの意味:
 * <ul>
 *   <li>注文作成 ── reserve(orderId, 明細行, TTL) ── すべて確保できれば HELD、一つでも足りなければ何も確保せず
 *       「足りない」と返す</li>
 *   <li>決済確定 ── product-update(order-&lt;id&gt;) による減算 ── commit(orderId): HELD → COMMITTED</li>
 *   <li>注文取消 ── release(orderId): HELD → RELEASED</li>
 *   <li>誰も来ない ── 期限切れジョブ(releaseExpired): HELD かつ expires_at 経過 → RELEASED</li>
 * </ul>
 *
 * <p>[JA] 残数 = {@code product_size.quantity} − 生きている HELD の合計。仮押さえは数量を移動しない —
 * 解放時に戻すべき減算が無い。
 *
 * <h3>[JA] 同時実行</h3>
 *
 * <p>[JA] 確保時に SKU 行を、決済後の減算と<b>同じロック</b>({@code findForStockUpdate}, PESSIMISTIC_WRITE)で握る —
 * 同じオプションの仮押さえ・減算が順番待ちになるので「両方が残り1個を見た」は起きない。複数オプションは
 * <b>決まった順序</b>(商品・色・サイズ)でロックする — 二つの注文が A・B と B・A を逆順にロックするとデッドロックになる。
 *
 * <p>[JA] 仮押さえの期限切れ後に決済が確定したら? 仮押さえ無しで減算する — その時足りなければ、既存の不足分自動返金
 * (docs/53 §7)が受け止める。仮押さえは売り越しを<b>防ぐ</b>仕組みであり、不足分返金はそれでも生じたものを
 * <b>埋める</b>仕組みである。両方置く。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReservationService {

    /** TTL 의 하한·상한. 무통장 입금 기한(48시간)을 덮고, 실수로 영원히 잡는 일은 막는다. */
    // [JA] TTL の下限・上限。銀行振込の入金期限(48時間)をカバーしつつ、誤って永久に確保してしまうことは防ぐ。
    static final Duration MIN_TTL = Duration.ofMinutes(1);
    static final Duration MAX_TTL = Duration.ofHours(72);

    private final StockReservationRepository reservations;
    private final ProductSkuRepository skus;
    private final ProductSizeRepository sizes;
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 옵션 하나의 키 — 잠금 순서이기도 하다. */
    // [JA] オプション一つのキー — ロック順序でもある。
    record Option(Long productId, Long colorId, String size) {
        static final Comparator<Option> ORDER = Comparator.comparing(Option::productId)
                .thenComparing(Option::colorId).thenComparing(Option::size);
    }

    /**
     * 주문의 줄을 한꺼번에 잡는다 — 전부 아니면 전무. 같은 주문으로 다시 부르면 기존 예약을 새 수량·만료로 덮는다.
     *
     * <p>[JA] 注文の明細行をまとめて確保する — 全部か無か。同じ注文で再度呼ぶと、既存の仮押さえを新しい数量・期限で上書きする。
     *
     * @return 잡았으면 held=true 와 만료 시각, 모자라면 held=false 와 모자란 줄(이때는 아무것도 바꾸지 않는다)
     *         [JA] 確保できれば held=true と期限時刻、足りなければ held=false と不足した明細行(この場合は何も変更しない)
     */
    @Transactional
    public StockReservationResult reserve(StockReservationRequest request) {
        if (request == null || request.getOrderId() == null || request.getLines() == null || request.getLines().isEmpty()) {
            throw new IllegalArgumentException("예약할 주문과 줄이 필요합니다.");
        }
        Long orderId = request.getOrderId();
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(clampTtl(request.getTtlSeconds()));

        // 같은 옵션이 두 줄로 와도 한 번에 센다. 정렬된 맵 — 잠금 순서.
        // [JA] 同じオプションが二行で来ても一度にまとめて数える。ソート済みマップ — ロック順序。
        Map<Option, Integer> wanted = new TreeMap<>(Option.ORDER);
        for (StockReservationRequest.Line line : request.getLines()) {
            if (line.getProductId() == null || line.getColorId() == null || line.getSize() == null || line.getQty() <= 0) {
                throw new IllegalArgumentException("예약 줄이 올바르지 않습니다.");
            }
            wanted.merge(new Option(line.getProductId(), line.getColorId(), line.getSize()), line.getQty(), Integer::sum);
        }

        List<StockReservationResult.Shortage> shortages = new ArrayList<>();
        for (Map.Entry<Option, Integer> e : wanted.entrySet()) {
            Option o = e.getKey();
            int available = availableFor(o, orderId, now);
            if (available < e.getValue()) {
                shortages.add(StockReservationResult.Shortage.builder()
                        .productId(o.productId()).colorId(o.colorId()).size(o.size())
                        .requested(e.getValue()).available(available).build());
            }
        }
        if (!shortages.isEmpty()) {
            log.info("재고 예약 거절 — 모자람: orderId={} lines={}", orderId, shortages.size());
            return StockReservationResult.builder().held(false).shortages(shortages).build();
        }

        Map<Option, StockReservation> existing = new HashMap<>();
        for (StockReservation r : reservations.findByOrderId(orderId)) {
            existing.put(new Option(r.getProductId(), r.getColorId(), r.getSize()), r);
        }
        for (Map.Entry<Option, Integer> e : wanted.entrySet()) {
            StockReservation r = existing.remove(e.getKey());
            if (r == null) {
                Option o = e.getKey();
                reservations.save(StockReservation.hold(orderId, o.productId(), o.colorId(), o.size(), e.getValue(), expiresAt, now));
            } else if (r.getStatus() != StockReservation.Status.COMMITTED) {
                r.rehold(e.getValue(), expiresAt, now);
            }
        }
        // 이번 요청에 없는 옛 줄 — 장바구니가 바뀐 재시도. 풀어 둔다.
        // [JA] 今回のリクエストに無い古い明細行 — カートが変わった後の再試行。解放しておく。
        existing.values().stream()
                .filter(r -> r.getStatus() == StockReservation.Status.HELD)
                .forEach(r -> r.release(now));

        log.info("재고 예약: orderId={} options={} until={}", orderId, wanted.size(), expiresAt);
        return StockReservationResult.builder().held(true).expiresAt(expiresAt).shortages(List.of()).build();
    }

    /** 주문 취소 — 잡아 둔 것을 푼다. 없거나 이미 풀렸으면 아무 일도 없다(몇 번을 불러도 같다). */
    // [JA] 注文取消 — 確保しておいたものを解放する。無いか既に解放済みなら何も起きない(何度呼んでも同じ)。
    @Transactional
    public int release(Long orderId) {
        Instant now = Instant.now(clock);
        int n = 0;
        for (StockReservation r : reservations.findByOrderId(orderId)) {
            if (r.getStatus() == StockReservation.Status.HELD) {
                r.release(now);
                n++;
            }
        }
        if (n > 0) {
            log.info("재고 예약 해제: orderId={} options={}", orderId, n);
        }
        return n;
    }

    /**
     * 결제 확정 — 차감과 <b>같은 트랜잭션</b>에서 부른다(ProductUpdateMessageListener). 만료가 지났어도 아직 HELD 면 확정한다 —
     * 이 주문의 몫이었고, 차감이 이미 그 수를 가져갔다.
     *
     * <p>[JA] 決済確定 — 減算と<b>同じトランザクション</b>で呼ぶ(ProductUpdateMessageListener)。期限が過ぎていても
     * まだ HELD なら確定する — この注文の取り分であり、減算がすでにその数を持っていったからだ。
     */
    @Transactional
    public void commit(Long orderId) {
        Instant now = Instant.now(clock);
        reservations.findByOrderId(orderId).stream()
                .filter(r -> r.getStatus() == StockReservation.Status.HELD)
                .forEach(r -> r.commit(now));
    }

    /** 만료 잡이 부른다. */
    // [JA] 期限切れジョブが呼ぶ。
    @Transactional
    public int releaseExpired() {
        int n = reservations.releaseExpired(Instant.now(clock));
        if (n > 0) {
            log.info("만료된 재고 예약 해제: {}건", n);
        }
        return n;
    }

    /**
     * 결제 확정 차감이 쓰는 "남이 잡아 둔 수" — 이 주문({@code orderId})의 예약은 빼고 센다. orderId 가 없으면 모든 예약을 센다.
     *
     * <p>[JA] 決済確定の減算が使う「他人が確保している数」— この注文({@code orderId})の仮押さえは除いて数える。
     * orderId が無ければすべての仮押さえを数える。
     */
    public ProductService.HeldQuantities heldByOthers(Long orderId) {
        Instant now = Instant.now(clock);
        long exclude = orderId == null ? StockReservationRepository.NO_ORDER : orderId;
        return (productId, colorId, size) -> (int) reservations.heldByOthers(productId, colorId, size, exclude, now);
    }

    /** SKU 를 잠그고 이 주문이 쓸 수 있는 수 — 실물 − 남의 살아 있는 예약. */
    // [JA] SKU をロックしてこの注文が使える数 — 実在庫 − 他人の生きている仮押さえ。
    private int availableFor(Option o, Long orderId, Instant now) {
        Optional<ProductSku> sku = skus.findForStockUpdate(o.productId(), o.size(), o.colorId());
        if (sku.isEmpty()) {
            return 0;
        }
        // 관리자가 품절로 내린 상품은 수량이 남아 있어도 팔지 않는다 — 담기 전에 담아 둔 장바구니로 주문하는 길을 막는다.
        // [JA] 管理者が品切れにした商品は数量が残っていても売らない — 品切れ前にカートに入れておいた商品で
        //      注文する経路を塞ぐ。
        if (sku.get().getProduct() != null && "SOLD_OUT".equals(sku.get().getProduct().getStatus())) {
            return 0;
        }
        ProductSize size = sizes.findFirstBySkuSkuproductIdAndSizeOrderBySizeIdAsc(sku.get().getSkuproductId(), o.size()).orElse(null);
        if (size == null) {
            return 0;
        }
        long held = reservations.heldByOthers(o.productId(), o.colorId(), o.size(), orderId, now);
        return (int) Math.max(0, size.getQuantity() - held);
    }

    static Duration clampTtl(long seconds) {
        Duration ttl = Duration.ofSeconds(Math.max(0, seconds));
        if (ttl.compareTo(MIN_TTL) < 0) {
            return MIN_TTL;
        }
        return ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
    }
}
