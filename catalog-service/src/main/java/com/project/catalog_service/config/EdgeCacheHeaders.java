package com.project.catalog_service.config;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 앞단(Cloudflare) 캐시 표시 — 사용자를 보지 않는 공개 GET 의 200 만 {@code public}, 나머지는 모두 {@code no-store}(docs/91 §2-9).
 *
 * <p>왜 서버가 정하나: 앞단 규칙만으로 {@code /catalog-service/api/*} 를 캐시하면 재고 · 장바구니용 조회까지 섞이고, 표시가 없는 응답은
 * 앞단의 기본 수명(200 이면 몇 시간)으로 남는다. 그래서 <b>기본은 no-store</b> 이고, 아래 목록의 경로가 200 으로 답할 때만 바꾼다.
 * 목록의 엔드포인트는 {@code User-Id} · 쿠키 · 언어를 보지 않는다(가드 {@code EdgeCacheHeadersTest} 가 컨트롤러 시그니처를 본다).
 *
 * <ul>
 *   <li>{@code max-age=0} — 브라우저는 지금처럼 매번 받는다(관리자가 고친 것 · 재고가 새로고침에 바로 보인다).</li>
 *   <li>{@code s-maxage} — 앞단만 짧게(기본 30초 — 첫 화면의 파드 메모리 캐시 {@code HomeViewCache} 와 같은 크기). 재고 표시는 그만큼
 *       늦을 수 있다 — 주문 · 결제는 서버가 재고를 다시 본다.</li>
 *   <li>{@code stale-while-revalidate} — 만료 직후에는 옛 응답을 주며 뒤에서 새로 받는다(원본이 잠깐 느려도 손님은 기다리지 않는다).</li>
 *   <li>{@code probe} 쿼리가 있으면 no-store — 바깥 탐침(site-probe)이 캐시가 아니라 원본을 재게. 쿼리가 다르면 앞단의 캐시 키도 다르다.</li>
 *   <li>오류(200 밖)는 no-store — 드문 필터 조합의 503 을 30초 동안 모두에게 돌려주지 않는다.</li>
 * </ul>
 *
 * <p>끄기: {@code catalog.edge-cache.enabled=false}(모두 no-store) 또는 앞단의 캐시 규칙을 끈다.
 *
 * <p>[JA] エッジ(Cloudflare)キャッシュの指示 — 利用者を見ない公開 GET の 200 だけ public、ほかはすべて no-store。ブラウザーは
 * max-age=0 でこれまでどおり毎回取得し、エッジだけが短く(既定 30 秒)持つ。probe クエリ付きと 200 以外は no-store。
 */
@ControllerAdvice
public class EdgeCacheHeaders implements HandlerInterceptor, ResponseBodyAdvice<Object> {

    static final String NO_STORE = "no-store";
    /** 바깥 탐침이 붙이는 쿼리 — 이 이름이 있으면 캐시하지 않는다(prometheus.oci.yml 의 site-probe 대상). */
    static final String PROBE_PARAM = "probe";

    /**
     * 캐시해도 되는 공개 GET(게이트웨이의 RewritePath 뒤 경로). 화면이 부르는 것만 — 서비스 사이 호출(/api/{id} · products/info ·
     * cart/* · stock · color*)과 관리자(/api/content/admin/**) · 내부(/api/internal/**)는 넣지 않는다.
     */
    static final List<String> PUBLIC_GET = List.of(
            "/api/content/home",
            "/api/content/banners",
            "/api/content/notices",
            "/api/content/notices/{id}",
            "/api/content/events",
            "/api/content/events/{slug}",
            "/api/category/categories",
            "/api/category/product/allsubcategories",
            "/api/category/product/subcategories",
            "/api/product/frontpage",
            "/api/product/products",
            "/api/product/{slug}",
            "/api/search",
            "/api/search/category");

    private static final List<PathPattern> PATTERNS = PUBLIC_GET.stream()
            .map(PathPatternParser.defaultInstance::parse).toList();
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final boolean enabled;
    private final String publicValue;

    public EdgeCacheHeaders(@Value("${catalog.edge-cache.enabled:true}") boolean enabled,
                            @Value("${catalog.edge-cache.s-maxage:30s}") Duration sMaxAge,
                            @Value("${catalog.edge-cache.stale-while-revalidate:30s}") Duration staleWhileRevalidate) {
        if (sMaxAge.isNegative() || sMaxAge.isZero() || staleWhileRevalidate.isNegative()) {
            throw new IllegalArgumentException("catalog.edge-cache: s-maxage 는 0 보다 커야 하고 stale-while-revalidate 는 0 이상");
        }
        this.enabled = enabled;
        this.publicValue = "public, max-age=0, s-maxage=" + sMaxAge.toSeconds()
                + (staleWhileRevalidate.isZero() ? "" : ", stale-while-revalidate=" + staleWhileRevalidate.toSeconds());
    }

    /** 응답을 시작하기 전에 기본값 no-store — 예외 · 빈 본문 · 목록 밖은 이대로 나간다. */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        return true;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /** 본문을 쓰기 직전 — 상태 코드가 정해진 뒤다. 목록의 GET 이 200 이면 public 으로 바꾼다. */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest req && response instanceof ServletServerHttpResponse res) {
            HttpServletResponse servlet = res.getServletResponse();
            String value = decide(req.getServletRequest(), servlet.getStatus());
            // setHeader 는 덮어쓴다 — preHandle 의 no-store 와 두 줄이 되지 않게(getHeaders().set 은 add 로 쓰인다)
            servlet.setHeader(HttpHeaders.CACHE_CONTROL, value);
        }
        return body;
    }

    /** 이 요청 · 상태 코드에 붙일 Cache-Control. */
    String decide(HttpServletRequest request, int status) {
        if (!enabled || status != 200 || request.getParameter(PROBE_PARAM) != null) {
            return NO_STORE;
        }
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return NO_STORE;
        }
        return isPublicPath(PATHS.getPathWithinApplication(request)) ? publicValue : NO_STORE;
    }

    static boolean isPublicPath(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return PATTERNS.stream().anyMatch(p -> p.matches(container));
    }
}
