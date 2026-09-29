package com.project.delivery_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryStatus;
import com.project.delivery_service.domain.Shipment;
import com.project.delivery_service.publisher.DeliveryStatusPublisher;
import com.project.delivery_service.repository.CarrierRepository;
import com.project.delivery_service.repository.DeliveryRepository;
import com.project.delivery_service.repository.ShipmentRepository;

/** 결제 취소·환불이 배송에 미치는 영향 — 발송 전에만 접는다. */
@DisplayName("delivery-service — 결제 취소로 배송 접기")
class DeliveryCancelByOrderTest {

    private DeliveryRepository deliveryRepository;
    private DeliveryStatusPublisher publisher;
    private DeliveryService service;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(DeliveryRepository.class);
        publisher = mock(DeliveryStatusPublisher.class);
        service = new DeliveryService(deliveryRepository, mock(ShipmentRepository.class),
                mock(CarrierRepository.class), publisher);
        when(deliveryRepository.saveAndFlush(any(Delivery.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static Delivery delivery() {
        return Delivery.builder().orderId(1L).customerId(10L).recipientName("홍길동")
                .addressLine1("서울 강남구 테헤란로 1").country("KR").build();
    }

    @Test
    @DisplayName("송장 전이면 취소되고 CANCELLED 가 발행된다 — 소유권 검사는 없다")
    void beforeDispatchIsCancelled() {
        Delivery d = delivery();
        d.addShipment(false);
        when(deliveryRepository.findByOrderId(1L)).thenReturn(Optional.of(d));

        assertThat(service.cancelByOrder(1L)).isTrue();

        assertThat(d.overallStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        verify(publisher).publish(eq(d), eq(DeliveryStatus.CANCELLED));
    }

    @Test
    @DisplayName("이미 집화된 배송은 건드리지 않는다 — 반품 절차다")
    void afterDispatchIsLeftAlone() {
        Delivery d = delivery();
        Shipment s = d.addShipment(false);
        s.assignTracking("SIMULATED", "SIM-1", true);
        s.advanceTo(DeliveryStatus.PICKED_UP, null);
        when(deliveryRepository.findByOrderId(1L)).thenReturn(Optional.of(d));

        assertThat(service.cancelByOrder(1L)).isFalse();

        assertThat(d.overallStatus()).isEqualTo(DeliveryStatus.PICKED_UP);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("배송이 없거나 이미 취소돼 있으면 조용히 false — 재전달에 안전하다")
    void missingOrAlreadyCancelledIsNoop() {
        when(deliveryRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        assertThat(service.cancelByOrder(1L)).isFalse();

        Delivery cancelled = delivery();
        cancelled.addShipment(false);
        cancelled.cancel();
        when(deliveryRepository.findByOrderId(2L)).thenReturn(Optional.of(cancelled));
        assertThat(service.cancelByOrder(2L)).isFalse();

        verify(publisher, never()).publish(any(), any());
    }
}
