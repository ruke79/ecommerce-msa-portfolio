package com.project.catalog_service.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.SmartLifecycle;

import com.project.catalog_service.service.StockReservationService;
import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;
import com.project.common.grpc.GrpcTls;
import com.project.common.grpc.stock.v1.Line;
import com.project.common.grpc.stock.v1.ReleaseRequest;
import com.project.common.grpc.stock.v1.ReserveRequest;
import com.project.common.grpc.stock.v1.ReserveResponse;
import com.project.common.grpc.stock.v1.StockReservationGrpc;
import com.project.common.security.InternalCallerFilter;
import com.project.common.security.TestPki;
import com.project.common.tracing.OtelTestTracing;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;

/**
 * 재고 예약 gRPC 서버 — 운영과 같은 조립({@link StockGrpcServerConfig})을 <b>실제 Netty · TLS</b> 위에 띄워 본다(docs/66).
 *
 * <p>확인하는 것: ① 같은 서비스 메서드에 닿고 결과가 그대로 온다 ② 모자람은 오류가 아니다 ③ 잘못된 요청은 INVALID_ARGUMENT,
 * 그 밖의 실패는 INTERNAL(내부 메시지 없이) ④ 경로 규칙이 걸린다 ⑤ order → catalog 가 한 추적이 된다 ⑥ enforce 인데 TLS 가
 * 없으면 서버를 띄우지 않는다.
 */
@DisplayName("재고 예약 gRPC 서버 (Netty · mTLS)")
class StockReservationGrpcServerTest {

    static Path dir;
    static String ca;
    static String catalogStore;
    static String orderStore;
    static String cartStore;

    private final StockReservationService reservations = mock(StockReservationService.class);
    private SmartLifecycle lifecycle;
    private ManagedChannel channel;
    private OtelTestTracing tracing;

    @BeforeAll
    static void pki() throws Exception {
        dir = Files.createTempDirectory("catalog-grpc");
        TestPki pki = new TestPki(dir);
        ca = pki.ca("ca", "internal-ca").toString();
        catalogStore = pki.identity("ca", "catalog", "catalog-service").toString();
        orderStore = pki.identity("ca", "order", "order-service").toString();
        cartStore = pki.identity("ca", "cart", "cart-service").toString();
    }

