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
        try {
            var result = reservations.reserve(StockReservationMapping.fromProto(request));
            out.onNext(StockReservationMapping.toProto(result));
            out.onCompleted();
        } catch (IllegalArgumentException e) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
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
}
