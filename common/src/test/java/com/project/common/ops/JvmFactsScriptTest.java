package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
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
 * JVM 사실 표(ops/60-jvm-facts.sh) — docs/92 §2.
 *
 * <p>가짜 kubectl 의 {@code exec} 가 파드 대신 <b>이 시험의 실제 JVM</b> 으로 스크립트의 탐침을 돌린다 — 그래서 표의 값은 진짜
 * 에르고노믹스다. 컨테이너 한도는 {@code -XX:ActiveProcessorCount} · {@code -XX:MaxRAM} 으로 흉내 낸다(JVM 이 cgroup 대신 쓰는 입력).
 * 지키는 것: ① 1 코어면 Serial · ForkJoin 병렬도 1, 4 코어면 G1 · 3 — 표가 JVM 의 실제 선택을 옮긴다. ② 최대 힙 = 한도 × 50%(이미지의
 * JVM_MAX_RAM_PERCENTAGE). ③ JAVA_OPTS 의 {@code -Xlog:…:file=} 은 빼고 띄운다 — 돌고 있는 JVM 의 gc.log 를 덮지 않는다.
 * ④ 운영 클러스터는 ALLOW_PROD=1 없이는 파드에 아무것도 하지 않는다. ⑤ 파드가 없거나 JVM 이 못 떠도 다른 서비스는 계속한다.
 *
 * <p>[JA] 偽 kubectl の exec がこのテストの本物の JVM でプローブを回す — 表の値は本物のエルゴノミクス。
 */
