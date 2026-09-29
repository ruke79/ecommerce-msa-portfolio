package com.project.gatewayserver.filter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 서비스의 관리·문서 엔드포인트를 게이트웨이 밖으로 내보내지 않는다 (docs/57 §7).
 *
 * <h3>왜</h3>
 *
 * <p>라우트는 {@code /catalog-service/**} 처럼 서비스 접두사 아래를 통째로 넘기고 {@code RewritePath} 로 접두사만 뗀다.
 * 그래서 {@code /catalog-service/actuator/prometheus} 는 catalog 의 {@code /actuator/prometheus} 에, {@code /catalog-service/
 * v3/api-docs} 는 catalog 의 API 명세에 그대로 닿았다 — catalog 의 읽기 라우트는 <b>로그인도 필요 없다.</b> 다른 서비스도
 * 로그인만 하면 같았다. 지표에는 내부 경로·호출량·JVM 상태가, 명세에는 관리자 API 의 전체 모양이 있다.
 *
 * <p>막을 곳을 서비스마다 두지 않고 여기 한 곳에 둔다 — 라우트가 늘어도 빠지지 않는다. Prometheus 는 서비스를
 * <b>직접</b> 긁으므로(observability/prometheus.yml) 영향이 없고, 게이트웨이 자신의 {@code /actuator/**}(첫 조각이
 * 서비스 이름이 아니다)도 건드리지 않는다.
 *
 * <p>404 로 답한다 — 있다는 사실도 알리지 않는다. 경로는 한 번 URL 디코딩하고 겹친 슬래시를 접은 뒤 소문자로 본다
 * ({@code %61ctuator}, {@code //actuator}, {@code /Actuator} 로 돌아가지 못하게).
 */
@Slf4j
@Component
public class InternalEndpointBlockFilter implements GlobalFilter, Ordered {

    /** 서비스 접두사 바로 다음 조각이 이것이면 막는다. */
    static final Set<String> BLOCKED_SEGMENTS = Set.of(
            "actuator", "v3", "swagger-ui", "swagger-ui.html", "api-docs", "swagger-resources", "webjars");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (isBlocked(exchange.getRequest().getURI().getRawPath())) {
            log.warn("관리·문서 엔드포인트 외부 접근 차단: {}", exchange.getRequest().getURI().getRawPath());
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    /** {@code /<서비스>/<막는 조각>/…} 인가. 첫 조각(서비스 접두사)만 있는 경로나 게이트웨이 자신의 경로는 아니다. */
    static boolean isBlocked(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        String path;
        try {
            path = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return true;   // 디코딩도 안 되는 경로는 정상 요청이 아니다
        }
        String[] segments = path.toLowerCase(Locale.ROOT).replaceAll("/{2,}", "/").split("/");
        // "", "<서비스>", "<다음 조각>", …
        if (segments.length < 3 || !segments[1].endsWith("-service")) {
            return false;
        }
        String next = segments[2].replaceAll(";.*$", "");   // 매트릭스 변수(;jsessionid=…)로 돌아가지 못하게
        return BLOCKED_SEGMENTS.contains(next);
    }

    /** 가장 먼저 — 인증 필터보다 앞에서, 라우팅 전에 끊는다. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
