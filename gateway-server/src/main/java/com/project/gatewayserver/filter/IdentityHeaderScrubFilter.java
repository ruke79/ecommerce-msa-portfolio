package com.project.gatewayserver.filter;

import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 클라이언트가 직접 보낸 신원 헤더를 <b>모든 요청에서 가장 먼저</b> 지운다.
 *
 * <p>신원은 게이트웨이만 주장할 수 있어야 한다. {@code User-Role: ROLE_ADMIN} 한 줄을
 * 그냥 보내서 하위 서비스가 그대로 믿으면 헤더 한 줄이 곧 권한 상승이다.
 *
 * <h2>왜 default-filters 가 아니라 GlobalFilter 인가</h2>
 *
 * 예전에는 이 일을 application-local.yml 의 {@code default-filters} 에서 했다:
 *
 * <pre>
 *   default-filters:
 *     - DedupeResponseHeader=...
 *     - RemoveRequestHeader=User-Id
 *     - RemoveRequestHeader=User-Email
 *     - RemoveRequestHeader=User-Role
 * </pre>
 *
 * 그 옆에는 "default-filters 는 라우트 필터보다 <b>먼저</b> 실행되므로, 여기서 지운 뒤
 * JwtAuthorization 이 검증된 값을 다시 넣는 순서가 된다" 는 주석이 있었다.
 * <b>그 전제가 틀렸다.</b> 스프링 클라우드 게이트웨이 4.2 의
 * {@code RouteDefinitionRouteLocator} 를 보면:
 *
 * <ul>
 *   <li>{@code loadGatewayFilters} 가 각 필터를 <b>그 목록 안에서의 인덱스+1</b> 로
 *       감싼다 — {@code new OrderedGatewayFilter(filter, i + 1)}</li>
 *   <li>{@code getFilters} 는 default-filters 를 먼저 담고(order 1,2,3,4,5),
 *       <b>라우트 필터를 다시 1부터</b> 담은 뒤 정렬한다</li>
 * </ul>
 *
 * 그래서 실제 순서는 이렇게 된다:
 *
 * <pre>
 *   1  Dedupe(기본,1)   |  JwtAuthorization(라우트,1)  ← User-Id 를 넣는다
 *   2  RemoveRequestHeader=User-Id(기본,2)             ← 방금 넣은 것을 지운다
 *   3  RemoveRequestHeader=User-Email(기본,3)
 *   4  RemoveRequestHeader=User-Role(기본,4)
 * </pre>
 *
 * <p>결과: <b>검증된 신원이 하위 서비스에 하나도 도달하지 않았다.</b> JwtAuthorization 이
 * 붙은 <b>모든</b> 라우트가 그랬다. 증상은 각 서비스에서 "로그인이 필요합니다"(401) 로
 * 나타났고, 게이트웨이 로그에는 필터가 정상 호출된 것으로 찍혀서 원인이 전혀 드러나지
 * 않았다. 관리자 화면은 배너 호출 하나가 401 을 두 번 받아 앱 전체가 로그아웃됐다.
 *
 * <p>GlobalFilter 는 라우트 필터와 함께 정렬되지만 자기 {@code getOrder()} 를 그대로
 * 쓴다. {@code HIGHEST_PRECEDENCE} 면 라우트 필터(order 1 부터)보다 반드시 앞이다 —
 * 필터 개수나 나열 순서에 기대지 않는다. 그것이 이 클래스가 존재하는 이유다.
 *
 * <p>⚠️ 이 헤더들을 {@code default-filters} 로 되돌리지 말 것.
 * {@code ConfigurationGuardrailTest} B-53 이 막는다.
 */
@Slf4j
@Component
public class IdentityHeaderScrubFilter implements GlobalFilter, Ordered {

    /** 로그 · 내부 엔드포인트 차단 다음 — 순서는 {@link PreLoggingFilter#ORDER} 의 주석. 라우트 필터(1 부터)보다 한참 앞이다. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    /**
     * 게이트웨이만 설정할 수 있는 헤더. JwtAuthorization 이 검증 뒤에 다시 넣는다.
     *
     * <p>{@code X-} 변형도 지운다. 게이트웨이는 그 이름을 쓰지 않지만, 예전
     * {@code CustomUserKeyResolver} 가 {@code X-User-Id} 를 읽었다 — 즉 <b>아무도 넣지 않는
     * 헤더를 하위 코드가 믿는 일</b>이 실제로 있었다. 신원처럼 보이는 이름은 들어오는 족족
     * 지워 두면, 나중에 누가 어느 이름으로 읽든 클라이언트 값은 도달하지 않는다.
     */
    static final List<String> IDENTITY_HEADERS = List.of(
            "User-Id", "User-Email", "User-Role",
            "X-User-Id", "X-User-Email", "X-User-Role");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean spoofed = IDENTITY_HEADERS.stream()
                .anyMatch(h -> exchange.getRequest().getHeaders().containsKey(h));

        if (!spoofed) {
            return chain.filter(exchange);
        }

        // 값은 남기지 않는다 — 사칭 시도의 내용을 로그에 옮겨 적을 이유가 없다.
        log.warn("외부에서 신원 헤더가 들어왔다 — 지운다. path={}", exchange.getRequest().getURI().getPath());

        return chain.filter(exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(h -> IDENTITY_HEADERS.forEach(h::remove))
                        .build())
                .build());
    }

    @Override
    public int getOrder() {
        // 라우트 필터보다 먼저. 특히 JwtAuthorization(라우트 필터라 order 1 부터)보다 먼저여야 한다.
        return ORDER;
    }
}