@DisplayName("ops/60 — JVM 사실 표 (docs/92 §2)")
class JvmFactsScriptTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix") && Files.isExecutable(Path.of("/bin/bash"));
    }

    private record Run(int code, String out) {
    }

    private void exe(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/bash\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** 가짜 kubectl — 부른 것을 calls 에, exec 는 마지막 인자(탐침)를 이 기계에서 sh -c 로. FAKE_NOPOD · FAKE_EXEC_FAIL 의 서비스는 빠진다. */
    private Run run(Map<String, String> env, String... services) throws Exception {
        assumeTrue(posix(), "bash · POSIX 가 없다");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        exe(bin.resolve("kubectl"), """
                echo "kubectl $*" >> "$FAKE/calls"
                case "$*" in
                  *configmap*ecommerce-config*) echo "${FAKE_URL-https://shop.lab}" ;;
                  *"get pods -l app="*) a="$*"; a="${a#*app=}"; s="${a%% *}"
                     for m in ${FAKE_NOPOD:-}; do [ "$m" = "$s" ] && exit 0; done; echo "$s-pod-1" ;;
                  *nodeName*) echo k3s-agent1 ;;
                  *limits.cpu*) echo 1 ;;
                  *limits.memory*) echo 768Mi ;;
                  *" exec "*)
                     for m in ${FAKE_EXEC_FAIL:-}; do case "$*" in *" exec $m-pod-1 "*) echo "OCI runtime exec failed"; exit 1 ;; esac; done
                     for last; do :; done; sh -c "$last" ;;
                esac
                """);
        List<String> cmd = new ArrayList<>(List.of("bash", repoRoot().resolve("ops/60-jvm-facts.sh").toString()));
        cmd.addAll(List.of(services));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(tmp.toFile()).redirectErrorStream(true);
        // 파드 안의 java 대신 이 시험의 JVM
        pb.environment().put("PATH", bin + ":" + Path.of(System.getProperty("java.home"), "bin") + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE", tmp.toString());
        pb.environment().put("OUT", tmp.resolve("out").toString());
        pb.environment().put("JVM_MAX_RAM_PERCENTAGE", "50");
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("JDK_JAVA_OPTIONS");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private String row(Run r, String service) {
        return r.out().lines().filter(l -> l.startsWith("| " + service + " |")).findFirst().orElse("");
    }

    private String calls() throws IOException {
        Path c = tmp.resolve("calls");
        return Files.exists(c) ? Files.readString(c) : "";
    }

    @Test
    @DisplayName("🔴 1 코어 · 768Mi → Serial · 최대 힙 384M · ForkJoin 1, -Xlog 파일 옵션은 빼고 띄운다(돌고 있는 JVM 의 gc.log 를 덮지 않는다)")
    void oneCoreGivesSerialAndTheLogOptionIsStripped() throws Exception {
        Path gcLog = tmp.resolve("running-jvm-gc.log");
        Run r = run(Map.of("JAVA_OPTS", "-XX:ActiveProcessorCount=1 -XX:MaxRAM=805306368 -Xlog:gc*:file=" + gcLog),
                "catalog-service");
        assertThat(r.code()).as(r.out()).isZero();
        String row = row(r, "catalog-service");
        assertThat(row).as(r.out()).contains("| 1 / 768Mi |").contains("| Serial |").contains("| 384M |");
        assertThat(row.split("\\|")[15].strip()).as("ForkJoin 병렬도 — 본 CPU 1 이면 1").isEqualTo("1");
        assertThat(gcLog).as("-Xlog:…:file= 은 탐침에서 뺀다").doesNotExist();
        assertThat(tmp.resolve("out/jvm-facts.md")).content().contains("| catalog-service | catalog-service-pod-1 |");
        assertThat(tmp.resolve("out/raw/catalog-service.txt")).content().contains("MaxHeapSize");
    }

    @Test
    @DisplayName("🔴 4 코어 → G1 · ForkJoin 3(CPU − 1) — 표는 고정값이 아니라 JVM 의 실제 선택을 옮긴다")
    void fourCoresGiveG1AndForkJoinThree() throws Exception {
        Run r = run(Map.of("JAVA_OPTS", "-XX:ActiveProcessorCount=4 -XX:MaxRAM=805306368"), "gateway-server");
        assertThat(r.code()).as(r.out()).isZero();
        String row = row(r, "gateway-server");
        assertThat(row).as(r.out()).contains("| G1 |").contains("| 4 |");
        assertThat(row.split("\\|")[15].strip()).as("ForkJoin 병렬도").isEqualTo("3");
    }

    @Test
    @DisplayName("운영 클러스터는 ALLOW_PROD=1 없이는 파드에 아무것도 하지 않는다 — 있으면 돈다")
    void productionNeedsExplicitPermission() throws Exception {
        Run refused = run(Map.of("FAKE_URL", "https://www.ysyproject.com", "JAVA_OPTS", "-XX:ActiveProcessorCount=1"), "catalog-service");
        assertThat(refused.code()).isEqualTo(2);
        assertThat(refused.out()).contains("운영 클러스터").contains("ALLOW_PROD=1");
        assertThat(calls()).doesNotContain(" exec ").doesNotContain("get pods");

        Run allowed = run(Map.of("FAKE_URL", "https://www.ysyproject.com", "ALLOW_PROD", "1",
                "JAVA_OPTS", "-XX:ActiveProcessorCount=1 -XX:MaxRAM=805306368"), "catalog-service");
        assertThat(allowed.code()).as(allowed.out()).isZero();
        assertThat(allowed.out()).contains("운영 클러스터").contains("| Serial |");
    }

    @Test
    @DisplayName("파드가 없거나 JVM 을 못 띄운 서비스는 그 줄에 적고, 나머지는 계속한다")
    void missingPodOrFailedProbeDoesNotStopTheOthers() throws Exception {
        Run r = run(Map.of("JAVA_OPTS", "-XX:ActiveProcessorCount=1 -XX:MaxRAM=805306368",
                "FAKE_NOPOD", "config-server", "FAKE_EXEC_FAIL", "chat-service"),
                "config-server", "chat-service", "order-service");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(row(r, "config-server")).contains("파드 없음");
        assertThat(row(r, "chat-service")).contains("JVM 을 띄우지 못했다");
        assertThat(row(r, "order-service")).contains("| Serial |");
    }

    @Test
    @DisplayName("서비스 이름은 영소문자 · 숫자 · - 만 — 파드 안 셸로 가는 값이다")
    void serviceNamesAreValidated() throws Exception {
        Run r = run(Map.of(), "catalog;rm -rf /");
        assertThat(r.code()).isEqualTo(2);
        assertThat(calls()).isEmpty();
    }
}
