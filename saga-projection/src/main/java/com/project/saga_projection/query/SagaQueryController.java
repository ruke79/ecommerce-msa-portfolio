package com.project.saga_projection.query;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * {@code GET /api/saga/{orderId}} — 주문 하나의 결제 사가 진행 상태.
 *
 * <p>응답 코드: 200 상태 있음(본문에 {@code stale} 표시), 404 이 주문의 요청도 응답도 본 적 없음,
 * 503 아직 준비되지 않았거나(기동·리밸런스·복구 중) 이 인스턴스에 사본이 없음({@code Retry-After} 포함).
 * 인증은 게이트웨이가 한다 — 이 서비스는 내부 망에서만 열린다.
 */
@RestController
@RequestMapping("/api/saga")
@RequiredArgsConstructor
public class SagaQueryController {

    private final SagaQueryService service;

    @GetMapping("/{orderId}")
    public ResponseEntity<?> get(@PathVariable long orderId) {
        SagaQueryService.Result r = service.find(orderId);
        return switch (r.answer()) {
            case FOUND -> ResponseEntity.ok(r.view());
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("orderId", orderId, "message", "이 주문의 사가 이벤트를 본 적이 없다"));
            case NOT_READY -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "2")
                    .body(Map.of("orderId", orderId, "message", "프로젝션이 아직 준비되지 않았다(기동·리밸런스·복구 중)"));
            case NOT_HERE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "2")
                    .body(Map.of("orderId", orderId, "activeHost", r.activeHost(),
                            "message", "이 인스턴스에 상태 사본이 없다 — 레플리카 수가 standby+1 을 넘는다"));
        };
    }
}
