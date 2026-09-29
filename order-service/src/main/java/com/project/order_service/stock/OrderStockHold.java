package com.project.order_service.stock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;
import com.project.order_service.model.Order;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 전 재고 예약 — order 쪽 (docs/59). 재고는 catalog 가 잡는다. 여기는 언제 잡고 언제 푸는지다.
 *
 * <ul>
 *   <li><b>주문 생성</b> — 주문을 저장한 직후 잡는다(주문 id 가 예약의 키다 — 결제 확정 차감의 {@code order-<id>} 와 같다).
 *       모자라면 방금 만든 주문을 버리고 이유를 돌려준다.</li>
 *   <li><b>무통장 입금 시작</b> — 입금 기한까지 늘린다. 그새 모자라게 됐으면 입금 안내를 내지 않는다 — 없는 물건에 돈을
 *       보내게 하지 않는다.</li>
 *   <li><b>취소</b>(PayPal 취소·무통장 취소·입금 기한 만료·결제 실패) — 푼다. 실패해도 catalog 의 만료가 푼다.</li>
 * </ul>
 *
 * <p><b>catalog 에 닿지 못하면 막지 않는다</b>(fail-open). 예약은 초과 판매를 줄이는 장치고, 그래도 생긴 초과 판매는 결제 뒤
 * 차감의 부족분 자동 환불(docs/53 §7)이 받는다. catalog 가 잠깐 흔들린다고 주문을 전부 막으면 그 손해가 더 크다. 대신 세고
 * ({@code order_stock_hold_total{result="unavailable"}}) 경고를 남긴다.
 *
 * <p>원격 호출이라 트랜잭션 밖에서 부른다(docs/37). 트랜잭션 안에서 풀어야 할 때는 {@link #releaseAfterCommit}.
 *
 * <p>[JA] 決済前の在庫の仮押さえ — order 側 (docs/59)。在庫を押さえるのは catalog である。ここはいつ押さえ、いつ解放するかを扱う。
 *
 * <ul>
 *   <li><b>注文作成</b> — 注文を保存した直後に押さえる(注文 id が予約のキーである — 決済確定時の減算の {@code order-<id>} と同じ)。
 *       足りなければ作ったばかりの注文を破棄し、理由を返す。</li>
 *   <li><b>銀行振込の開始</b> — 入金期限まで延長する。その間に足りなくなっていたら入金案内を出さない — 無い商品に
 *       お金を振り込ませない。</li>
 *   <li><b>キャンセル</b>(PayPal キャンセル・銀行振込キャンセル・入金期限切れ・決済失敗) — 解放する。失敗しても catalog 側の期限切れが解放する。</li>
 * </ul>
 *
 * <p><b>catalog に到達できなくても止めない</b>(fail-open)。予約は売り越しを減らすための仕組みであり、それでも発生した売り越しは
 * 決済後の減算での不足分自動返金(docs/53 §7)が受け止める。catalog が一時的に揺らいだだけで注文をすべて止める方が損害は大きい。
 * その代わりカウントし({@code order_stock_hold_total{result="unavailable"}})、警告を残す。
 *
 * <p>リモート呼び出しなのでトランザクションの外で呼ぶ(docs/37)。トランザクション内で解放する必要がある時は {@link #releaseAfterCommit}。
 */
@Slf4j
@Component
public class OrderStockHold {

    /** 결과의 종류. */
    // [JA] 結果の種類。
    public enum Kind {
        /** 전부 잡았다. */
        // [JA] すべて押さえた。
        HELD,
        /** 모자라 아무것도 잡지 않았다. */
        // [JA] 足りず、何も押さえなかった。
        SHORT,
        /** catalog 에 닿지 못했다 — 예약 없이 간다. */
        // [JA] catalog に到達できなかった — 予約なしで進める。
        UNAVAILABLE,
        /** 기능이 꺼져 있다. */
        // [JA] 機能が無効になっている。
        DISABLED
    }

    public record Outcome(Kind kind, LocalDateTime holdUntil, List<StockReservationResult.Shortage> shortages) {
        public boolean isShort() {
            return kind == Kind.SHORT;
        }

        public boolean isHeld() {
            return kind == Kind.HELD;
        }
    }

    private final StockReservationPort catalog;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final Duration checkoutTtl;

    public OrderStockHold(StockReservationPort catalog, MeterRegistry meters,
            @Value("${order.stock-hold.enabled:true}") boolean enabled,
            @Value("${order.stock-hold.checkout-minutes:30}") long checkoutMinutes) {
        this.catalog = catalog;
        this.meters = meters;
        this.enabled = enabled;
        this.checkoutTtl = Duration.ofMinutes(Math.max(1, checkoutMinutes));
    }

    /** 카드·간편결제의 기본 — 결제 화면에서 결제를 마칠 시간. */
    // [JA] カード・簡易決済のデフォルト — 決済画面で決済を終えるまでの時間。
    public Duration checkoutTtl() {
        return checkoutTtl;
    }

    /** 주문의 모든 줄을 {@code ttl} 동안 잡는다. 이미 잡혀 있으면 수량·만료를 새로 쓴다(연장). */
    // [JA] 注文のすべての行を {@code ttl} の間押さえる。すでに押さえてあれば数量・期限を上書きする(延長)。
    public Outcome hold(Order order, Duration ttl) {
        if (!enabled) {
            return new Outcome(Kind.DISABLED, null, List.of());
        }
        StockReservationRequest request = StockReservationRequest.builder()
                .orderId(order.getOrderId())
                .ttlSeconds(ttl.toSeconds())
                .lines(order.getOrderedProducts().stream()
                        .map(p -> StockReservationRequest.Line.builder()
                                .productId(p.getProductId()).colorId(p.getColorId())
                                .size(p.getSize()).qty(p.getQty()).build())
                        .toList())
                .build();
        StockReservationResult result;
        try {
            result = catalog.reserve(request);
        } catch (RuntimeException e) {
            count("unavailable");
            log.warn("재고 예약 불가 — catalog 에 닿지 못해 예약 없이 진행한다(결제 뒤 부족분 환불이 받는다). orderId={} transport={} ({})",
                    order.getOrderId(), catalog.transport(), e.getClass().getSimpleName());
            return new Outcome(Kind.UNAVAILABLE, null, List.of());
        }
        if (result == null) {
            count("unavailable");
            return new Outcome(Kind.UNAVAILABLE, null, List.of());
        }
        if (!result.isHeld()) {
            count("short");
            return new Outcome(Kind.SHORT, null, result.getShortages() == null ? List.of() : result.getShortages());
        }
        count("held");
        Instant until = result.getExpiresAt();
        return new Outcome(Kind.HELD, until == null ? null : LocalDateTime.ofInstant(until, ZoneId.systemDefault()), List.of());
    }

    /** 푼다 — 실패해도 던지지 않는다(catalog 의 만료가 푼다). */
    // [JA] 解放する — 失敗しても例外は投げない(catalog 側の期限切れが解放する)。
    public void release(Long orderId) {
        if (!enabled || orderId == null) {
            return;
        }
        try {
            catalog.release(orderId);
        } catch (RuntimeException e) {
            log.warn("재고 예약 해제 실패 — 만료 시각에 catalog 가 푼다. orderId={} ({})", orderId, e.getClass().getSimpleName());
        }
    }

    /** 트랜잭션 안이면 커밋된 뒤에 푼다 — 롤백된 취소로 재고를 풀면 안 된다. 밖이면 바로. */
    // [JA] トランザクション内ならコミット後に解放する — ロールバックされたキャンセルで在庫を解放してはならない。外ならすぐに。
    public void releaseAfterCommit(Long orderId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    release(orderId);
                }
            });
        } else {
            release(orderId);
        }
    }

    private void count(String result) {
        meters.counter("order_stock_hold_total", "result", result).increment();
    }
}
