package com.project.delivery_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.common.dto.order.OrderShippingDto;
import com.project.delivery_service.client.OrderServiceClient;
import com.project.delivery_service.domain.Carrier;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryStatus;
import com.project.delivery_service.domain.Shipment;
import com.project.delivery_service.dto.RegisterTrackingRequest;
import com.project.delivery_service.publisher.DeliveryStatusPublisher;
import com.project.delivery_service.repository.CarrierRepository;
import com.project.delivery_service.repository.DeliveryRepository;
import com.project.delivery_service.repository.ShipmentRepository;

/**
 * 송장 등록 · 배송 생성의 상태 검사 — 끝난 배송을 되살리지 않고, 결제되지 않았거나 환불된 주문에 배송을 만들지 않는다.
 *
 * <p>[JA] 送り状登録・配送作成の状態チェック — 終わった配送を生き返らせず、未決済・返金済みの注文に配送を作らない。
 */
@DisplayName("송장 등록 · 배송 생성 — 상태 검사")
class DeliveryGuardsTest {

    private DeliveryRepository deliveryRepository;
    private DeliveryStatusPublisher publisher;
    private DeliveryService service;
    private Delivery delivery;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(DeliveryRepository.class);
        CarrierRepository carriers = mock(CarrierRepository.class);
        when(carriers.findById("SIMULATED")).thenReturn(Optional.of(new Carrier("SIMULATED", "테스트", "Test", null, true)));
        publisher = mock(DeliveryStatusPublisher.class);
        service = new DeliveryService(deliveryRepository, mock(ShipmentRepository.class), carriers, publisher);
        ReflectionTestUtils.setField(service, "originCountry", "KR");
        when(deliveryRepository.saveAndFlush(any(Delivery.class))).thenAnswer(i -> i.getArgument(0));

        delivery = Delivery.builder().orderId(1L).customerId(10L).recipientName("홍길동")
                .addressLine1("서울 강남구 테헤란로 1").country("KR").build();
        delivery.addShipment(false);
        when(deliveryRepository.findByOrderId(1L)).thenReturn(Optional.of(delivery));
    }

    private static RegisterTrackingRequest tracking(Integer sequenceNo) {
        return new RegisterTrackingRequest("SIMULATED", "SIM-X", sequenceNo, null, null);
    }

    @Test
    @DisplayName("🔴 취소된 배송에는 송장을 달지 않는다 — 예전엔 주문이 다시 DISPATCHED 가 돼 환불이 막혔다")
    void cancelledDeliveryRejectsTracking() {
        delivery.cancel();

        assertThatThrownBy(() -> service.registerTracking(1L, tracking(null))).isInstanceOf(TrackingRejectedException.class);
        assertThatThrownBy(() -> service.registerTracking(1L, tracking(1))).isInstanceOf(TrackingRejectedException.class);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("🔴 상자 번호로 배달 완료 · 이동 중인 상자를 접수로 되돌리지 않는다")
    void dispatchedShipmentIsNotOverwritten() {
        Shipment box = delivery.getShipments().get(0);
        box.assignTracking("SIMULATED", "SIM-1", true);
        box.advanceTo(DeliveryStatus.DELIVERED, LocalDateTime.now());

        assertThatThrownBy(() -> service.registerTracking(1L, tracking(1))).isInstanceOf(TrackingRejectedException.class);
        assertThat(box.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("번호 없이 등록하면 발송 전 상자에 — 없으면 새 상자(분할 배송)")
    void unnumberedTrackingGoesToAPendingBox() {
        Optional<Shipment> first = service.registerTracking(1L, tracking(null));
        assertThat(first).isPresent();
        assertThat(first.get().getSequenceNo()).isEqualTo(1);

        Optional<Shipment> second = service.registerTracking(1L, tracking(null));
        assertThat(second.get().getSequenceNo()).isEqualTo(2);
    }

    private static OrderShippingDto order(String status) {
        return new OrderShippingDto(1L, 10L, status, new OrderShippingDto.Address("홍길동", "010-1234-5678", "06236",
                "서울 강남구 테헤란로 1", null, "서울", "서울", "KR"), List.of());
    }

    @Test
    @DisplayName("🔴 관리자 복구는 결제됐고 배송 전인 주문만 — 미결제 · 환불된 주문에는 만들지 않는다")
    void backfillRequiresAPaidOrder() {
        DeliveryService deliveries = mock(DeliveryService.class);
        OrderServiceClient orders = mock(OrderServiceClient.class);
        DeliveryBackfillService backfill = new DeliveryBackfillService(deliveries, orders);

        for (String status : new String[] {"REFUNDED", "CANCELED", "NOT_PROCESSED", "DELIVERED"}) {
            when(orders.shipping(anyLong())).thenReturn(order(status));
            assertThat(backfill.createFromOrder(1L)).as(status).isEmpty();
        }
        verify(deliveries, never()).createFromOrder(any());

        when(orders.shipping(anyLong())).thenReturn(order("PAID"));
        backfill.createFromOrder(1L);
        verify(deliveries).createFromOrder(any());
    }
}
