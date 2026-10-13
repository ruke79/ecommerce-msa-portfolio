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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/29 ACTION=cronjobs — 백업 CronJob(k8s/85)을 <b>적용하고 같은 명령에서 켠다</b>.
 *
 * <p>k8s/85 는 postgres-backup · restore-drill 을 {@code suspend: true} 로 들고 있어, 손으로 patch 해서 켠 뒤 누가 다시
 * {@code kubectl apply -f} 하면 아무 표시 없이 다시 꺼진다. 그래서 적용은 늘 이 동작으로 하고, 켜기 전에 pg_basebackup 이
 * 거절당할 원인(원격 replication 항목 없음)을 확인한다 — 매일 조용히 실패할 백업은 켜지 않는다.
 *
 * <p>[JA] 適用と有効化を一つの操作にまとめているか、replication の許可がなければ何も変えないかを偽の kubectl で確かめる。
 */
@DisplayName("ops/29 cronjobs — 백업 CronJob 적용 + 켜기 · pg_hba 가 막으면 아무것도 바꾸지 않는다")
class EnableBackupCronJobsTest {

    @TempDir
    Path tmp;

    private Path bin;
    private Path calls;

    private record Run(int code, String out) {}

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean bashAvailable() {
        try {
            Process p = new ProcessBuilder("bash", "-c", "true").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
                    && FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @BeforeEach
    void fakeKubectl() throws Exception {
        assumeTrue(bashAvailable(), "bash · POSIX 가 없다");
        bin = Files.createDirectories(tmp.resolve("bin"));
        calls = tmp.resolve("calls.log");
        Files.writeString(calls, "");
        // pg_hba 를 들여다보는 exec 만 HBA_EXIT 로 답하고, 나머지는 기록만 하고 성공한다.
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, String.join("\n",
                "#!/bin/sh",
                "echo \"kubectl $*\" >> '" + calls + "'",
                "case \"$*\" in",
                "    *exec*pg_hba*) exit \"${HBA_EXIT:-0}\" ;;",
                "esac",
                "exit 0") + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Run run(Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", repoRoot().resolve("ops/29-enable-pitr-in-cluster.sh").toString())
                .redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("ACTION", "cronjobs");
        pb.environment().putAll(env);
        Process p = pb.start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private List<String> kubectlCalls() throws IOException {
        return Files.readAllLines(calls);
    }

    private static int indexOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("적용한 뒤 같은 명령에서 postgres-backup · restore-drill 의 suspend 를 끈다")
    void appliesThenEnables() throws Exception {
        Run r = run(Map.of());

        assertThat(r.code()).as(r.out()).isZero();
        List<String> c = kubectlCalls();
        int apply = indexOf(c, "apply -f " + repoRoot().resolve("k8s/85-backup-cronjobs.yaml"));
        int pg = indexOf(c, "-n ecommerce patch cronjob postgres-backup -p {\"spec\":{\"suspend\":false}}");
        int drill = indexOf(c, "-n ecommerce patch cronjob restore-drill -p {\"spec\":{\"suspend\":false}}");
        assertThat(apply).as(String.join("\n", c)).isNotNegative();
        assertThat(pg).as("postgres-backup 을 켜지 않았다").isGreaterThan(apply);
        assertThat(drill).as("restore-drill 을 켜지 않았다").isGreaterThan(apply);
        assertThat(c).noneMatch(l -> l.contains("mongo-backup"));
    }

    @Test
    @DisplayName("원격 replication 항목이 없으면 적용도 켜기도 하지 않는다 — 매일 03:00 에 조용히 실패할 백업")
    void refusesWithoutRemoteReplication() throws Exception {
        Run r = run(Map.of("HBA_EXIT", "1"));

        assertThat(r.code()).isNotZero();
        assertThat(r.out()).contains("원격 replication 항목이 없습니다");
        assertThat(kubectlCalls()).noneMatch(l -> l.contains(" apply ") || l.contains(" patch "));
    }
}
