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
import org.yaml.snakeyaml.Yaml;

/**
 * k8s/85-backup-cronjobs.yaml 의 postgres-backup — 접속 확인이 <b>새 파드의 첫 몇 초 거절을 견디고</b>, 끝내 실패하면
 * <b>psql 의 오류를 그대로</b> 남기는가. 백업이 끝난 뒤 지표를 못 써도 <b>Job 을 실패시키지 않는가</b>(재시도가 백업 전체를
 * 다시 뜬다). restore-drill 은 운영의 postgresql.conf(ssl = on, 데이터 디렉터리 밖 인증서)를 끄고 띄우는가.
 *
 * <p>2026-10-10 운영에서 이 Job 이 매번 'ERROR: … 접속할 수 없습니다' 한 줄로 끝났다. 원인은 NetworkPolicy(k8s/95)를
 * 집행하는 kube-router 가 새 파드의 IP 를 허용 목록에 넣기까지의 몇 초였다 — 첫 시도는 'Connection refused', 5초 뒤에는
 * 성공. 오류를 /dev/null 로 버리고 있어서 그것을 알아내는 데 파드를 세 번 따로 띄워야 했다.
 *
 * <p>매니페스트의 스크립트를 그대로 꺼내 가짜 psql · pg_basebackup 으로 끝까지 돌린다(경로만 임시 디렉터리로).
 *
 * <p>[JA] バックアップ Job が新しい Pod の数秒間の拒否に耐え、最後に失敗したら psql のエラーを残すかを、マニフェストの
 * スクリプトをそのまま偽コマンドで実行して確かめる。
 */
@DisplayName("85-backup · postgres-backup — 첫 거절에 재시도 · 실패하면 psql 오류를 남긴다")
class BackupCronJobScriptTest {

    private static final String REFUSED =
            "psql: error: connection to server at \"postgres.postgres.svc.cluster.local\" (10.43.0.1), port 5432 failed: Connection refused";

    @TempDir
    Path tmp;

    private Path bin;
    private Path script;
    private Path calls;

