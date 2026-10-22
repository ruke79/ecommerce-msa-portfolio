package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/54-bulk-orders.sh(docs/83 §2.4) — 주문 이력은 실습 클러스터에만, 끊긴 적재는 다 들어간 묶음 다음부터, 줄의 상품 범위는 ops/44 의 것.
 * 가짜 kubectl 이 psql 호출 · 받은 SQL 의 첫 줄을 남긴다. 생성기 자체는 order-service 의 DemoOrderGeneratorIntegrationTest 가 실제 DB 로 본다.
 *
 * <p>[JA] 注文履歴は実習クラスタにだけ、途切れた投入は入りきった束の次から。偽の kubectl が psql 呼び出しと受け取った SQL の先頭行を残す。
 */
@DisplayName("ops/54 — 주문 이력 적재(실습 클러스터만 · 이어서)")
class BulkOrdersScriptTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        return Files.exists(p.resolve("ops")) ? p : p.getParent();
    }

    private static boolean runnable() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                && Files.isExecutable(Path.of("/bin/bash")) && Files.isExecutable(Path.of("/usr/bin/python3"));
    }

    private record Run(int code, String out) {}

    private Run run(Map<String, String> env, String... args) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, """
                #!/bin/bash
                echo "kubectl $*" >> "$FAKE/kubectl.log"
                case "$*" in
                  *"get configmap ecommerce-config"*) printf '%s' "${FAKE_URL-https://shop.lab}" ;;
                  *"FROM users.users"*) printf '%s' "${FAKE_USERS:-100}" ;;
                  *"SELECT count(*) FROM orders.orders WHERE"*) printf '%s' "${FAKE_HAVE:-0}" ;;
                  *"config current-context"*) echo lab ;;
                  *"psql"*) if [ ! -t 0 ]; then head -1 >> "$FAKE/sql.log"; cat > /dev/null; fi ;;
                esac
                """, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwxr-xr-x"));
        List<String> cmd = new ArrayList<>(List.of("bash", repoRoot().resolve("ops/54-bulk-orders.sh").toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(tmp.toFile()).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(Files.writeString(tmp.resolve("stdin"), "").toFile()));
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE", tmp.toString());
        pb.environment().put("YES", "1");
        pb.environment().put("FORCE", "1");
        pb.environment().put("USERS", "100");
        pb.environment().put("ORDERS_PER_USER", "5");
        pb.environment().put("CHUNK", "20");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private String log(String name) throws Exception {
        Path f = tmp.resolve(name);
        return Files.exists(f) ? Files.readString(f) : "";
    }

    @Test
    @DisplayName("🔴 운영 클러스터에서는 load · purge 를 거절한다 — 가짜 주문이 매출 · 주문 목록을 더럽힌다")
    void refusesProduction() throws Exception {
        assumeTrue(runnable(), "bash · python3 가 없다");
        for (String cmd : List.of("load", "purge")) {
            Run r = run(Map.of("FAKE_URL", "https://www.ysyproject.com"), cmd);
            assertThat(r.code()).as(cmd).isNotZero();
            assertThat(r.out()).contains("운영 클러스터다");
        }
        assertThat(log("sql.log")).as("SQL 을 한 줄도 보내지 않았다").isEmpty();
    }

    @Test
    @DisplayName("🔴 끊긴 적재는 다 들어간 묶음 다음부터 — 이미 200개(묶음 2개분)면 묶음 2 ~ 4 만, orders DB 로")
    void resumesAfterCompleteChunks() throws Exception {
        assumeTrue(runnable(), "bash · python3 가 없다");
        Run r = run(Map.of("FAKE_HAVE", "200"), "load");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("이 범위에 이미 있는 데모 주문: 200 개").contains("묶음 5/5");
        String sql = log("sql.log");
        assertThat(sql).doesNotContain("묶음 0 ").doesNotContain("묶음 1 ")
                .contains("-- generate-demo-orders: 묶음 2 ").contains("묶음 3 ").contains("묶음 4 ");
        assertThat(log("kubectl.log")).contains("-d orderdb").contains("SELECT count(*) FROM orders.orders WHERE order_id BETWEEN 8000000000 AND 8000000499");
    }

    @Test
    @DisplayName("데모 사용자가 모자라면 알린다 · 숫자가 아닌 값은 거절한다 · 줄의 상품 범위는 ops/44 의 것을 넘긴다")
    void warnsAndValidates() throws Exception {
        assumeTrue(runnable(), "bash · python3 가 없다");
        Run few = run(Map.of("FAKE_USERS", "40"), "load");
        assertThat(few.out()).contains("데모 사용자가 40명");
        Run bad = run(Map.of("USERS", "10; rm -rf /"), "load");
        assertThat(bad.code()).isNotZero();
        assertThat(bad.out()).contains("USERS 는 0 이상의 정수");
        Run est = run(Map.of("PRODUCTS", "10000000"), "estimate");
        assertThat(est.code()).as(est.out()).isZero();
        assertThat(est.out()).contains("주문 500 개").contains("상품 id 1000001 … 11000000");
    }
}
