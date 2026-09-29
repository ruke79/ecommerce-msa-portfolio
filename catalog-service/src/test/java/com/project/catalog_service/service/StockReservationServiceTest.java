package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.project.catalog_service.model.ProductSize;
import com.project.catalog_service.model.ProductSku;
import com.project.catalog_service.model.StockReservation;
import com.project.catalog_service.repository.jpa.data.ProductSizeRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.repository.jpa.data.StockReservationRepository;
import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;

/**
 * 결제 전 재고 예약 (docs/59).
 */
@DisplayName("StockReservationService — 결제 전 재고 예약")
class StockReservationServiceTest {

    StockReservationRepository reservations = mock(StockReservationRepository.class);
    ProductSkuRepository skus = mock(ProductSkuRepository.class);
    ProductSizeRepository sizes = mock(ProductSizeRepository.class);
    StockReservationService service;
    Instant now = Instant.parse("2026-09-25T01:00:00Z");
    List<StockReservation> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new StockReservationService(reservations, skus, sizes);
        service.setClock(Clock.fixed(now, ZoneOffset.UTC));
        when(reservations.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
        when(reservations.findByOrderId(anyLong())).thenReturn(List.of());
    }

    /** 옵션 하나의 실물 수량. */
    private void stock(long productId, long colorId, String size, int qty) {
        ProductSku sku = mock(ProductSku.class);
        when(sku.getSkuproductId()).thenReturn(productId * 100 + colorId);
        when(skus.findForStockUpdate(productId, size, colorId)).thenReturn(Optional.of(sku));
        ProductSize ps = mock(ProductSize.class);
        when(ps.getQuantity()).thenReturn(qty);
        when(sizes.findFirstBySkuSkuproductIdAndSizeOrderBySizeIdAsc(productId * 100 + colorId, size)).thenReturn(Optional.of(ps));
    }

    private void heldByOthers(long productId, long colorId, String size, long qty) {
        when(reservations.heldByOthers(eq(productId), eq(colorId), eq(size), any(), any())).thenReturn(qty);
    }

    private static StockReservationRequest request(long orderId, long ttl, StockReservationRequest.Line... lines) {
        return StockReservationRequest.builder().orderId(orderId).ttlSeconds(ttl).lines(List.of(lines)).build();
    }

    private static StockReservationRequest.Line line(long productId, long colorId, String size, int qty) {
        return StockReservationRequest.Line.builder().productId(productId).colorId(colorId).size(size).qty(qty).build();
    }

    @Test
    @DisplayName("🔴 남은 수 = 실물 − 남의 살아 있는 예약 — 실물 3, 남의 예약 2 면 2개 주문은 거절")
    void othersHoldsCount() {
        stock(1, 5, "M", 3);
        heldByOthers(1, 5, "M", 2);

        StockReservationResult r = service.reserve(request(10, 1800, line(1, 5, "M", 2)));

        assertThat(r.isHeld()).isFalse();
        assertThat(r.getShortages()).singleElement().satisfies(s -> {
            assertThat(s.getRequested()).isEqualTo(2);
            assertThat(s.getAvailable()).isEqualTo(1);
        });
        verify(reservations, never()).save(any());
    }

    @Test
    @DisplayName("🔴 관리자가 품절로 내린 상품은 수량이 남아 있어도 잡지 않는다")
    void soldOutStatusIsNotAvailable() {
        stock(1, 5, "M", 10);
        com.project.catalog_service.model.Product p = org.mockito.Mockito.mock(com.project.catalog_service.model.Product.class);
        when(p.getStatus()).thenReturn("SOLD_OUT");
        when(skus.findForStockUpdate(1L, "M", 5L).get().getProduct()).thenReturn(p);

        StockReservationResult r = service.reserve(request(10, 1800, line(1, 5, "M", 1)));

        assertThat(r.isHeld()).isFalse();
        assertThat(r.getShortages()).singleElement().satisfies(s -> assertThat(s.getAvailable()).isZero());
    }

    @Test
    @DisplayName("사이즈 없는 상품 — 이름이 빈 사이즈(\"\")도 한 옵션으로 잡는다")
    void sizelessOption() {
        stock(1, 5, "", 3);

        StockReservationResult r = service.reserve(request(10, 1800, line(1, 5, "", 1)));

        assertThat(r.isHeld()).isTrue();
        assertThat(saved).singleElement().satisfies(s -> assertThat(s.getSize()).isEmpty());
    }

    @Test
    @DisplayName("충분하면 잡는다 — HELD, 만료 = 지금 + TTL")
    void holds() {
        stock(1, 5, "M", 3);
        heldByOthers(1, 5, "M", 1);

        StockReservationResult r = service.reserve(request(10, 1800, line(1, 5, "M", 2)));

        assertThat(r.isHeld()).isTrue();
        assertThat(r.getExpiresAt()).isEqualTo(now.plusSeconds(1800));
        assertThat(saved).singleElement().satisfies(s -> {
            assertThat(s.getStatus()).isEqualTo(StockReservation.Status.HELD);
            assertThat(s.getQty()).isEqualTo(2);
            assertThat(s.getOrderId()).isEqualTo(10L);
        });
    }

