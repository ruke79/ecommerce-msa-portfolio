package com.project.delivery_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.project.common.dto.order.OrderShippingDto;

/**
 * 주문의 배송 단면(수취인·주소·품목)을 order-service 에 묻는다.
 *
 * <p>결제 완료 이벤트({@code payment} 토픽)에는 주소가 없다. 배송에 필요한 것은 주문의
 * 주인이 갖고 있으니 직접 묻는다 — user-service 의 등급 배치가 구매 집계를 묻는 것과
 * 같은 모양이다.
 *
 * <p>🔒 <b>요청 헤더 전달 인터셉터를 붙이지 않는다.</b> 이 호출은 Kafka 리스너 스레드에서
 * 일어나므로 전달할 HTTP 요청 자체가 없고, 게이트웨이를 거치지 않아 {@code User-*} 헤더도
 * 붙지 않는다. order-service 는 그 상태를 "내부 호출" 로 읽는다({@code InternalCallGuard}).
 * 신원 헤더를 실어 보내는 순간 관리자가 아니라는 이유로 403 이 된다.
 *
 * <p>사설 CA 는 JVM 기본 트러스트스토어로 신뢰한다(k8s/48 의 {@code JAVA_TOOL_OPTIONS}).
 * Feign 은 {@code server.ssl}·{@code eureka.client.tls} 와 달리 그 기본값을 쓴다.
 */
@FeignClient(name = "order-service", url = "${feign.order-url}")
public interface OrderServiceClient {

    /** 없는 주문이면 404 → {@code FeignException.NotFound}. */
    @GetMapping("api/order/internal/orders/{orderId}/shipping")
    OrderShippingDto shipping(@PathVariable("orderId") long orderId);
}
