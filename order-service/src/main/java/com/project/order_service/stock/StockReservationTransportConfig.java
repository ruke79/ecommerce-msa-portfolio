package com.project.order_service.stock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.project.common.grpc.GrpcTls;
import com.project.order_service.client.CatalogServiceClient;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 재고 예약 전송 스위치 — {@code stock.rpc.transport}(STOCK_RPC_TRANSPORT) = {@code http}(기본) | {@code grpc} (docs/66).
 *
 * <p>되돌리기는 설정 하나다: ConfigMap 의 값을 http 로 바꾸고 {@code ops/28 --config-only}. 두 전송은 같은 catalog 서비스 메서드에
 * 닿으므로 진행 중인 예약의 의미는 바뀌지 않는다.
 *
 * <p>gRPC 채널은 grpc 일 때만 만든다(http 면 Netty 스레드도 열지 않는다). 채널은 오래 쓰는 객체다 — 호출마다 만들지 않고, HTTP/2
 * 연결 하나 위에 호출을 다중화한다. 컨텍스트가 닫히면 {@code shutdown} 으로 닫는다.
 */
// [JA] 在庫予約の伝送スイッチ — stock.rpc.transport = http(既定) | grpc。戻すのは設定ひとつ(ConfigMap を http にして
//      ops/28 --config-only)。gRPC チャネルは grpc のときだけ作り、呼び出しごとには作らない(HTTP/2 接続一本の上で多重化)。
@Slf4j
@Configuration
public class StockReservationTransportConfig {

    @Bean
    public StockReservationPort stockReservationPort(CatalogServiceClient catalog,
            @Value("${stock.rpc.transport:http}") String transport,
            org.springframework.beans.factory.ObjectProvider<ManagedChannel> channel,
            @Value("${stock.grpc.deadline-ms:3000}") long deadlineMillis) {
        String t = transport == null ? "http" : transport.trim().toLowerCase(java.util.Locale.ROOT);
        switch (t) {
            case "grpc" -> {
                log.info("재고 예약 전송: grpc (기한 {}ms)", deadlineMillis);
                return new GrpcStockReservationPort(channel.getObject(), deadlineMillis);
            }
            case "http" -> {
                log.info("재고 예약 전송: http");
                return new FeignStockReservationPort(catalog);
            }
            default -> throw new IllegalStateException("stock.rpc.transport 는 http · grpc 중 하나여야 한다: " + transport);
        }
    }

    /**
     * catalog 로 가는 gRPC 채널. {@code @Lazy} 대신 {@link org.springframework.beans.factory.ObjectProvider} 로 받으므로 http 면
     * 만들어지지 않는다(프로토타입이 아니라 싱글턴 — 한 번 만들면 계속 쓴다).
     */
    @Bean(destroyMethod = "shutdown")
    @org.springframework.context.annotation.Lazy
    public ManagedChannel stockReservationChannel(ObservationRegistry observations,
            @Value("${stock.grpc.target:dns:///localhost:9095}") String target,
            @Value("${stock.grpc.tls.key-store:${javax.net.ssl.keyStore:}}") String keyStore,
            @Value("${stock.grpc.tls.key-store-password:${javax.net.ssl.keyStorePassword:}}") String keyStorePassword,
            @Value("${internal-auth.ca-cert:${LOCAL_CACERT_PATH:}}") String ca) throws Exception {
        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(target)
                .intercept(new ObservationGrpcClientInterceptor(observations));
        boolean tls = GrpcTls.configured(keyStore, ca);
        if (tls) {
            builder.sslContext(GrpcTls.client(keyStore, keyStorePassword, ca));
        } else {
            // 평문 — 로컬 개발. 운영(catalog 가 enforce)에서 이리로 오면 모든 호출이 UNAUTHENTICATED 로 거절된다(로그에 남는다).
            log.warn("재고 예약 gRPC 채널이 평문이다 — TLS 키스토어/CA 가 없다(keyStore={}, ca={})", keyStore, ca);
            builder.usePlaintext();
        }
        log.info("재고 예약 gRPC 채널: target={} tls={}", target, tls);
        return builder.build();
    }
}
