package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

/**
 * 🔴 B-108: {@code @Transactional} 이 <b>붙어 있는데 적용되지 않는</b> 자리를 모든 서비스에서 막는다.
 *
 * <p>Spring 의 트랜잭션은 프록시가 건다. 그래서 다음 자리의 어노테이션은 아무 일도 하지 않는다 — 코드만 보면 트랜잭션이 있는 것처럼 읽혀
 * 잘못 믿게 된다(2026-11-03 전수 조사: 312곳 중 private 6 · {@code @PostConstruct} 1 · 받는 쪽 설정을 건너뛴 자기 호출 9).
 * <ol>
 *   <li><b>private · static · final 메서드</b> — CGLIB 프록시가 가로챌 수 없다. (package-private · protected 는 Spring 6 에서 적용된다.)</li>
 *   <li><b>{@code @PostConstruct}</b> — 프록시가 생기기 전에 대상 객체에서 바로 불린다.</li>
 *   <li><b>자기 호출</b> — 같은 클래스 안에서 {@code foo()} · {@code this.foo()} 로 부르면 프록시를 지나지 않는다. 부르는 쪽에 트랜잭션이
 *       없거나(또는 NOT_SUPPORTED · NEVER) 받는 쪽이 다른 전파(REQUIRES_NEW 등)를 원하면 받는 쪽의 설정은 무시된다. 그런 곳은
 *       TransactionTemplate 으로 열거나(람다 안의 호출은 이 가드가 본다), 트랜잭션을 입구 메서드로 옮긴다.
 *       부르는 쪽 트랜잭션에 그냥 합류하는 경우(readOnly · timeout 차이만)는 프록시를 지나도 같은 결과라 막지 않는다.</li>
 * </ol>
 *
 * <p>[JA] B-108: 付いているのに効かない @Transactional を防ぐ — private · static · final、@PostConstruct、プロキシを通らない自己呼び出し。
 */
@DisplayName("B-108: @Transactional 이 적용되지 않는 자리(private · @PostConstruct · 자기 호출)")
class TransactionalProxyGuardTest {

    /** 받는 쪽이 이 전파면 합류가 아니라 다른 동작을 원한 것이다 — 자기 호출로는 그 동작이 없다. */
    private static final Set<String> OWN_PROPAGATION = Set.of("REQUIRES_NEW", "NOT_SUPPORTED", "NEVER", "NESTED", "MANDATORY");
    /** 부르는 쪽이 이 전파면 트랜잭션이 없다. */
    private static final Set<String> NO_TX_PROPAGATION = Set.of("NOT_SUPPORTED", "NEVER");
    /** 이 이름의 호출에 넘긴 람다 · 메서드 참조 안은 템플릿이 트랜잭션을 연다(TransactionTemplate · TransactionOperations · 각 서비스의 inTransaction). */
    private static final Set<String> TEMPLATE_CALLS = Set.of("execute", "executeWithoutResult", "inTransaction");

    private static final List<Unit> UNITS = new ArrayList<>();

    record Unit(String path, CompilationUnit cu) {
    }

