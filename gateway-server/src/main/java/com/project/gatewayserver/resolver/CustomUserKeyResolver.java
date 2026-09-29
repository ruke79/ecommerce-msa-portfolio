package com.project.gatewayserver.resolver;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * RequestRateLimiter 의 버킷 키.
 *
 * <p><b>원칙 하나: 클라이언트가 고를 수 있는 값은 키가 될 수 없다.</b> 키를 고를 수 있으면
 * 요청마다 새 버킷을 얻어 제한이 없는 것과 같다. www-security 데모의 시나리오 1
 * ("Rate Limit 우회 — X-Forwarded-For 스푸핑")이 정확히 이것이고, 예전 구현이 두 군데서
 * 그 함정에 있었다:
 *
 * <ul>
 *   <li>{@code X-User-Id} 헤더를 폴백으로 읽었다. 게이트웨이가 넣는 이름은 {@code User-Id}
 *       이고 {@code X-User-Id} 는 <b>아무도 넣지 않는다</b> — 즉 그 값은 언제나 클라이언트가
 *       보낸 것이었다. 비로그인 요청에 {@code X-User-Id: 아무거나} 를 바꿔 가며 붙이면
 *       요청마다 버킷이 새로 생겼다.</li>
 *   <li>{@code X-Forwarded-For} 의 <b>첫 항목</b>을 IP 로 썼다. 프록시는 이 헤더에
 *       <b>덧붙이지</b> 지우지 않으므로, 첫 항목은 클라이언트가 처음 보낸 값이다. 역시
 *       요청마다 바꿀 수 있다.</li>
 * </ul>
 *
 * <p>지금의 우선순위:
 *
 * <ol>
 *   <li>{@code User-Id} — JwtAuthorization 이 <b>검증 뒤에</b> 넣는다. 클라이언트가 보낸
 *       동명 헤더는 {@code IdentityHeaderScrubFilter} 가 그보다 먼저 지우므로, 여기 도달한
 *       값은 게이트웨이 것이다.</li>
 *   <li>{@code CF-Connecting-IP} — Cloudflare 엣지가 <b>실제 접속 IP 로 덮어쓴다.</b>
 *       클라이언트가 같은 이름을 보내도 엣지에서 교체되므로 조작할 수 없다. 이 배포는
 *       cloudflared 터널 뒤에 있어 모든 외부 요청이 이 헤더를 갖는다.</li>
 *   <li>소켓 원격 주소 — 터널이 없는 경로(포트포워드·클러스터 내부). 프록시 뒤라면 전부
 *       같은 IP 로 보이겠지만, 그건 "제한이 조금 거친 것"이지 "제한이 없는 것"이 아니다.
 *       후자보다 전자가 낫다.</li>
 * </ol>
 *
 * <p>키는 절대 null/empty 가 되지 않는다(예전에 NPE 로 파이프라인이 죽은 적이 있다).
 */
@Configuration
public class CustomUserKeyResolver {

    static final String ANONYMOUS = "anonymous";

    /** Cloudflare 가 엣지에서 채우는 실제 접속 IP. 클라이언트가 보낸 값은 엣지가 덮어쓴다. */
    static final String CF_CONNECTING_IP = "CF-Connecting-IP";

    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("User-Id");
            if (StringUtils.hasText(userId)) {
                return Mono.just("user:" + userId);
            }

            String edgeIp = exchange.getRequest().getHeaders().getFirst(CF_CONNECTING_IP);
            if (StringUtils.hasText(edgeIp)) {
                return Mono.just("ip:" + edgeIp.trim());
            }

            if (exchange.getRequest().getRemoteAddress() != null
                    && exchange.getRequest().getRemoteAddress().getAddress() != null) {
                return Mono.just("ip:" + exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
            }

            return Mono.just(ANONYMOUS);
        };
    }
}
