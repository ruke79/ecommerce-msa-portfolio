package com.project.catalog_service.grpc;

import com.project.catalog_service.service.StockReservationService;
import com.project.common.grpc.StockReservationMapping;
import com.project.common.grpc.stock.v1.ReleaseRequest;
import com.project.common.grpc.stock.v1.ReleaseResponse;
import com.project.common.grpc.stock.v1.ReserveRequest;
import com.project.common.grpc.stock.v1.ReserveResponse;
import com.project.common.grpc.stock.v1.StockReservationGrpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 재고 예약의 gRPC 입구 — HTTP 입구({@code InternalStockController})와 <b>같은 서비스 메서드</b>를 부른다(docs/66).
 *
 * <p>두 입구가 같은 {@link StockReservationService} 에 닿으므로 잠금 순서 · 전부 아니면 전무 · 연장 규칙이 하나다. 다른 것은
 * 전송과 오류의 모양뿐이다:
 * <ul>
 *   <li>모자라다 — 오류가 아니다. {@code held=false} + 모자란 줄(HTTP 도 200).</li>
 *   <li>잘못된 요청({@code IllegalArgumentException}) — {@code INVALID_ARGUMENT}(HTTP 400 자리).</li>
 *   <li>그 밖의 실패 — {@code INTERNAL}. 예외 메시지는 싣지 않는다(내부 사정이 호출자에게 새지 않게 — 500 과 같은 원칙).</li>
 * </ul>
 */
// [JA] 在庫予約の gRPC の入口 — HTTP の入口と同じサービスメソッドを呼ぶ。違うのは伝送とエラーの形だけ:
//      不足はエラーではない(held=false)、不正なリクエストは INVALID_ARGUMENT、その他の失敗は INTERNAL(例外メッセージは載せない)。
@Slf4j
@RequiredArgsConstructor
public class StockReservationGrpcService extends StockReservationGrpc.StockReservationImplBase {

    private final StockReservationService reservations;

    @Override
    public void reserve(ReserveRequest request, StreamObserver<ReserveResponse> out) {
        // 🔒 호출자의 기한을 지킨다(docs/66 §5) — 🐛 예전에는 Context 를 보지 않아, 기한이 지난 호출도 잠금을 기다리며 일을 계속했다.
        // [JA] 呼び出し側の期限を守る。以前は Context を見ず、期限切れの呼び出しもロックを待ち続けた。
        java.time.Duration budget = remainingBudget(io.grpc.Context.current());
        if (budget != null && (budget.isNegative() || budget.isZero())) {
            out.onError(Status.DEADLINE_EXCEEDED.withDescription("deadline exceeded before reservation").asRuntimeException());
            return;
        }
        try {
            var result = reservations.reserve(StockReservationMapping.fromProto(request),
                    budget == null ? StockReservationService.DEFAULT_LOCK_TIMEOUT : budget);
            out.onNext(StockReservationMapping.toProto(result));
            out.onCompleted();
        } catch (IllegalArgumentException e) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            if (isLockWait(e)) {
                // 기한 안에 잠금을 얻지 못했다(lock_timeout 55P03) — 예약은 롤백됐다(아무것도 잡지 않았다).
                log.warn("재고 예약(gRPC) 잠금 대기 초과 — orderId={}", request.getOrderId());
                out.onError(Status.DEADLINE_EXCEEDED.withDescription("stock lock wait exceeded").asRuntimeException());
                return;
            }
            log.warn("재고 예약(gRPC) 실패 — orderId={} ({})", request.getOrderId(), e.getClass().getSimpleName());
            out.onError(Status.INTERNAL.withDescription("stock reservation failed").asRuntimeException());
        }
    }

    @Override
    public void release(ReleaseRequest request, StreamObserver<ReleaseResponse> out) {
        if (request.getOrderId() == 0L) {
            out.onError(Status.INVALID_ARGUMENT.withDescription("order_id is required").asRuntimeException());
            return;
        }
        try {
            reservations.release(request.getOrderId());
            out.onNext(ReleaseResponse.getDefaultInstance());
            out.onCompleted();
        } catch (RuntimeException e) {
            log.warn("재고 예약 해제(gRPC) 실패 — orderId={} ({})", request.getOrderId(), e.getClass().getSimpleName());
            out.onError(Status.INTERNAL.withDescription("stock release failed").asRuntimeException());
        }
    }

    /**
     * 잠금 대기 초과인가 — 리포지터리를 지나면 Spring 예외(PessimisticLockingFailureException), EntityManager 를 바로 쓰면
     * JPA 예외(jakarta PessimisticLockException · LockTimeoutException)로 온다. 둘 다 본다.
     */
    static boolean isLockWait(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.dao.PessimisticLockingFailureException
                    || t instanceof org.springframework.dao.QueryTimeoutException
                    || t instanceof jakarta.persistence.PessimisticLockException
                    || t instanceof jakarta.persistence.LockTimeoutException
                    || (t instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    /** 호출자의 남은 기한에서 응답에 쓸 여유(200ms)를 뺀 것 — 기한이 없으면 null(기본 상한을 쓴다). 취소된 호출은 0. */
    static java.time.Duration remainingBudget(io.grpc.Context context) {
        if (context.isCancelled()) {
            return java.time.Duration.ZERO;
        }
        io.grpc.Deadline deadline = context.getDeadline();
        if (deadline == null) {
            return null;
        }
        long ms = deadline.timeRemaining(java.util.concurrent.TimeUnit.MILLISECONDS) - 200;
        return java.time.Duration.ofMillis(Math.min(ms, StockReservationService.DEFAULT_LOCK_TIMEOUT.toMillis()));
    }
}