    @BeforeAll
    static void parse() throws IOException {
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                List<Path> files;
                try (Stream<Path> walk = Files.walk(main)) {
                    files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
                }
                for (Path f : files) {
                    String src = Files.readString(f);
                    if (!src.contains("Transactional")) {
                        continue;
                    }
                    ParseResult<CompilationUnit> r = parser.parse(src);
                    String rel = repoRoot().relativize(f).toString();
                    assertThat(r.isSuccessful()).as("파싱 실패 — " + rel + " " + r.getProblems()).isTrue();
                    UNITS.add(new Unit(rel, r.getResult().orElseThrow()));
                }
            }
        }
        assertThat(UNITS).as("@Transactional 을 쓰는 파일을 찾지 못했다 — 경로가 바뀌었나").hasSizeGreaterThan(30);
    }

    @Test
    @DisplayName("🔴 private · static · final 메서드에 @Transactional 이 없다 — 프록시가 가로채지 못한다")
    void noTransactionalOnUnproxyableMethods() {
        List<String> bad = new ArrayList<>();
        for (Unit u : UNITS) {
            for (MethodDeclaration m : u.cu().findAll(MethodDeclaration.class)) {
                if (tx(m).isPresent() && (m.isPrivate() || m.isStatic() || m.isFinal())) {
                    bad.add(u.path() + ":" + line(m) + " " + m.getNameAsString()
                            + (m.isPrivate() ? " (private)" : m.isStatic() ? " (static)" : " (final)"));
                }
            }
        }
        assertThat(bad).as("트랜잭션은 부르는 쪽(입구 메서드)에 두거나 TransactionTemplate 으로 연다").isEmpty();
    }

    @Test
    @DisplayName("🔴 @PostConstruct 메서드에 @Transactional 이 없다 — 프록시 전에 대상 객체에서 불린다")
    void noTransactionalOnPostConstruct() {
        List<String> bad = new ArrayList<>();
        for (Unit u : UNITS) {
            for (MethodDeclaration m : u.cu().findAll(MethodDeclaration.class)) {
                boolean postConstruct = m.getAnnotations().stream().anyMatch(a -> a.getNameAsString().endsWith("PostConstruct"));
                if (postConstruct && tx(m).isPresent()) {
                    bad.add(u.path() + ":" + line(m) + " " + m.getNameAsString());
                }
            }
        }
        assertThat(bad).as("기동 때 트랜잭션이 필요하면 TransactionTemplate 으로(EventPricing.loadOnStartup)").isEmpty();
    }

    @Test
    @DisplayName("🔴 자기 호출이 받는 쪽의 @Transactional 을 건너뛰지 않는다 — 부르는 쪽에 트랜잭션이 없거나 다른 전파를 원할 때")
    void noSelfInvocationThatSkipsTransaction() {
        List<String> bad = new ArrayList<>();
        for (Unit u : UNITS) {
            for (ClassOrInterfaceDeclaration c : u.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                AnnotationExpr classTx = tx(c).orElse(null);
                Map<String, MethodDeclaration> byKey = new HashMap<>();
                for (MethodDeclaration m : c.getMethods()) {
                    byKey.putIfAbsent(m.getNameAsString() + "/" + m.getParameters().size(), m);
                }
                for (MethodDeclaration caller : c.getMethods()) {
                    // private 은 프록시를 지나지 않으므로 클래스 단위 설정도 그 메서드의 것이 아니다 — 하지만 부르는 입구가 클래스 단위
                    // 트랜잭션 안이면 그 안에서 돈다. 클래스 단위가 있으면 그것으로 본다.
                    AnnotationExpr callerTx = tx(caller).orElse(classTx);
                    String callerProp = propagation(callerTx);
                    boolean callerHasTx = callerTx != null && !NO_TX_PROPAGATION.contains(callerProp);
                    for (MethodCallExpr call : caller.findAll(MethodCallExpr.class)) {
                        boolean self = call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr;
                        if (!self || insideTemplate(call, caller)) {
                            continue;
                        }
                        MethodDeclaration callee = byKey.get(call.getNameAsString() + "/" + call.getArguments().size());
                        if (callee == null || callee == caller) {
                            continue;
                        }
                        AnnotationExpr calleeTx = tx(callee).orElse(null);
                        if (calleeTx == null) {
                            continue;   // 받는 쪽에 메서드 단위 설정이 없으면 부르는 쪽의 것을 그대로 쓴다
                        }
                        String calleeProp = propagation(calleeTx);
                        if (calleeProp.equals(callerProp) || (!callerHasTx && NO_TX_PROPAGATION.contains(calleeProp))) {
                            continue;   // 프록시를 지나도 같다 — 둘 다 트랜잭션 없음, 또는 같은 전파
                        }
                        boolean wantsOwn = OWN_PROPAGATION.contains(calleeProp) && !calleeProp.equals(callerProp);
                        if (!callerHasTx || wantsOwn) {
                            bad.add(u.path() + ":" + line(call) + " " + c.getNameAsString() + "." + caller.getNameAsString()
                                    + "[" + (callerTx == null ? "트랜잭션 없음" : callerProp) + "] → " + callee.getNameAsString()
                                    + "[" + calleeProp + "]");
                        }
                    }
                }
            }
        }
        assertThat(bad).as("자기 호출은 프록시를 지나지 않는다 — 입구로 옮기거나 TransactionTemplate 으로 연다").isEmpty();
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private static Optional<AnnotationExpr> tx(NodeWithAnnotations<?> n) {
        return n.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("Transactional") || a.getNameAsString().endsWith(".Transactional"))
                .findFirst();
    }

    /** 전파 이름(REQUIRED 가 기본). 어노테이션 본문에서 Propagation.X 를 읽는다. */
    private static String propagation(AnnotationExpr a) {
        if (a == null) {
            return "NONE";
        }
        String s = a.toString();
        int i = s.indexOf("Propagation.");
        if (i < 0) {
            return "REQUIRED";
        }
        String rest = s.substring(i + "Propagation.".length());
        int end = 0;
        while (end < rest.length() && (Character.isLetter(rest.charAt(end)) || rest.charAt(end) == '_')) {
            end++;
        }
        return rest.substring(0, end);
    }

    /** 이 호출이 템플릿 호출(execute · inTransaction …)에 넘긴 람다 · 메서드 참조 안에 있는가. */
    private static boolean insideTemplate(Node call, Node stop) {
        Node n = call;
        while (n != null && n != stop) {
            Optional<Node> parent = n.getParentNode();
            if ((n instanceof LambdaExpr || n instanceof MethodReferenceExpr) && parent.isPresent()
                    && parent.get() instanceof MethodCallExpr outer && TEMPLATE_CALLS.contains(outer.getNameAsString())) {
                return true;
            }
            n = parent.orElse(null);
        }
        return false;
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(-1);
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        return p;
    }
}
