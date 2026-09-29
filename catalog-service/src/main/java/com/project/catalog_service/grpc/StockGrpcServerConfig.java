package com.project.catalog_service.grpc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.project.catalog_service.service.StockReservationService;
import com.project.common.grpc.GrpcTls;
import com.project.common.security.InternalCallerFilter;
import com.project.common.security.InternalCallerGrpcInterceptor;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 재고 예약 gRPC 서버 — Tomcat(8081) 옆의 별도 포트(기본 9095, docs/66).
 *
 * <h3>무엇이 걸려 있나</h3>
 * <ul>
 *   <li><b>mTLS</b> — 서비스 신원 인증서(JVM 이 이미 쓰는 {@code javax.net.ssl.keyStore})로 서버 TLS 를 켜고, 내부 CA 만 신뢰한다
 *       ({@link GrpcTls}). 키스토어 · CA 가 없으면 평문(로컬 개발 · 테스트).</li>
 *   <li><b>호출자 판정</b> — HTTP 필터와 <b>같은 객체</b>({@code internalCallerFilter} 빈의 필터)를 쓴다. 모드 · CA · 경로 규칙이
 *       한 벌이다({@link InternalCallerGrpcInterceptor}).</li>
 *   <li><b>추적 · 지표</b> — Micrometer Observation(바깥쪽 — 거절된 호출도 남는다). OTel 브리지가 traceparent 를 이어 받아
 *       order → catalog 가 한 추적이 되고, {@code grpc.server} 타이머가 Prometheus 로 간다.</li>
 * </ul>
 *
 * <h3>enforce 인데 TLS 가 없으면</h3>
 *
 * <p>서버를 <b>띄우지 않는다</b>(ERROR). 평문으로 뜨면 모든 호출이 인증서 없음으로 거절될 뿐이고, catalog 전체를 죽일 일도 아니다 —
 * HTTP 입구는 그대로 돈다. order 는 전송 스위치(STOCK_RPC_TRANSPORT)를 http 로 되돌리면 된다.
 */
// [JA] 在庫予約 gRPC サーバー — Tomcat(8081)の横の別ポート(既定 9095)。mTLS(身元証明書 + 内部 CA のみ信頼)、
//      呼び出し元判定(HTTP フィルターと同じオブジェクト)、追跡・メトリクス(Observation)がかかる。enforce なのに TLS が
//      なければサーバーを起動しない(ERROR) — HTTP の入口はそのまま動く。
@Slf4j
@Configuration
@ConditionalOnProperty(name = "stock.grpc.server.enabled", havingValue = "true", matchIfMissing = true)
public class StockGrpcServerConfig {

    @Bean
    public StockReservationGrpcService stockReservationGrpcService(StockReservationService reservations) {
        return new StockReservationGrpcService(reservations);
    }

    @Bean
    public SmartLifecycle stockGrpcServer(StockReservationGrpcService service,
            @Qualifier("internalCallerFilter") FilterRegistrationBean<InternalCallerFilter> callerFilter,
            ObservationRegistry observations,
            @Value("${stock.grpc.server.port:9095}") int port,
            @Value("${stock.grpc.tls.key-store:${javax.net.ssl.keyStore:}}") String keyStore,
            @Value("${stock.grpc.tls.key-store-password:${javax.net.ssl.keyStorePassword:}}") String keyStorePassword,
            @Value("${internal-auth.ca-cert:${LOCAL_CACERT_PATH:}}") String ca,
            @Value("${internal-auth.mode:off}") String mode) {
        return new GrpcServerLifecycle(() -> {
            boolean tls = GrpcTls.configured(keyStore, ca);
            if (!tls && !"off".equalsIgnoreCase(mode.trim())) {
                log.error("재고 예약 gRPC 서버를 띄우지 않는다 — internal-auth.mode={} 인데 TLS 키스토어/CA 가 없다(keyStore={}, ca={}). "
                        + "order 는 STOCK_RPC_TRANSPORT=http 로 두면 된다(docs/66)", mode, keyStore, ca);
                return null;
            }
            NettyServerBuilder builder = NettyServerBuilder.forPort(port)
                    .addService(ServerInterceptors.intercept(service,
                            new InternalCallerGrpcInterceptor(callerFilter.getFilter()),
                            // 마지막에 넣은 것이 가장 바깥 — 추적이 판정보다 먼저 돈다(거절도 추적에 남는다)
                            new ObservationGrpcServerInterceptor(observations)));
            if (tls) {
                builder.sslContext(GrpcTls.server(keyStore, keyStorePassword, ca));
            }
            Server server = builder.build().start();
            log.info("재고 예약 gRPC 서버: port={} tls={} mode={}", server.getPort(), tls, mode);
            return server;
        });
    }

    /** 서버를 만드는 일 — 실패하면 기동을 멈춘다(포트 충돌 · 키스토어 비밀번호 틀림은 조용히 넘길 일이 아니다). */
    @FunctionalInterface
    interface ServerFactory {
        Server start() throws Exception;
    }

    /**
     * 스프링 수명에 서버를 묶는다 — 컨텍스트가 뜰 때 열고, 닫힐 때 <b>진행 중인 호출을 기다렸다가</b>(최대 10초) 닫는다.
     * Tomcat 의 graceful shutdown 과 같은 단계(SmartLifecycle)에서 멈추므로, 배포 중 롤링 교체에 예약 호출이 끊기지 않는다.
     */
    static final class GrpcServerLifecycle implements SmartLifecycle {

        private final ServerFactory factory;
        private volatile Server server;
        private volatile boolean running;

        GrpcServerLifecycle(ServerFactory factory) {
            this.factory = factory;
        }

        @Override
        public void start() {
            try {
                server = factory.start();
            } catch (IOException e) {
                throw new IllegalStateException("재고 예약 gRPC 서버를 열지 못했다(포트?) — " + e.getMessage(), e);
            } catch (Exception e) {
                throw new IllegalStateException("재고 예약 gRPC 서버 설정 오류(TLS 키스토어 · CA?) — " + e.getClass().getSimpleName(), e);
            }
            running = true;
        }

        @Override
        public void stop() {
            running = false;
            Server s = server;
            if (s == null) {
                return;
            }
            s.shutdown();
            try {
                if (!s.awaitTermination(10, TimeUnit.SECONDS)) {
                    s.shutdownNow();
                }
            } catch (InterruptedException e) {
                s.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        /** 실제로 열린 포트(테스트 — 0 으로 열었을 때). 서버를 띄우지 않았으면 -1. */
        int port() {
            Server s = server;
            return s == null ? -1 : s.getPort();
        }
    }
}
