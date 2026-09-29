package com.project.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.grpc.GrpcTls;
import com.project.common.grpc.stock.v1.ReleaseRequest;
import com.project.common.grpc.stock.v1.ReleaseResponse;
import com.project.common.grpc.stock.v1.StockReservationGrpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

/**
 * gRPC 서버의 호출자 판정 — <b>실제 TLS</b>(Netty) 위에서 HTTP 필터와 같은 규칙이 걸리는지 본다(docs/66).
 *
 * <p>인증서는 keytool 로 만든다({@link TestPki}): 내부 CA 와 그 CA 가 서명한 order-service · cart-service 신원, 다른 CA 가 서명한
 * order-service 신원(이름만 같은 가짜).
 */
@DisplayName("gRPC 호출자 판정 (mTLS)")
class InternalCallerGrpcInterceptorTest {

    private static final String RULE = "/ecommerce.stock.v1.StockReservation/";

    static Path dir;
    static String ca;
    static String catalogStore;
    static String orderStore;
    static String cartStore;
    static String impostorStore;

    private Server server;
    private ManagedChannel channel;
    private final AtomicReference<String> seenCaller = new AtomicReference<>();

    @BeforeAll
    static void pki() throws Exception {
        dir = Files.createTempDirectory("grpc-pki");
        TestPki pki = new TestPki(dir);
        ca = pki.ca("ca", "internal-ca").toString();
        catalogStore = pki.identity("ca", "catalog", "catalog-service").toString();
        orderStore = pki.identity("ca", "order", "order-service").toString();
        cartStore = pki.identity("ca", "cart", "cart-service").toString();
        pki.ca("other", "other-ca");
        impostorStore = pki.identity("other", "impostor", "order-service").toString();
    }

    @AfterEach
    void stop() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void start(InternalCallerFilter.Mode mode, InternalCallerFilter.Mode rulesMode) throws Exception {
        InternalCallerFilter policy = new InternalCallerFilter(mode, new TestPki(dir).read(Path.of(ca)), Set.of(), List.of(),
                List.of(new InternalCallerFilter.PathRule(RULE, Set.of("order-service"))), rulesMode);
        StockReservationGrpc.StockReservationImplBase impl = new StockReservationGrpc.StockReservationImplBase() {
            @Override
            public void release(ReleaseRequest request, StreamObserver<ReleaseResponse> out) {
                seenCaller.set(InternalCallerGrpcInterceptor.CALLER.get());
                out.onNext(ReleaseResponse.getDefaultInstance());
                out.onCompleted();
            }
        };
        server = NettyServerBuilder.forPort(0)
                .sslContext(GrpcTls.server(catalogStore, TestPki.PASSWORD, ca))
                .addService(ServerInterceptors.intercept(impl, new InternalCallerGrpcInterceptor(policy)))
                .build().start();
    }

    private StockReservationGrpc.StockReservationBlockingStub client(String keyStore) throws Exception {
        var ssl = keyStore == null
                ? GrpcSslContexts.forClient().trustManager(new java.io.File(ca)).build()   // 인증서를 내지 않는 클라이언트
                : GrpcTls.client(keyStore, TestPki.PASSWORD, ca);
        channel = NettyChannelBuilder.forAddress("localhost", server.getPort()).sslContext(ssl).build();
        return StockReservationGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    private static ReleaseRequest release() {
        return ReleaseRequest.newBuilder().setOrderId(1L).build();
    }

    @Test
    @DisplayName("🔴 규칙이 허용한 서비스(order-service)는 통과하고, 서비스 이름이 호출 문맥에 실린다")
    void allowedCallerPasses() throws Exception {
        start(InternalCallerFilter.Mode.ENFORCE, InternalCallerFilter.Mode.ENFORCE);

        client(orderStore).release(release());

        assertThat(seenCaller.get()).isEqualTo("order-service");
    }

    @Test
    @DisplayName("🔴 같은 CA 라도 규칙에 없는 서비스(cart-service)는 PERMISSION_DENIED")
    void otherServiceIsDenied() throws Exception {
        start(InternalCallerFilter.Mode.ENFORCE, InternalCallerFilter.Mode.ENFORCE);
        var stub = client(cartStore);

        assertThatThrownBy(() -> stub.release(release()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
        assertThat(seenCaller.get()).isNull();
    }

    @Test
    @DisplayName("🔴 인증서를 내지 않으면 UNAUTHENTICATED — 클러스터 안의 아무 파드가 부를 수 없다")
    void noCertificateIsUnauthenticated() throws Exception {
        start(InternalCallerFilter.Mode.ENFORCE, InternalCallerFilter.Mode.ENFORCE);
        var stub = client(null);

        assertThatThrownBy(() -> stub.release(release()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("다른 CA 가 서명한 인증서는 이름이 order-service 여도 통하지 않는다(TLS 에서 끊긴다)")
    void impostorIsRejected() throws Exception {
        start(InternalCallerFilter.Mode.ENFORCE, InternalCallerFilter.Mode.ENFORCE);
        var stub = client(impostorStore);

        assertThatThrownBy(() -> stub.release(release())).isInstanceOf(StatusRuntimeException.class);
        assertThat(seenCaller.get()).isNull();
    }

    @Test
    @DisplayName("observe 모드 — 인증서가 없어도 기록만 하고 통과(켜기 전 확인 단계)")
    void observeLetsItThrough() throws Exception {
        start(InternalCallerFilter.Mode.OBSERVE, InternalCallerFilter.Mode.ENFORCE);

        client(null).release(release());

        assertThat(seenCaller.get()).isNull();
    }

    @Test
    @DisplayName("규칙만 observe(rules-mode=observe) — 규칙 위반은 기록하고 통과, 서비스 이름은 실린다")
    void rulesObserveLetsItThrough() throws Exception {
        start(InternalCallerFilter.Mode.ENFORCE, InternalCallerFilter.Mode.OBSERVE);

        client(cartStore).release(release());

        assertThat(seenCaller.get()).isEqualTo("cart-service");
    }

    @Test
    @DisplayName("off — 판정하지 않는다(로컬 개발)")
    void offDoesNothing() throws Exception {
        start(InternalCallerFilter.Mode.OFF, InternalCallerFilter.Mode.ENFORCE);

        client(null).release(release());
    }
}