    @Test
    @DisplayName("🔴 전부 아니면 전무 — 한 줄이라도 모자라면 아무것도 잡지 않는다")
    void allOrNothing() {
        stock(1, 5, "M", 10);
        stock(2, 6, "L", 0);
        heldByOthers(1, 5, "M", 0);
        heldByOthers(2, 6, "L", 0);

        StockReservationResult r = service.reserve(request(10, 1800, line(1, 5, "M", 1), line(2, 6, "L", 1)));

        assertThat(r.isHeld()).isFalse();
        assertThat(r.getShortages()).extracting(StockReservationResult.Shortage::getProductId).containsExactly(2L);
        verify(reservations, never()).save(any());
    }

    @Test
    @DisplayName("같은 옵션이 두 줄로 와도 합쳐서 센다 — 1+1 은 남은 1 을 넘는다")
    void mergesSameOption() {
        stock(1, 5, "M", 1);
        heldByOthers(1, 5, "M", 0);

        assertThat(service.reserve(request(10, 1800, line(1, 5, "M", 1), line(1, 5, "M", 1))).isHeld()).isFalse();
    }

    @Test
    @DisplayName("🔴 여러 옵션은 정해진 순서로 잠근다 — 거꾸로 잠그는 두 주문의 교착을 막는다")
    void locksInStableOrder() {
        stock(2, 6, "L", 5);
        stock(1, 5, "M", 5);
        heldByOthers(1, 5, "M", 0);
        heldByOthers(2, 6, "L", 0);

        service.reserve(request(10, 1800, line(2, 6, "L", 1), line(1, 5, "M", 1)));

        InOrder order = inOrder(skus);
        order.verify(skus).findForStockUpdate(1L, "M", 5L);
        order.verify(skus).findForStockUpdate(2L, "L", 6L);
    }

    @Test
    @DisplayName("없는 상품·옵션은 남은 수 0")
    void missingOptionIsZero() {
        when(skus.findForStockUpdate(anyLong(), anyString(), anyLong())).thenReturn(Optional.empty());

        assertThat(service.reserve(request(10, 1800, line(9, 9, "M", 1))).isHeld()).isFalse();
    }

    @Test
    @DisplayName("같은 주문으로 다시 잡으면 기존 행의 수량·만료를 덮는다 — 새 행을 만들지 않는다(무통장 연장)")
    void reholdExtends() {
        stock(1, 5, "M", 3);
        heldByOthers(1, 5, "M", 0);
        StockReservation old = StockReservation.hold(10L, 1L, 5L, "M", 2, now.plusSeconds(60), now.minusSeconds(600));
        old.release(now.minusSeconds(1));   // 이미 만료돼 풀렸던 것도 다시 잡는다
        when(reservations.findByOrderId(10L)).thenReturn(List.of(old));

        StockReservationResult r = service.reserve(request(10, 48 * 3600, line(1, 5, "M", 2)));

        assertThat(r.isHeld()).isTrue();
        assertThat(old.getStatus()).isEqualTo(StockReservation.Status.HELD);
        assertThat(old.getExpiresAt()).isEqualTo(now.plus(Duration.ofHours(48)));
        verify(reservations, never()).save(any());
    }

    @Test
    @DisplayName("TTL 은 1분~72시간으로 자른다")
    void ttlClamped() {
        assertThat(StockReservationService.clampTtl(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(StockReservationService.clampTtl(3600)).isEqualTo(Duration.ofHours(1));
        assertThat(StockReservationService.clampTtl(365L * 24 * 3600)).isEqualTo(Duration.ofHours(72));
    }

    @Test
    @DisplayName("풀기·확정은 HELD 만 바꾼다 — 여러 번 불러도 같다")
    void releaseAndCommit() {
        StockReservation held = StockReservation.hold(10L, 1L, 5L, "M", 1, now.plusSeconds(60), now);
        StockReservation committed = StockReservation.hold(10L, 2L, 6L, "L", 1, now.plusSeconds(60), now);
        committed.commit(now);
        when(reservations.findByOrderId(10L)).thenReturn(List.of(held, committed));

        assertThat(service.release(10L)).isEqualTo(1);
        assertThat(held.getStatus()).isEqualTo(StockReservation.Status.RELEASED);
        assertThat(committed.getStatus()).isEqualTo(StockReservation.Status.COMMITTED);
        assertThat(service.release(10L)).isZero();

        StockReservation another = StockReservation.hold(11L, 1L, 5L, "M", 1, now.minusSeconds(5), now.minusSeconds(900));
        when(reservations.findByOrderId(11L)).thenReturn(List.of(another));
        service.commit(11L);
        assertThat(another.getStatus()).as("만료가 지났어도 아직 HELD 면 확정 — 이 주문의 몫이었다").isEqualTo(StockReservation.Status.COMMITTED);
    }

    @Test
    @DisplayName("잘못된 요청은 거절")
    void invalid() {
        assertThatThrownBy(() -> service.reserve(StockReservationRequest.builder().orderId(1L).lines(List.of()).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve(request(1, 60, line(1, 5, "M", 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제 확정 차감에 넘기는 '남의 예약' 은 자기 주문을 빼고 센다")
    void heldByOthersExcludesOwnOrder() {
        when(reservations.heldByOthers(eq(1L), eq(5L), eq("M"), eq(10L), eq(now))).thenReturn(3L);

        assertThat(service.heldByOthers(10L).heldByOthers(1L, 5L, "M")).isEqualTo(3);
        ArgumentCaptor<Long> excluded = ArgumentCaptor.forClass(Long.class);
        verify(reservations).heldByOthers(eq(1L), eq(5L), eq("M"), excluded.capture(), any());
        assertThat(excluded.getValue()).isEqualTo(10L);
    }
}
