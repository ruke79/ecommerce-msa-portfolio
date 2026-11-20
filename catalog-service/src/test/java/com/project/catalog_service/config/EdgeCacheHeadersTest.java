package com.project.catalog_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앞단 캐시 표시(docs/91 §2-9) — 목록의 공개 GET 200 만 public, 나머지는 no-store.
 *
 * <p>캐시가 다른 손님의 응답을 섞는 사고를 막는 것이 핵심이라, 동작(MockMvc)과 함께 <b>목록의 엔드포인트가 사용자 · 쿠키 · 헤더를
 * 읽지 않는지</b>를 실제 컨트롤러의 시그니처로 본다 — 누가 상품 상세에 {@code User-Id} 를 읽게 바꾸면 여기서 실패한다.
 *
 * <p>[JA] エッジキャッシュの指示 — 一覧の公開 GET の 200 だけ public。一覧のエンドポイントが利用者 · Cookie · ヘッダーを読まないことも見る。
 */
@DisplayName("앞단 캐시 표시 — 공개 GET 200 만 public, 나머지 no-store (docs/91 §2-9)")
class EdgeCacheHeadersTest {

    private static final String PUBLIC = "public, max-age=0, s-maxage=30, stale-while-revalidate=30";

    /** 경로만 흉내 낸 컨트롤러 — 실제 목록 경로와 목록 밖 경로, 오류 응답. */
    // [JA] パスだけ真似たコントローラ — 実際の一覧のパスと一覧外のパス、エラー応答。
    @RestController
    static class Fake {
        @GetMapping("/api/content/home")
        ResponseEntity<?> home() { return ResponseEntity.ok(Map.of("sections", List.of())); }

        @GetMapping("/api/product/{slug}")
        ResponseEntity<?> product(@PathVariable("slug") String slug) {
            return "broken".equals(slug) ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("m", "busy"))
                    : ResponseEntity.ok(Map.of("slug", slug));
        }

        @GetMapping("/api/search")
        ResponseEntity<?> search() { return ResponseEntity.ok(Map.of("products", List.of())); }

        @GetMapping("/api/stock")
        ResponseEntity<?> stock() { return ResponseEntity.ok(Map.of("qty", 3)); }

        @GetMapping("/api/content/admin/banners")
        ResponseEntity<?> adminBanners() { return ResponseEntity.ok(List.of()); }

        @GetMapping("/api/content/events/{slug}/coupon-offer")
        ResponseEntity<?> couponOffer(@PathVariable("slug") String slug) { return ResponseEntity.ok(Map.of()); }

        @PostMapping("/api/search")
        ResponseEntity<?> postSearch() { return ResponseEntity.ok(Map.of()); }

