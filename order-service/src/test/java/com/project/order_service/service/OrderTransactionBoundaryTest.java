package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.project.order_service.client.CartServiceClient;

/**
 * 주문 생성의 원격 호출은 <b>트랜잭션 밖</b>에 있어야 한다 (docs/37 #5).
 *
 * <h3>🔴 무엇이 문제였나</h3>
 *
 * <p>{@code OrderDataService.createOrder} 가 트랜잭션을 연 직후 cart-service 를 불렀다.
 * DB 커넥션을 쥔 채 다른 서비스를 기다리는 모양이라 <b>주문 생성 지연이 cart-service
 * 지연에 정비례</b>했고, cart-service 가 멈추면 order-service 의 DB 풀이 먼저 말랐다.
 *
 * <p>docs/40 #10(금액을 서버가 정한다)을 고치면서 쿠폰 견적 호출이 하나 더 붙어 그 창이
 * 두 배가 됐다. Feign 기본값이 연결 3초·읽기 5초이므로 최악의 경우 커넥션 하나를
 * 16초까지 붙들 수 있었다.
 *
 * <h3>왜 테스트로 고정하는가</h3>
 *
 * <p>이런 경계는 <b>고쳐 놓아도 되돌아온다.</b> 다음 사람이 주문 생성에 값 하나를 더
 * 필요로 할 때, 가장 자연스러운 자리가 {@code OrderDataService.createOrder} 안이기
 * 때문이다. 거기에 Feign 클라이언트가 <b>없으면</b> 부를 수가 없다 — 이 테스트는 그
 * "없음" 을 지킨다.
 */
@DisplayName("주문 생성 트랜잭션 경계")
class OrderTransactionBoundaryTest {

    /**
     * 🔴 이것이 핵심 가드다. 필드가 없으면 그 메서드에서 원격 호출을 할 수 없다.
     * 주석으로 "여기서 부르지 마세요" 라고 적는 것보다 확실하다.
     */
    @Test
    @DisplayName("트랜잭션 안에서 도는 클래스는 원격 호출 클라이언트를 갖지 않는다")
    void theTransactionalServiceHoldsNoRemoteClient() {
        java.util.List<String> clients = Arrays.stream(OrderDataService.class.getDeclaredFields())
                .filter(f -> f.getType().getName().startsWith("com.project.order_service.client"))
                .map(Field::getName)
                .toList();

        assertThat(clients)
                .as("OrderDataService 의 메서드는 전부 트랜잭션 안에서 돈다. 여기에 Feign "
                        + "클라이언트를 두면 DB 커넥션을 쥔 채 다른 서비스를 기다리게 된다 "
                        + "— 원격 호출은 OrderService.createOrder 가 한다(docs/37 #5). "
                        + "catalogServiceClient 는 예외다: 조회 경로의 DTO 조립에 쓰이고 "
                        + "그건 별도 항목(docs/37 #1)이다")
                .containsExactly("catalogServiceClient");
    }

    /**
     * 클래스에 {@code @Transactional} 이 붙어 있어 메서드 하나만 끄려면 명시가 필요하다.
     * 이 애노테이션이 사라지면 원격 호출이 다시 트랜잭션 안으로 들어간다 — 코드는 그대로인데.
     */
    @Test
    @DisplayName("createOrder 는 트랜잭션을 열지 않는다")
    void createOrderRunsOutsideATransaction() throws Exception {
        Method createOrder = OrderService.class.getMethod("createOrder",
                com.project.common.dto.order.request.OrderRequest.class, Long.class);

        Transactional tx = createOrder.getAnnotation(Transactional.class);

        assertThat(tx)
                .as("클래스 레벨 @Transactional 을 이 메서드에서만 꺼야 한다")
                .isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    /**
     * 저장 단계는 반대로 <b>반드시</b> 트랜잭션이어야 한다. 주문·주문상품·배송지가 한
     * 단위로 커밋되지 않으면 품목 없는 주문이 남는다.
     */
    @Test
    @DisplayName("저장 단계는 트랜잭션 안이다")
    void thePersistStepIsTransactional() throws Exception {
        Method save = OrderCacheService.class.getMethod("createOrder",
                com.project.common.dto.order.request.OrderRequest.class,
                com.project.common.dto.CartDto.class,
                OrderPricing.Amounts.class,
                Long.class);

        assertThat(save.getAnnotation(Transactional.class)).isNotNull();
    }

    /**
     * 🔴 주문자 id 는 트랜잭션 밖에서 정해져 <b>인자로</b> 들어온다 — 지금은 게이트웨이가 검증한 토큰의 uid
     * (User-Id)이고, 예전에는 트랜잭션 밖의 백필(user-service 조회)이었다(docs/48 §6-①). 어느 쪽이든 트랜잭션 안의
     * {@code OrderDataService} 는 이미 정해진 값만 받는다.
     *
     * <p>예전에는 {@code OrderDataService.createOrder} 가 스스로
     * {@code getCustomerIdByEmail} 을 불렀다. 그 자체는 로컬 DB 라 괜찮았지만, 없을 때
     * 메꾸려면 그 자리에서 user-service 를 부르고 싶어진다 — 트랜잭션 안에서.
     */
    @Test
    @DisplayName("저장 단계는 주문자 id 를 인자로 받는다 — 스스로 찾지 않는다")
    void thePersistStepTakesTheCustomerId() throws Exception {
        Method save = OrderDataService.class.getMethod("createOrder",
                com.project.common.dto.order.request.OrderRequest.class,
                com.project.common.dto.CartDto.class,
                OrderPricing.Amounts.class,
                Long.class);

        assertThat(save).isNotNull();

        // user-service 호출(관리자 목록의 이메일 등)은 트랜잭션 밖의 OrderService 에 있어야 한다.
        // OrderDataService 에는 user-service 클라이언트 필드가 없어야 부를 수가 없다(docs/37 #5).
        assertThat(java.util.Arrays.stream(OrderDataService.class.getDeclaredFields())
                .map(f -> f.getType().getName()))
                .as("트랜잭션 안에서 user-service 를 부를 수 있으면 안 된다")
                .noneMatch(n -> n.endsWith("UserServiceClient"));
    }

    /** 위 가드가 진짜인지 — CartServiceClient 는 client 패키지에 있어야 걸린다. */
    @Test
    @DisplayName("가드는 client 패키지를 본다")
    void theGuardLooksAtTheRightPackage() {
        assertThat(CartServiceClient.class.getName())
                .startsWith("com.project.order_service.client");
    }
}
