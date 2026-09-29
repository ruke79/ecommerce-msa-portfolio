package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.model.Order;
import com.project.order_service.repository.jpa.OrderAddressRepository;
import com.project.order_service.repository.jpa.OrderRepository;

/**
 * 주문 조회의 <b>소유권 검사</b> 회귀 테스트.
 *
 * <p>이 테스트가 지키려는 것은 하나다: <b>남의 주문을 읽을 수 없어야 한다.</b>
 *
 * <p>예전 구현은 {@code getOrder(orderId, email, filter)} 가 {@code email} 을 인자로 받고도
 * 쓰지 않은 채 {@code orderRepository.findById(orderId)} 만 했다. 컨트롤러에도 검사가 없어서
 * {@code GET /api/order/1}, {@code /2}, {@code /3} … 으로 번호만 훑으면 전 고객의 주문
 * 내역·배송 주소·연락처·결제 금액을 읽을 수 있었다(IDOR).
 *
 * <p>이런 종류의 결함은 <b>기능 테스트로는 절대 잡히지 않는다</b> — 자기 주문을 조회하는
 * 정상 경로는 고치기 전에도 잘 동작했기 때문이다. 그래서 "남의 것을 요청하면 거절한다"를
 * 명시적으로 고정해 둔다.
 */
class OrderOwnershipTest {

    private static final long OWNER_ID = 100L;
    private static final long INTRUDER_ID = 200L;

    private OrderRepository orderRepository;
    private OrderDataService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);

        // 생성자 인자 순서는 @RequiredArgsConstructor 가 만드는 필드 선언 순서를 따른다.
        service = new OrderDataService(
                orderRepository,
                mock(CatalogServiceClient.class),
                new ObjectMapper(),
                mock(OrderAddressRepository.class),
                mock(com.project.order_service.outbox.PaymentOutboxHelper.class),
                mock(com.project.order_service.payment.PaymentGateways.class),
                new com.project.order_service.payment.PaymentCurrencyPolicy());

        Order order = newInstance(Order.class);
        ReflectionTestUtils.setField(order, "orderId", 1L);
        ReflectionTestUtils.setField(order, "customerId", OWNER_ID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("테스트 인스턴스 생성 실패: " + type, e);
        }
    }

    @Test
    @DisplayName("남의 주문을 주문번호로 조회하면 아무것도 돌려주지 않는다")
    void otherCustomersOrderIsNotReadable() {
        // 침입자가 주문번호 1번(소유자: OWNER)을 그대로 요청한다.
        assertThat(service.getOrder(1L, INTRUDER_ID, "")).isNull();
    }

    @Test
    @DisplayName("요청자를 모르면(신원 없음) 주문이 새지 않는다")
    void unknownRequesterGetsNothing() {
        // 신원이 없는 요청이다. 소유자를 특정할 수 없으므로 통과시키면 안 된다 — null 을 "일치"로
        // 처리하면 전부 열린다.
        assertThat(service.getOrder(1L, null, "")).isNull();
    }

    @Test
    @DisplayName("주문이 없으면 null 이다 — 소유권 검사보다 먼저 걸린다")
    void missingOrderIsNull() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());
        assertThat(service.getOrder(999L, OWNER_ID, "")).isNull();
    }
}