        /** 본문 없이 끝나는 응답 — 본문 advice 를 거치지 않으므로 인터셉터의 기본값만 남는다. */
        // [JA] 本文なしで終わる応答 — 本文の advice を通らないので、インターセプタの既定値だけが残る。
        @GetMapping("/api/search/category")
        void raw(jakarta.servlet.http.HttpServletResponse response) { response.setStatus(204); }
    }

    private static MockMvc mvc(EdgeCacheHeaders headers) {
        return MockMvcBuilders.standaloneSetup(new Fake())
                .addInterceptors(headers)
                .setControllerAdvice(headers)
                .build();
    }

    private static EdgeCacheHeaders on() {
        return new EdgeCacheHeaders(true, Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    private static List<String> cacheControl(MvcResult r) {
        return r.getResponse().getHeaders(HttpHeaders.CACHE_CONTROL);
    }

    @Test
    @DisplayName("🔴 목록의 공개 GET 200 은 public · 브라우저 max-age=0 · 앞단 s-maxage — Cache-Control 은 한 줄")
    void publicGetIsEdgeCacheable() throws Exception {
        MockMvc mvc = mvc(on());
        for (String path : List.of("/api/content/home", "/api/product/blue-shirt", "/api/search?category=16&page=1")) {
            MvcResult r = mvc.perform(get(path)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(cacheControl(r)).as(path).containsExactly(PUBLIC);
        }
    }

    @Test
    @DisplayName("🔴 목록 밖(재고 · 관리자 · 쿠폰 제안) · POST · 오류 · 탐침은 no-store — 앞단의 기본 수명으로 남지 않게")
    void everythingElseIsNoStore() throws Exception {
        MockMvc mvc = mvc(on());
        for (String path : List.of("/api/stock", "/api/content/admin/banners", "/api/content/events/x/coupon-offer",
                "/api/product/broken", "/api/content/home?probe=site-probe")) {
            MvcResult r = mvc.perform(get(path)).andReturn();
            assertThat(cacheControl(r)).as(path).containsExactly("no-store");
        }
        assertThat(cacheControl(mvc.perform(post("/api/search")).andReturn())).containsExactly("no-store");
        // 본문 advice 를 거치지 않는 응답(예외 처리 · 직접 쓴 상태 코드)은 인터셉터의 기본값이 지킨다 — 목록의 경로여도
        // [JA] 本文の advice を通らない応答(例外処理・直接書いたステータスコード)はインターセプタの既定値が守る — 一覧のパスであっても
        assertThat(cacheControl(mvc.perform(get("/api/search/category")).andReturn())).containsExactly("no-store");
    }

    @Test
    @DisplayName("🔴 WebMvcConfig 가 이 인터셉터를 /api/** 에 건다 — 빠지면 예외 · 빈 응답에 표시가 없어 앞단의 기본 수명으로 남는다")
    void interceptorIsRegistered() {
        EdgeCacheHeaders headers = on();
        WebMvcConfig config = new WebMvcConfig(org.mockito.Mockito.mock(AdminWriteInterceptor.class), headers);
        class Registry extends org.springframework.web.servlet.config.annotation.InterceptorRegistry {
            List<Object> all() { return getInterceptors(); }
        }
        Registry registry = new Registry();
        config.addInterceptors(registry);
        assertThat(registry.all()).anySatisfy(i -> assertThat(String.valueOf(
                org.springframework.test.util.ReflectionTestUtils.getField(i, "interceptor"))).isEqualTo(String.valueOf(headers)));
    }

    @Test
    @DisplayName("🔴 끄면(catalog.edge-cache.enabled=false) 목록의 GET 도 no-store")
    void disabledMeansNoStore() throws Exception {
        MockMvc mvc = mvc(new EdgeCacheHeaders(false, Duration.ofSeconds(30), Duration.ofSeconds(30)));
        assertThat(cacheControl(mvc.perform(get("/api/content/home")).andReturn())).containsExactly("no-store");
    }

    @Test
    @DisplayName("수명은 설정값 — s-maxage 0 은 기동 실패, stale-while-revalidate 0 이면 그 지시를 뺀다")
    void durationsComeFromConfig() {
        org.springframework.mock.web.MockHttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/content/home");
        assertThat(new EdgeCacheHeaders(true, Duration.ofSeconds(60), Duration.ZERO).decide(req, 200))
                .isEqualTo("public, max-age=0, s-maxage=60");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new EdgeCacheHeaders(true, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 목록이 실제 컨트롤러와 맞는가 ─────────────────────────────────────────────────────────────
    // [JA] ── 一覧が実際のコントローラと合っているか ──

    /** catalog 컨트롤러의 GET 엔드포인트: 경로 → 메서드. */
    // [JA] catalog コントローラの GET エンドポイント: パス → メソッド。
    private static Map<String, Method> realGetEndpoints() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, Method> out = new java.util.TreeMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.project.catalog_service.controller")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String prefix = base == null || base.value().length == 0 ? "" : base.value()[0];
            for (Method m : type.getDeclaredMethods()) {
                GetMapping g = AnnotatedElementUtils.findMergedAnnotation(m, GetMapping.class);
                if (g == null) {
                    continue;
                }
                for (String p : g.value().length == 0 ? new String[] {""} : g.value()) {
                    String path = ("/" + prefix + "/" + p).replaceAll("/+", "/");
                    out.put(path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path, m);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("🔴 목록의 경로는 모두 실제 catalog GET 이고, 그 핸들러는 헤더 · 쿠키 · 요청 객체를 읽지 않는다(손님마다 같은 응답)")
    void listedEndpointsExistAndIgnoreTheCaller() throws Exception {
        Map<String, Method> real = realGetEndpoints();
        assertThat(real).as("catalog 컨트롤러 스캔").hasSizeGreaterThan(15);
        List<String> problems = new ArrayList<>();
        for (String listed : EdgeCacheHeaders.PUBLIC_GET) {
            Method m = real.get(listed);
            if (m == null) {
                problems.add(listed + " — 이런 GET 이 없다(경로가 바뀌면 목록도)");
                continue;
            }
            for (Parameter p : m.getParameters()) {
                boolean readsCaller = p.isAnnotationPresent(RequestHeader.class) || p.isAnnotationPresent(CookieValue.class)
                        || jakarta.servlet.http.HttpServletRequest.class.isAssignableFrom(p.getType())
                        || java.security.Principal.class.isAssignableFrom(p.getType())
                        || java.util.Locale.class.isAssignableFrom(p.getType());
                if (readsCaller) {
                    problems.add(listed + " — " + m.getName() + " 가 " + p.getType().getSimpleName() + " 를 읽는다(손님마다 다른 응답이면 캐시 금지)");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("🔴 관리자 · 내부 · 재고 · 장바구니 · 서비스 사이 조회 경로는 목록 밖이다")
    void privateOrServicePathsAreNeverPublic() throws Exception {
        Set<String> leaked = new TreeSet<>();
        for (String path : realGetEndpoints().keySet()) {
            String concrete = path.replaceAll("\\{[^}]+}", "x");
            boolean shouldStayPrivate = concrete.startsWith("/api/content/admin") || concrete.startsWith("/api/internal")
                    || concrete.startsWith("/api/cart") || concrete.equals("/api/stock") || concrete.contains("/info")
                    || concrete.startsWith("/api/color") || concrete.endsWith("/coupon-offer") || concrete.equals("/api/x");
            if (shouldStayPrivate && EdgeCacheHeaders.isPublicPath(concrete)) {
                leaked.add(path);
            }
        }
        assertThat(leaked).isEmpty();
    }
}
