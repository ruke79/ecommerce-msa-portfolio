package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 내부 전용 엔드포인트의 마지막 방어선(B-80)과, 그 방어선을 enforce 로 올릴 전제(B-81).
 *
 * <p><b>B-80.</b> 서비스 간 mTLS 의 경로 규칙({@code INTERNAL_AUTH_PATH_RULES}, docs/57 §16)은 <b>신원 헤더가 있는 요청은 건너뛴다</b> —
 * 게이트웨이를 거쳐 온 사용자 요청이기 때문이다. 그래서 외부(게이트웨이 경유)에서 내부 전용 경로를 부를 때 실제로 막는 것은 그
 * 엔드포인트의 {@code InternalCallGuard.allowed(...)} (또는 catalog 의 {@code AdminWriteInterceptor}) 하나뿐이다. 그 줄 하나가
 * 빠지면 로그인한 아무나 다른 사람의 이메일 · 구매 집계를 읽는다. 여기서는 규칙의 모든 경로에 대해 그 호출이 있는지, 그리고
 * {@code /internal/} 을 가진 엔드포인트가 규칙에 빠짐없이 있는지 본다.
 *
 * <p><b>B-81.</b> identity enforce(docs/57 §16 "신원 헤더")는 내부 서비스가 보낸 {@code User-Role} · {@code User-Email} 을 버린다 —
 * 서비스는 사용자를 <b>대신할</b> 수만 있고(User-Id) 역할을 주장할 수는 없다. 지금 Feign 이 그 둘을 싣지 않으므로 enforce 로
 * 올려도 동작이 바뀌지 않는다. 그 전제가 코드에 없으면 누군가 Feign 에 역할을 실은 날 enforce 가 그 호출을 조용히 깨뜨린다.
 *
 * <p>[JA] B-80: パス規則は身元ヘッダーのあるリクエストを飛ばすので、ゲートウェイ経由で内部専用パスを呼ばれたとき実際に止めるのは
 * エンドポイントの InternalCallGuard.allowed だけ。規則の全パスにその呼び出しがあるか、/internal/ を持つエンドポイントが規則に
 * 漏れなくあるかを見る。B-81: identity enforce は内部サービスが送った User-Role · User-Email を捨てる — Feign がそれを載せない
 * ことが enforce へ上げる前提なので、それを固定する。
 */
@DisplayName("내부 전용 엔드포인트 — B-80 컨트롤러 판정 전수 · B-81 Feign 은 역할을 싣지 않는다")
class InternalEndpointGuardTest {