    private record Run(int code, String out) {}

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean shellAvailable() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v gzip && command -v stat && command -v find").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
                    && FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void exe(Path file, String... lines) throws IOException {
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> podSpec(String cronJob) throws IOException {
        String yaml = Files.readString(repoRoot().resolve("k8s/85-backup-cronjobs.yaml"));
        for (Object doc : new Yaml().loadAll(yaml)) {
            Map<String, Object> m = (Map<String, Object>) doc;
            if (!"CronJob".equals(m.get("kind"))
                    || !cronJob.equals(((Map<String, Object>) m.get("metadata")).get("name"))) {
                continue;
            }
            Map<String, Object> job = (Map<String, Object>) ((Map<String, Object>) m.get("spec")).get("jobTemplate");
            return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) job.get("spec"))
                    .get("template")).get("spec");
        }
        throw new AssertionError(cronJob + " CronJob 을 찾지 못했다");
    }

    @SuppressWarnings("unchecked")
    private static String script(String cronJob) throws IOException {
        Map<String, Object> container = ((List<Map<String, Object>>) podSpec(cronJob).get("containers")).get(0);
        return ((List<String>) container.get("args")).get(0);
    }

    /** 컨테이너의 절대 경로(/tmp · /backup · /metrics)만 임시 디렉터리로 옮긴다. 나머지는 매니페스트 그대로다. */
    private Path localize(String cronJob) throws IOException {
        String body = script(cronJob)
                .replace("/tmp/", tmp.resolve("t") + "/")
                .replace("/backup", tmp.resolve("backup").toString())
                .replace("/metrics", tmp.resolve("metrics").toString());
        Path file = tmp.resolve(cronJob + ".sh");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    @BeforeEach
    void machine() throws Exception {
        assumeTrue(shellAvailable(), "sh · gzip · stat · find · POSIX 가 없다");
        bin = Files.createDirectories(tmp.resolve("bin"));
        calls = tmp.resolve("calls.log");
        Files.writeString(calls, "");
        Files.createDirectories(tmp.resolve("t"));
        // /metrics 는 노드의 hostPath 다 — 디렉터리는 늘 있다(initContainer 가 파일을 미리 만든다).
        Files.createDirectories(tmp.resolve("metrics"));
        script = localize("postgres-backup");

        // 가짜 psql — REFUSE_FIRST 번은 거절(stderr), 그 뒤로는 성공. FAIL_MSG 가 있으면 늘 그 메시지로 실패.
        exe(bin.resolve("psql"),
                "#!/bin/sh",
                "echo \"psql $*\" >> '" + calls + "'",
                "n=$(grep -c '^psql' '" + calls + "')",
                "if [ -n \"${FAIL_MSG:-}\" ]; then echo \"$FAIL_MSG\" >&2; exit 2; fi",
                "if [ \"$n\" -le \"${REFUSE_FIRST:-0}\" ]; then echo '" + REFUSED + "' >&2; exit 2; fi",
                "echo 1");
        // 가짜 pg_basebackup — -D 의 디렉터리에 읽히는 gzip 두 개를 만든다.
        exe(bin.resolve("pg_basebackup"),
                "#!/bin/sh",
                "echo \"pg_basebackup $*\" >> '" + calls + "'",
                "while [ $# -gt 0 ]; do [ \"$1\" = -D ] && dest=\"$2\"; shift; done",
                "echo base | gzip > \"$dest/base.tar.gz\"",
                "echo wal | gzip > \"$dest/pg_wal.tar.gz\"");
    }

    private Run run(Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", script.toString()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("PGHOST", "postgres.postgres.svc.cluster.local");
        pb.environment().put("PGUSER", "postgres");
        pb.environment().put("PGPASSWORD", "test-pw");
        pb.environment().put("RETENTION_DAYS", "14");
        pb.environment().put("CONNECT_INTERVAL", "0");
        pb.environment().putAll(env);
        Process p = pb.start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("스크립트가 끝나지 않는다").isTrue();
        return new Run(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private long count(String prefix) throws IOException {
        return Files.readAllLines(calls).stream().filter(l -> l.startsWith(prefix)).count();
    }

    @Test
    @DisplayName("처음 두 번 거절(새 파드의 정책 반영 지연) → 기다렸다가 붙어 백업을 끝낸다")
    void survivesTheFirstRefusals() throws Exception {
        Run r = run(Map.of("REFUSE_FIRST", "2"));

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("2회 거절 뒤 성공").contains(">>> 완료");
        assertThat(count("psql")).isEqualTo(3);
        assertThat(count("pg_basebackup")).isEqualTo(1);
        assertThat(tmp.resolve("metrics/pg_backup.prom")).content().contains("ecommerce_pg_backup_last_success_timestamp");
    }

    @Test
    @DisplayName("한 번에 붙으면 재시도 줄을 남기지 않는다")
    void quietWhenTheFirstTryWorks() throws Exception {
        Run r = run(Map.of());

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).doesNotContain("거절 뒤 성공");
        assertThat(count("psql")).isEqualTo(1);
    }

    @Test
    @DisplayName("끝내 안 되면 시도 횟수와 psql 의 실제 오류를 남기고 실패 — 백업은 시작하지 않는다")
    void reportsThePsqlErrorWhenItNeverConnects() throws Exception {
        String auth = "psql: error: connection to server failed: FATAL:  password authentication failed for user \"postgres\"";
        Run r = run(Map.of("FAIL_MSG", auth, "CONNECT_ATTEMPTS", "3"));

        assertThat(r.code()).isNotZero();
        assertThat(r.out()).contains("접속할 수 없습니다").contains("3회 시도").contains(auth);
        assertThat(r.out()).as("비밀번호는 어디에도 나오지 않는다").doesNotContain("test-pw");
        assertThat(count("psql")).isEqualTo(3);
        assertThat(count("pg_basebackup")).isZero();
        assertThat(tmp.resolve("metrics/pg_backup.prom")).doesNotExist();
    }

    @Test
    @DisplayName("백업 뒤 지표를 못 써도 성공으로 끝낸다 — 실패로 끝내면 재시도가 0.8GB 백업을 다시 뜬다")
    void metricsFailureDoesNotFailTheBackup() throws Exception {
        // 쓸 수 없는 /metrics — 디렉터리 자리에 파일을 둔다(root 로 도는 테스트에서도 확실히 실패한다).
        Files.delete(tmp.resolve("metrics"));
        Files.writeString(tmp.resolve("metrics"), "");

        Run r = run(Map.of());

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("지표를 쓰지 못했다").contains(">>> 완료");
        assertThat(count("pg_basebackup")).isEqualTo(1);
        assertThat(tmp.resolve("backup/pg/LATEST")).exists();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("지표 파일은 initContainer(root)가 미리 만들어 999 에게 넘긴다 — 본 컨테이너는 999 로 돈다")
    void initContainerHandsTheMetricsFileTo999() throws Exception {
        Map<String, Object> pod = podSpec("postgres-backup");
        assertThat(((Map<String, Object>) pod.get("securityContext")).get("runAsUser")).isEqualTo(999);

        List<Map<String, Object>> inits = (List<Map<String, Object>>) pod.get("initContainers");
        assertThat(inits).as("postgres-backup 에 initContainer 가 없다").isNotNull().hasSize(1);
        Map<String, Object> init = inits.get(0);
        assertThat(((Map<String, Object>) init.get("securityContext")).get("runAsUser")).isEqualTo(0);
        assertThat(String.join(" ", (List<String>) init.get("command")))
                .contains("touch /metrics/pg_backup.prom")
                .contains("chown 999:999 /metrics/pg_backup.prom");
        assertThat((List<Map<String, Object>>) init.get("volumeMounts"))
                .anySatisfy(v -> assertThat(v).containsEntry("name", "metrics").containsEntry("mountPath", "/metrics"));
    }

    /** 백업 하나를 실제 tar.gz 로 만든다(데이터 디렉터리 흉내 — PG_VERSION 하나). */
    private void oneBackup() throws Exception {
        Path data = Files.createDirectories(tmp.resolve("data"));
        Files.writeString(data.resolve("PG_VERSION"), "16\n");
        Path dest = Files.createDirectories(tmp.resolve("backup/pg/20261011T000000Z"));
        Process tar = new ProcessBuilder("tar", "-czf", dest.resolve("base.tar.gz").toString(), "-C", data.toString(), ".")
                .redirectErrorStream(true).start();
        assertThat(tar.waitFor(30, TimeUnit.SECONDS) && tar.exitValue() == 0).isTrue();
        Files.writeString(tmp.resolve("backup/pg/LATEST"), "20261011T000000Z\n");
    }

    /**
     * 가짜 psql — 운영처럼 데이터베이스가 여럿이고 기본 DB(postgres)는 비어 있다. 목록 질의에는 DB 이름을,
     * {@code -d <db>} 의 테이블 수 질의에는 tables 의 값을 돌려준다.
     */
    private void multiDbPsql(Map<String, Integer> tables) throws IOException {
        StringBuilder cases = new StringBuilder();
        tables.forEach((db, n) -> cases.append("    *\"-d ").append(db).append(" \"*) echo ").append(n).append(" ;;\n"));
        exe(bin.resolve("psql"),
                "#!/bin/sh",
                "echo \"psql $*\" >> '" + calls + "'",
                "case \"$*\" in",
                "    *pg_database*) printf '%s\\n' " + String.join(" ", tables.keySet()) + " ;;",
                cases.toString().stripTrailing(),
                "    *) echo 0 ;;",
                "esac");
    }

    private Run drill() throws Exception {
        exe(bin.resolve("pg_ctl"), "#!/bin/sh", "echo \"pg_ctl $*\" >> '" + calls + "'");
        Path drill = localize("restore-drill");
        ProcessBuilder pb = new ProcessBuilder("sh", drill.toString()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("PGUSER", "postgres");
        Process p = pb.start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("restore-drill 은 ssl · archive_mode 를 끄고 복원본을 띄운다 — 운영 conf 의 인증서는 데이터 디렉터리 밖이다")
    void restoreDrillStartsWithoutTheProductionTlsAndArchive() throws Exception {
        oneBackup();
        multiDbPsql(new java.util.LinkedHashMap<>(Map.of("cartdb", 5)));

        Run r = drill();

        assertThat(r.code()).as(r.out()).isZero();
        String start = Files.readAllLines(calls).stream().filter(l -> l.startsWith("pg_ctl") && l.endsWith("start"))
                .findFirst().orElseThrow();
        assertThat(start).contains("-c ssl=off").contains("-c archive_mode=off");
    }

    @Test
    @DisplayName("restore-drill 은 데이터베이스마다 센다 — 기본 DB(postgres)가 비어 있어도 서비스 DB 에 테이블이 있으면 통과")
    void restoreDrillCountsEveryDatabase() throws Exception {
        oneBackup();
        java.util.LinkedHashMap<String, Integer> dbs = new java.util.LinkedHashMap<>();
        dbs.put("cartdb", 5);
        dbs.put("productdb", 7);
        multiDbPsql(dbs);

        Run r = drill();

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("cartdb: 사용자 테이블 5 개").contains("productdb: 사용자 테이블 7 개")
                .contains("복원 훈련 통과 (20261011T000000Z, 사용자 테이블 12 개)");
    }

    @Test
    @DisplayName("restore-drill 은 어느 DB 에도 테이블이 없으면 실패한다")
    void restoreDrillFailsWhenEveryDatabaseIsEmpty() throws Exception {
        oneBackup();
        java.util.LinkedHashMap<String, Integer> dbs = new java.util.LinkedHashMap<>();
        dbs.put("cartdb", 0);
        multiDbPsql(dbs);

        Run r = drill();

        assertThat(r.code()).isNotZero();
        assertThat(r.out()).contains("어느 데이터베이스에도 테이블이 없습니다").doesNotContain("복원 훈련 통과");
    }

    @Test
    @DisplayName("restore-drill 은 끝나면 복원본을 멈추고 지운다 — 통과해도 실패해도(파드가 남는 동안 노드 디스크를 먹는다)")
    void restoreDrillCleansUpTheRestoredCopy() throws Exception {
        oneBackup();
        java.util.LinkedHashMap<String, Integer> dbs = new java.util.LinkedHashMap<>();
        dbs.put("cartdb", 0);
        multiDbPsql(dbs);

        Run failed = drill();

        assertThat(failed.code()).isNotZero();
        assertThat(tmp.resolve("t/restore")).as("실패한 훈련이 복원본을 남겼다").doesNotExist();
        assertThat(Files.readAllLines(calls)).anySatisfy(l -> assertThat(l).startsWith("pg_ctl").contains("stop"));

        multiDbPsql(new java.util.LinkedHashMap<>(Map.of("cartdb", 3)));
        Run passed = drill();

        assertThat(passed.code()).as(passed.out()).isZero();
        assertThat(tmp.resolve("t/restore")).as("통과한 훈련이 복원본을 남겼다").doesNotExist();
    }
}