    @AfterEach
    void stop() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (lifecycle != null) {
            lifecycle.stop();
        }
        if (tracing != null) {
            tracing.close();
        }
    }

    private ObservationRegistry registry() {
        tracing = new OtelTestTracing();
        ObservationRegistry r = ObservationRegistry.create();
        r.observationConfig().observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracing.tracer(), tracing.propagator()),
                new PropagatingReceiverTracingObservationHandler<>(tracing.tracer(), tracing.propagator()),
                new DefaultTracingObservationHandler(tracing.tracer())));
        return r;
    }

    /** 운영 조립 그대로 — enforce, 경로 규칙은 ConfigMap 과 같은 모양. */
    private int startServer(ObservationRegistry observations, String keyStore, String caPath) throws Exception {
        InternalCallerFilter policy = new InternalCallerFilter(InternalCallerFilter.Mode.ENFORCE,
                new TestPki(dir).read(Path.of(ca)), Set.of(), List.of(),
                List.of(new InternalCallerFilter.PathRule("/ecommerce.stock.v1.StockReservation/", Set.of("order-service"))),
                InternalCallerFilter.Mode.ENFORCE);
        StockGrpcServerConfig config = new StockGrpcServerConfig();
        lifecycle = config.stockGrpcServer(config.stockReservationGrpcService(reservations),
                new FilterRegistrationBean<>(policy), observations, 0, keyStore, TestPki.PASSWORD, caPath, "enforce");
        lifecycle.start();
        return ((StockGrpcServerConfig.GrpcServerLifecycle) lifecycle).port();
    }

    private StockReservationGrpc.StockReservationBlockingStub client(int port, String identity,
            ObservationRegistry observations) throws Exception {
        channel = NettyChannelBuilder.forAddress("localhost", port)
                .sslContext(GrpcTls.client(identity, TestPki.PASSWORD, ca))
                .intercept(new ObservationGrpcClientInterceptor(observations))
                .build();
        return StockReservationGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    private static ReserveRequest request() {
        return ReserveRequest.newBuilder().setOrderId(812L).setTtlSeconds(1800)
                .addLines(Line.newBuilder().setProductId(10L).setColorId(5L).setSize("270").setQty(2))
                .addLines(Line.newBuilder().setProductId(11L).setColorId(6L).setSize("").setQty(1))   // 사이즈 없는 상품
                .build();
    }

    @Test
    @DisplayName("🔴 HTTP 입구와 같은 서비스 메서드에 닿는다 — 줄 · 사이즈(빈 문자열 포함) · 만료가 그대로 오간다")
    void reserveReachesTheSameServiceMethod() throws Exception {
        Instant until = Instant.parse("2026-09-28T10:30:00Z");
        when(reservations.reserve(any())).thenReturn(StockReservationResult.builder().held(true).expiresAt(until).build());
        ObservationRegistry obs = registry();

        ReserveResponse res = client(startServer(obs, catalogStore, ca), orderStore, obs).reserve(request());

        assertThat(res.getHeld()).isTrue();
        assertThat(res.getExpiresAtEpochMillis()).isEqualTo(until.toEpochMilli());
        ArgumentCaptor<StockReservationRequest> sent = ArgumentCaptor.forClass(StockReservationRequest.class);
        verify(reservations).reserve(sent.capture());
        assertThat(sent.getValue().getOrderId()).isEqualTo(812L);
        assertThat(sent.getValue().getTtlSeconds()).isEqualTo(1800L);
        assertThat(sent.getValue().getLines()).extracting(StockReservationRequest.Line::getSize).containsExactly("270", "");
    }

    @Test
    @DisplayName("모자람은 오류가 아니다 — held=false 와 모자란 줄(HTTP 도 200)")
    void shortageIsAResultNotAnError() throws Exception {
        when(reservations.reserve(any())).thenReturn(StockReservationResult.builder().held(false)
                .shortages(List.of(StockReservationResult.Shortage.builder()
                        .productId(10L).colorId(5L).size("270").requested(2).available(1).build()))
                .build());
        ObservationRegistry obs = registry();

        ReserveResponse res = client(startServer(obs, catalogStore, ca), orderStore, obs).reserve(request());

        assertThat(res.getHeld()).isFalse();
        assertThat(res.getExpiresAtEpochMillis()).isZero();
        assertThat(res.getShortagesList()).singleElement().satisfies(s -> {
            assertThat(s.getRequested()).isEqualTo(2);
            assertThat(s.getAvailable()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("잘못된 요청은 INVALID_ARGUMENT, 그 밖의 실패는 INTERNAL — 내부 예외 메시지는 싣지 않는다")
    void errorsAreMapped() throws Exception {
        ObservationRegistry obs = registry();
        var stub = client(startServer(obs, catalogStore, ca), orderStore, obs);

        org.mockito.Mockito.doThrow(new IllegalArgumentException("예약할 주문과 줄이 필요합니다."))
                .when(reservations).reserve(any());
        assertThatThrownBy(() -> stub.reserve(request())).isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));

        org.mockito.Mockito.doThrow(new IllegalStateException("could not obtain lock on row product_sku#77"))
                .when(reservations).reserve(any());
        assertThatThrownBy(() -> stub.reserve(request())).isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(e.getStatus().getDescription()).doesNotContain("product_sku");
        });

        assertThatThrownBy(() -> stub.release(ReleaseRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("🔴 경로 규칙 — 같은 CA 의 cart-service 는 PERMISSION_DENIED, 서비스 메서드에 닿지 않는다")
    void onlyOrderServiceMayCall() throws Exception {
        ObservationRegistry obs = registry();
        var stub = client(startServer(obs, catalogStore, ca), cartStore, obs);

        assertThatThrownBy(() -> stub.reserve(request())).isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
        org.mockito.Mockito.verifyNoInteractions(reservations);
    }

    /** OTel 브리지가 traceparent 를 gRPC 메타데이터로 넘기고 받아서, order 의 span 과 catalog 의 span 이 한 추적에 있다. */
    @Test
    @DisplayName("🔴 order → catalog 가 한 추적이 된다 — 서버 span 의 부모가 클라이언트 span")
    void traceContinuesAcrossTheCall() throws Exception {
        when(reservations.release(any())).thenReturn(1);
        ObservationRegistry obs = registry();
        var stub = client(startServer(obs, catalogStore, ca), orderStore, obs);

        stub.release(ReleaseRequest.newBuilder().setOrderId(812L).build());

        // 서버는 응답을 보낸 <b>뒤에</b> span 을 닫는다 — 클라이언트가 응답을 받은 순간에는 아직 열려 있을 수 있다(간헐 실패의 원인).
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (tracing.finishedSpans().stream().noneMatch(s -> s.getKind() == io.opentelemetry.api.trace.SpanKind.SERVER)
                && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        var spans = tracing.finishedSpans();
        var client = spans.stream().filter(s -> s.getKind() == io.opentelemetry.api.trace.SpanKind.CLIENT).findFirst().orElseThrow();
        var server = spans.stream().filter(s -> s.getKind() == io.opentelemetry.api.trace.SpanKind.SERVER).findFirst().orElseThrow();
        assertThat(server.getTraceId()).isEqualTo(client.getTraceId());
        assertThat(server.getParentSpanId()).isEqualTo(client.getSpanId());
        // span 이름 = gRPC 메서드 — Jaeger 에서 "ecommerce.stock.v1.StockReservation/Release" 로 찾는다
        assertThat(server.getName()).isEqualTo("ecommerce.stock.v1.StockReservation/Release");
    }

    @Test
    @DisplayName("enforce 인데 TLS 키스토어가 없으면 서버를 띄우지 않는다 — catalog 는 계속 뜨고 HTTP 입구는 그대로")
    void enforceWithoutTlsDoesNotStart() throws Exception {
        int port = startServer(registry(), "", ca);

        assertThat(port).isEqualTo(-1);
        assertThat(lifecycle.isRunning()).isTrue();   // 컨텍스트 기동을 막지 않는다
        assertThat(new File(ca)).exists();
    }
}