    /** 인터셉터가 맡는 경로 — 모듈 → (경로 앞부분, 그 인터셉터 파일). 그 파일이 앞부분 상수와 InternalCallGuard.allowed 를 가져야 한다. */
    // [JA] インターセプターが受け持つパス。
    private static final Map<String, Map<String, String>> INTERCEPTED = Map.of(
            "catalog-service", Map.of(
                    "/api/internal/", "catalog-service/src/main/java/com/project/catalog_service/config/AdminWriteInterceptor.java",
                    "/api/product/rating", "catalog-service/src/main/java/com/project/catalog_service/config/AdminWriteInterceptor.java"));

    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\s*\\(([^)]*)\\)[^{;]*?\\bclass\\s");
    private static final Pattern METHOD_MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\b(\\s*\\(([^)]*)\\))?");
    private static final Pattern LITERAL = Pattern.compile("\"([^\"]*)\"");

    record Endpoint(String module, String file, String path, String body) {
    }

    private static Path root() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    /** 모든 모듈의 src/main/java 아래 .java — node_modules · build 는 걷지 않는다. */
    static List<Path> mainSources() {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root())) {
            for (Path module : modules.filter(m -> Files.isDirectory(m.resolve("src/main/java"))).toList()) {
                try (Stream<Path> files = Files.walk(module.resolve("src/main/java"))) {
                    files.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 주석을 지운 소스 — 주석 속 예시(@RequestHeader("User-Email") 등)에 속지 않게. 문자열 속 "lb://" 는 공백 뒤가 아니라 남는다. */
    static String code(String src) {
        String noBlock = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(src).replaceAll(" ");
        return noBlock.replaceAll("(?m)(^|\\s)//.*$", "$1");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 규칙의 HTTP 경로 앞부분들(gRPC 는 InternalCallerFilter 가 직접 막아 컨트롤러가 없다). */
    static List<String> rulePrefixes() {
        String cm = read(root().resolve("k8s/10-configmap.yaml"));
        Matcher m = Pattern.compile("INTERNAL_AUTH_PATH_RULES:\\s*\"([^\"]+)\"").matcher(cm);
        assertThat(m.find()).as("k8s/10-configmap.yaml 에 INTERNAL_AUTH_PATH_RULES 가 있다").isTrue();
        List<String> prefixes = new ArrayList<>();
        for (String piece : m.group(1).split(";")) {
            String prefix = piece.substring(0, piece.indexOf('=')).trim();
            if (prefix.startsWith("/api/")) {
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }

    /** 매핑 애너테이션 괄호 안에서 경로들만 — value/path 의 문자열(배열이면 전부), 없으면 "". */
    static List<String> paths(String args) {
        if (args == null || args.isBlank()) {
            return List.of("");
        }
        String a = args.trim().replaceFirst("^(value|path)\\s*=\\s*", "");
        String part;
        if (a.startsWith("{")) {
            part = a.substring(0, a.indexOf('}') + 1);
        } else if (a.startsWith("\"")) {
            part = a.substring(0, a.indexOf('"', 1) + 1);
        } else {
            return List.of("");   // produces = ... 만 있다
        }
        List<String> out = new ArrayList<>();
        Matcher m = LITERAL.matcher(part);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out.isEmpty() ? List.of("") : out;
    }

    static String join(String base, String sub) {
        String p = ("/" + base + "/" + sub).replaceAll("/+", "/");
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /** 모든 서비스의 컨트롤러 엔드포인트와 그 메서드 본문(다음 매핑 애너테이션까지). */
    static List<Endpoint> endpoints() {
        List<Endpoint> out = new ArrayList<>();
        mainSources().forEach(p -> {
            String src = code(read(p));
            if (!src.contains("@RestController") && !src.contains("@Controller")) {
                return;
            }
            String module = root().relativize(p).getName(0).toString();
            int classAt = src.indexOf(" class ");
            String base = "";
            Matcher cm = CLASS_MAPPING.matcher(src);
            if (cm.find() && cm.start() < classAt + 7) {
                base = paths(cm.group(1)).get(0);
            }
            Matcher mm = METHOD_MAPPING.matcher(src);
            List<int[]> spans = new ArrayList<>();
            List<String> args = new ArrayList<>();
            while (mm.find()) {
                if (mm.start() < classAt) {
                    continue;   // 클래스의 @RequestMapping
                }
                spans.add(new int[] {mm.start(), mm.end()});
                args.add(mm.group(3));
            }
            for (int i = 0; i < spans.size(); i++) {
                int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : src.length();
                String body = src.substring(spans.get(i)[1], end);
                for (String sub : paths(args.get(i))) {
                    out.add(new Endpoint(module, root().relativize(p).toString(), join(base, sub), body));
                }
            }
        });
        return out;
    }

    private static boolean intercepted(Endpoint e) {
        return INTERCEPTED.getOrDefault(e.module(), Map.of()).keySet().stream().anyMatch(e.path()::startsWith);
    }

    @Test
    @DisplayName("파서 — 클래스 · 메서드 매핑을 이어 붙인다(배열 · produces · 겹친 슬래시)")
    void parserReadsMappings() {
        assertThat(paths("{\"/public/verify-2fa\", \"/public/oauth2/verify-2fa\"}"))
                .containsExactly("/public/verify-2fa", "/public/oauth2/verify-2fa");
        assertThat(paths("value = \"/a\", produces = \"text/plain\"")).containsExactly("/a");
        assertThat(paths("produces = \"text/plain\"")).containsExactly("");
        assertThat(join("api/order/", "/internal/purchased")).isEqualTo("/api/order/internal/purchased");
        assertThat(code("a // @GetMapping(\"/x\")\n/* @RequestHeader(\"User-Role\") */ b \"lb://svc\""))
                .doesNotContain("@GetMapping", "User-Role").contains("lb://svc");
        // 실제 소스에서 알려진 내부 엔드포인트를 찾는다 — 파서가 아무것도 못 찾으면 아래 두 테스트가 헛돈다.
        assertThat(endpoints()).extracting(Endpoint::path)
                .contains("/api/order/internal/purchase-stats", "/api/auth/user/emails", "/api/internal/stock/reservations");
    }

    @Test
    @DisplayName("🔴 B-80: 경로 규칙의 모든 엔드포인트가 InternalCallGuard.allowed 로 판정한다 — 규칙이 신원 헤더 있는 요청을 건너뛰므로")
    void everyRuledEndpointChecksTheCaller() {
        List<Endpoint> all = endpoints();
        List<String> unguarded = new ArrayList<>();
        for (String prefix : rulePrefixes()) {
            List<Endpoint> matched = all.stream().filter(e -> e.path().startsWith(prefix)).toList();
            assertThat(matched).as("규칙 %s 에 해당하는 엔드포인트가 없다 — 경로가 바뀌었으면 규칙(k8s/10)도 고친다", prefix).isNotEmpty();
            for (Endpoint e : matched) {
                if (!intercepted(e) && !e.body().contains("InternalCallGuard.allowed(")) {
                    unguarded.add(e.path() + " (" + e.file() + ")");
                }
            }
        }
        assertThat(unguarded).as("InternalCallGuard.allowed 없이 열린 내부 전용 엔드포인트").isEmpty();
    }

    @Test
    @DisplayName("🔴 B-80: /internal/ 을 가진 엔드포인트는 모두 경로 규칙에 있다 — 새 내부 경로가 규칙 밖에 생기지 않게")
    void everyInternalPathIsRuled() {
        List<String> prefixes = rulePrefixes();
        List<String> missing = endpoints().stream()
                .filter(e -> e.path().contains("/internal/") || e.path().endsWith("/internal"))
                .filter(e -> prefixes.stream().noneMatch(e.path()::startsWith))
                .map(e -> e.path() + " (" + e.file() + ")")
                .toList();
        assertThat(missing).as("INTERNAL_AUTH_PATH_RULES 에 없는 내부 경로 — 규칙과 docs/57 §16 표에 더한다").isEmpty();
    }

    @Test
    @DisplayName("B-80: 인터셉터가 맡는 경로 — 그 인터셉터가 경로 상수와 InternalCallGuard.allowed 를 가진다")
    void interceptorsReallyGuard() {
        INTERCEPTED.forEach((module, byPrefix) -> byPrefix.forEach((prefix, file) -> {
            String src = read(root().resolve(file));
            assertThat(src).as(file).contains("\"" + prefix + "\"", "InternalCallGuard.allowed(");
        }));
        assertThat(read(root().resolve("catalog-service/src/main/java/com/project/catalog_service/config/WebMvcConfig.java")))
                .contains("AdminWriteInterceptor", ".addPathPatterns(\"/api/**\")");
    }

    @Test
    @DisplayName("🔴 B-81: Feign(클라이언트 · RequestInterceptor)은 User-Role · User-Email 을 싣지 않는다 — identity enforce 의 전제")
    void feignDoesNotClaimRoleOrEmail() {
        List<String> offenders = new ArrayList<>();
        mainSources().forEach(p -> {
            String src = code(read(p));
            boolean feign = src.contains("@FeignClient") || src.contains("RequestInterceptor")
                    || src.contains("requestTemplate.header(") || src.contains("RequestTemplate");
            if (feign && (src.contains("\"User-Role\"") || src.contains("\"User-Email\""))) {
                offenders.add(root().relativize(p).toString());
            }
        });
        assertThat(offenders)
                .as("Feign 이 역할 · 이메일 헤더를 싣는다 — identity enforce 가 그것을 버린다(docs/57 §16). 받는 쪽이 User-Id 로 조회하게 한다")
                .isEmpty();
        // 대신할 수 있는 것은 User-Id 뿐 — 그 길이 실제로 쓰이고 있다(장바구니 조회).
        assertThat(read(root().resolve("order-service/src/main/java/com/project/order_service/client/CartServiceClient.java")))
                .contains("@RequestHeader(\"User-Id\")");
    }
}
