package com.project.order_service.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;
import com.project.common.grpc.StockReservationMapping;
import com.project.common.grpc.stock.v1.ReleaseRequest;
import com.project.common.grpc.stock.v1.ReleaseResponse;
import com.project.common.grpc.stock.v1.ReserveRequest;
import com.project.common.grpc.stock.v1.ReserveResponse;
import com.project.common.grpc.stock.v1.StockReservationGrpc;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;

import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 재고 예약의 gRPC 전송 — order 쪽 (docs/66).
 *
 * <p>가장 중요한 것은 <b>동등성</b>이다: 같은 catalog 응답이면 HTTP 로 받든 gRPC 로 받든 {@link OrderStockHold} 의 결과가 같아야
 * 전송 스위치(STOCK_RPC_TRANSPORT)를 안심하고 바꿀 수 있다. catalog 는 메모리 안 gRPC 서버(in-process)로 흉내 낸다 — 판정 · TLS 는
 * catalog 의 {@code StockReservationGrpcServerTest} 가 실제 Netty 로 본다.
 */
@DisplayName("재고 예약 — gRPC 전송")
class GrpcStockReservationPortTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void stop() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** catalog 흉내 — 받은 요청(DTO)을 주면 줄 결과를 정한다. HTTP 흉내와 같은 함수를 쓴다(동등성 비교). */
    private GrpcStockReservationPort grpcCatalog(Function<StockReservationRequest, StockReservationResult> catalog,
            long deadlineMillis) throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new StockReservationGrpc.StockReservationImplBase() {
                    @Override
                    public void reserve(ReserveRequest req, StreamObserver<ReserveResponse> out) {
                        out.onNext(StockReservationMapping.toProto(catalog.apply(StockReservationMapping.fromProto(req))));
                        out.onCompleted();
                    }

                    @Override
                    public void release(ReleaseRequest req, StreamObserver<ReleaseResponse> out) {
                        out.onError(Status.UNAVAILABLE.asRuntimeException());
                    }
                }).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return new GrpcStockReservationPort(channel, deadlineMillis);
    }

    private static StockReservationPort httpCatalog(Function<StockReservationRequest, StockReservationResult> catalog) {
        CatalogServiceClient feign = mock(CatalogServiceClient.class);
        when(feign.reserveStock(any())).thenAnswer(i -> catalog.apply(i.getArgument(0)));
        return new FeignStockReservationPort(feign);
    }

    private static Order order() {
        Order o = Order.builder().orderId(77L).build();
        o.setOrderedProducts(List.of(
                OrderProduct.builder().productId(10L).colorId(5L).size("270").qty(2).name("러닝화").build(),
                OrderProduct.builder().productId(11L).colorId(6L).size("").qty(1).name("책").build()));
        return o;
    }

    private static OrderStockHold hold(StockReservationPort port) {
        return new OrderStockHold(port, new SimpleMeterRegistry(), true, 30);
    }

    @Test
    @DisplayName("🔴 동등성 — 잡혔을 때: HTTP 와 gRPC 의 결과(종류 · 만료)가 같다")
    void heldIsTheSameOverBothTransports() throws Exception {
        Instant until = Instant.parse("2026-09-28T10:30:00Z");
        AtomicReference<StockReservationRequest> seen = new AtomicReference<>();
        Function<StockReservationRequest, StockReservationResult> catalog = req -> {
            seen.set(req);
            return StockReservationResult.builder().held(true).expiresAt(until).build();
        };

        var overHttp = hold(httpCatalog(catalog)).hold(order(), java.time.Duration.ofMinutes(30));
        StockReservationRequest viaHttp = seen.get();
        var overGrpc = hold(grpcCatalog(catalog, 3000)).hold(order(), java.time.Duration.ofMinutes(30));
        StockReservationRequest viaGrpc = seen.get();

        assertThat(overGrpc).isEqualTo(overHttp);
        assertThat(overGrpc.kind()).isEqualTo(OrderStockHold.Kind.HELD);
        // catalog 가 받은 요청도 같다 — 사이즈 없는 상품의 빈 문자열까지
        assertThat(viaGrpc).usingRecursiveComparison().isEqualTo(viaHttp);
        assertThat(viaGrpc.getLines()).extracting(StockReservationRequest.Line::getSize).containsExactly("270", "");
    }

    @Test
    @DisplayName("🔴 동등성 — 모자랄 때: 모자란 줄까지 같다(오류가 아니라 결과)")
    void shortIsTheSameOverBothTransports() throws Exception {
        Function<StockReservationRequest, StockReservationResult> catalog = req -> StockReservationResult.builder()
                .held(false)
                .shortages(List.of(StockReservationResult.Shortage.builder()
                        .productId(10L).colorId(5L).size("270").requested(2).available(1).build()))
                .build();

        var overHttp = hold(httpCatalog(catalog)).hold(order(), java.time.Duration.ofMinutes(30));
        var overGrpc = hold(grpcCatalog(catalog, 3000)).hold(order(), java.time.Duration.ofMinutes(30));

        assertThat(overGrpc.kind()).isEqualTo(OrderStockHold.Kind.SHORT);
        assertThat(overGrpc.shortages()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(overHttp.shortages());
    }

    /**
     * 기한은 <b>서버로 전파된다</b> — catalog 는 order 가 포기한 호출을 안다(Context 취소). HTTP 의 읽기 제한은 클라이언트만 포기하고
     * 서버는 끝까지 일한다.
     */
    @Test
    @DisplayName("🔴 기한이 지나면 order 는 '닿지 못함' 으로 진행하고, catalog 쪽 호출도 취소된다(기한 전파)")
    void deadlinePropagatesAndOrderProceeds() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(new StockReservationGrpc.StockReservationImplBase() {
                    @Override
                    public void reserve(ReserveRequest req, StreamObserver<ReserveResponse> out) {
                        // 느린 catalog — 응답하지 않고, 호출이 취소되는지만 본다
                        Context.current().addListener(ctx -> cancelled.countDown(), Runnable::run);
                    }
                }).build().start();
        channel = InProcessChannelBuilder.forName(name).build();

        var outcome = hold(new GrpcStockReservationPort(channel, 100)).hold(order(), java.time.Duration.ofMinutes(30));

        assertThat(outcome.kind()).isEqualTo(OrderStockHold.Kind.UNAVAILABLE);
        assertThat(cancelled.await(2, TimeUnit.SECONDS)).as("catalog 쪽 Context 가 기한으로 취소된다").isTrue();
    }

    @Test
    @DisplayName("gRPC 실패는 상태 코드를 가진 채 던진다 — 해제 실패는 OrderStockHold 가 삼킨다(catalog 의 만료가 푼다)")
    void failuresKeepTheirStatus() throws Exception {
        GrpcStockReservationPort port = grpcCatalog(req -> null, 3000);

        assertThatThrownBy(() -> port.release(77L)).isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
        hold(port).release(77L);   // 던지지 않는다
    }

    @Test
    @DisplayName("전송 스위치 — http(기본) · grpc, 그 밖의 값은 기동을 멈춘다(오타가 조용히 http 가 되지 않게)")
    @SuppressWarnings("unchecked")
    void transportSwitch() throws Exception {
        var config = new StockReservationTransportConfig();
        CatalogServiceClient feign = mock(CatalogServiceClient.class);
        ObjectProvider<ManagedChannel> channels = mock(ObjectProvider.class);
        channel = InProcessChannelBuilder.forName("unused").build();
        when(channels.getObject()).thenReturn(channel);

        assertThat(config.stockReservationPort(feign, "http", channels, 3000).transport()).isEqualTo("http");
        assertThat(config.stockReservationPort(feign, " GRPC ", channels, 3000).transport()).isEqualTo("grpc");
        assertThatThrownBy(() -> config.stockReservationPort(feign, "grcp", channels, 3000))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(channels, org.mockito.Mockito.times(1)).getObject();   // http 면 채널을 만들지 않는다
    }
}
