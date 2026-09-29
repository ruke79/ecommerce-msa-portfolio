package com.project.order_service.stock;

import java.util.concurrent.TimeUnit;

import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;
import com.project.common.grpc.StockReservationMapping;
import com.project.common.grpc.stock.v1.ReleaseRequest;
import com.project.common.grpc.stock.v1.StockReservationGrpc;

import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

/**
 * gRPC — 재고 예약의 시범 전송(docs/66).
 *
 * <h3>기한(deadline)</h3>
 *
 * <p>호출마다 기한을 건다(기본 3초). HTTP 의 읽기 제한(5초)과 달리 기한은 <b>서버로 전파된다</b> — catalog 는 기한이 지난 호출을
 * 알고(Context 가 취소된다) 더 기다리지 않는다. 주문 쪽이 이미 포기한 예약을 catalog 가 끝까지 붙잡고 있지 않게 한다.
 *
 * <h3>실패</h3>
 *
 * <p>{@link StatusRuntimeException}(UNAVAILABLE · DEADLINE_EXCEEDED · UNAUTHENTICATED …)을 그대로 던진다 — {@link OrderStockHold}
 * 가 HTTP 실패와 똑같이 "catalog 에 닿지 못함" 으로 읽는다. 상태 코드는 여기서 한 번 남긴다(어느 쪽 문제인지 가르는 단서:
 * UNAUTHENTICATED · PERMISSION_DENIED 면 인증서 · 경로 규칙, UNAVAILABLE 이면 연결, DEADLINE_EXCEEDED 면 catalog 가 느림).
 */
// [JA] gRPC — 在庫予約の試行伝送。呼び出しごとに期限(既定 3 秒)をかけ、期限はサーバーへ伝播する。失敗は
//      StatusRuntimeException のまま投げ、OrderStockHold が HTTP の失敗と同じく「catalog に届かない」と読む。
//      ステータスコードはここで一度記録する(原因の切り分けの手がかり)。
@Slf4j
public class GrpcStockReservationPort implements StockReservationPort {

    private final StockReservationGrpc.StockReservationBlockingStub stub;
    private final long deadlineMillis;

    public GrpcStockReservationPort(Channel channel, long deadlineMillis) {
        this.stub = StockReservationGrpc.newBlockingStub(channel);
        this.deadlineMillis = deadlineMillis;
    }

    @Override
    public StockReservationResult reserve(StockReservationRequest request) {
        try {
            return StockReservationMapping.fromProto(
                    stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                            .reserve(StockReservationMapping.toProto(request)));
        } catch (StatusRuntimeException e) {
            log.warn("재고 예약(gRPC) 실패 — orderId={} status={}", request.getOrderId(), e.getStatus().getCode());
            throw e;
        }
    }

    @Override
    public void release(Long orderId) {
        try {
            stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                    .release(ReleaseRequest.newBuilder().setOrderId(orderId).build());
        } catch (StatusRuntimeException e) {
            log.warn("재고 예약 해제(gRPC) 실패 — orderId={} status={}", orderId, e.getStatus().getCode());
            throw e;
        }
    }

    @Override
    public String transport() {
        return "grpc";
    }
}
