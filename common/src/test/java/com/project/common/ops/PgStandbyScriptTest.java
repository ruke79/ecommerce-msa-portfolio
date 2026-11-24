package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/65 — PostgreSQL 스탠바이(k8s/91): primary 준비 · 적용 · 승격 · 되돌리기. 이미 한 단계는 다시 하지 않고, 어긋난 상태면 바꾸기 전에 멈추고,
 * 바꾸는 동작은 한 번에 하나만 돈다(flock). 가짜 kubectl 이 Secret · 계정 · 슬롯 · 설정 · 스탠바이를 파일로 흉내 낸다.
 *
 * <p>[JA] ops/65 — 済んだ段階は二度としない · 食い違いは変える前に止まる · 変える動作は同時に一つ(flock)。偽の kubectl が状態をファイルで真似る。
 */
@DisplayName("ops/65 — PG 스탠바이: 한 번만 · 어긋나면 멈춤 · 동시에 하나 · 슬롯까지 치운다")
class PgStandbyScriptTest {

    @TempDir
    Path tmp;
    Path fake;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean posix() {
        return Files.isExecutable(Path.of("/bin/bash")) && Files.isExecutable(Path.of("/usr/bin/flock"));
    }

    /** 상태 파일: secret · role · slot(타입,active) · keep · standby · pvc · recovery(t/f) · svc · primary_replicas */
    // [JA] 状態ファイル: secret · role · slot(種類,active) · keep · standby · pvc · recovery(t/f) · svc · primary_replicas
    private static final String FAKE_KUBECTL = """
            #!/usr/bin/env bash
            a="$*"
            echo "kubectl $a" >> "$FAKE/kubectl.log"
            f() { [ -e "$FAKE/$1" ]; }
            case "$a" in
              *"get secret pg-replication -o jsonpath={.data.password}"*) base64 < "$FAKE/secret" | tr -d '\\n' ;;
              *"get secret pg-replication"*) f secret || exit 1 ;;
              *"create secret generic pg-replication --from-file=password="*)
                src="${a##*--from-file=password=}"; cp "$src" "$FAKE/secret"; stat -c %a "$src" > "$FAKE/secret.mode" ;;
              *"exec -i deploy/postgres "*"psql"*)
                in="$(cat)"; printf '%s\\n' "$in" >> "$FAKE/stdin.log"
                case "$in" in *"CREATE ROLE replicator"*) echo "true,true" > "$FAKE/role" ;; esac ;;
              *"exec deploy/postgres-standby "*)
                f standby || exit 1
                case "$a" in
                  *"pg_is_in_recovery"*) [ -n "${FAKE_STANDBY_SILENT:-}" ] && exit 1; cat "$FAKE/recovery" ;;
                  *"pg_promote"*) echo f > "$FAKE/recovery"; echo t ;;
                esac ;;
              *"exec deploy/postgres "*)
                case "$a" in
                  *"pg_stat_replication where application_name"*)
                    [ -n "${FAKE_METRICS_FAIL:-}" ] && { echo "connection refused" >&2; exit 1; }
                    echo "${FAKE_METRICS_ROW-1 1 1024 0 1 0.250 2048}" ;;
                  *"from pg_roles where rolname"*) f role && cat "$FAKE/role"; true ;;
                  *"from pg_replication_slots where slot_name"*) f slot && cat "$FAKE/slot"; true ;;
                  *"current_setting('max_slot_wal_keep_size')"*) if f keep; then cat "$FAKE/keep"; else echo -1; fi ;;
                  *"pg_create_physical_replication_slot"*) echo "physical,false" > "$FAKE/slot"; echo "(standby_oci2,)" ;;
                  *"ALTER SYSTEM SET max_slot_wal_keep_size"*) v="${a##*= \\'}"; echo "${v%%\\'*}" > "$FAKE/keep" ;;
                  *"pg_reload_conf"*) echo t ;;
                  *"pg_drop_replication_slot"*) rm -f "$FAKE/slot" ;;
                  *"pg_stat_replication"*) f standby && echo "replication standby-oci2 streaming async replay_lag=-"; true ;;
                  *) echo "unexpected sql: $a" >&2; exit 1 ;;
                esac ;;
              *"get deploy postgres-standby"*) f standby || exit 1 ;;
              *"get pvc pg-standby-data"*) f pvc || exit 1 ;;
              *"get svc postgres -o jsonpath={.spec.selector.app}"*) if f svc; then cat "$FAKE/svc"; else echo postgres; fi ;;
              *"get deploy postgres -o jsonpath={.status.readyReplicas}"*) printf '%s' "${FAKE_PRIMARY_READY-1}" ;;
              *"get deploy postgres -o jsonpath={.spec.replicas}"*) if f primary_replicas; then cat "$FAKE/primary_replicas"; else echo 1; fi ;;
              *"get nodes -l ecommerce.io/data=true --no-headers"*) printf '%s\\n' "${FAKE_DATA_NODE-oci2 Ready <none> 1d v1.30}" ;;
              *"apply -f "*) touch "$FAKE/standby" "$FAKE/pvc"; echo t > "$FAKE/recovery" ;;
              *"rollout status deploy/postgres-standby"*) echo "successfully rolled out" ;;
              *"scale deploy/postgres --replicas=0"*) echo 0 > "$FAKE/primary_replicas" ;;
              *"patch svc postgres"*) echo postgres-standby > "$FAKE/svc" ;;
              *"delete -f "*) rm -f "$FAKE/standby" "$FAKE/pvc" "$FAKE/recovery" ;;
              *) echo "unexpected: $a" >&2; exit 1 ;;
            esac
            """;

    private record Run(int code, String out) {}

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(posix(), "bash · flock 이 없다");
        fake = Files.createDirectories(tmp.resolve("fake"));
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, FAKE_KUBECTL);
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwx------"));
        Path systemctl = bin.resolve("systemctl");
        Files.writeString(systemctl, FAKE_SYSTEMCTL);
        Files.setPosixFilePermissions(systemctl, PosixFilePermissions.fromString("rwx------"));
        Files.createDirectories(tmp.resolve("textfile"));
        Files.createDirectories(tmp.resolve("systemd"));
    }

    /** 가짜 systemctl — 부른 것을 적고, 타이머가 켜졌는지는 파일 하나로 */
    // [JA] 偽の systemctl — 呼んだものを記録し、タイマーが有効かはファイル一つで
    private static final String FAKE_SYSTEMCTL = """
            #!/usr/bin/env bash
            echo "systemctl $*" >> "$FAKE/systemctl.log"
            case "$*" in
              "is-active --quiet "*) [ -e "$FAKE/timer_active" ] ;;
              "enable --now "*) touch "$FAKE/timer_active" ;;
              "disable --now "*) rm -f "$FAKE/timer_active" ;;
              *) true ;;
            esac
            """;

    private Run run(String action, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", repoRoot().resolve("ops/65-pg-standby.sh").toString(), action)
                .redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
        pb.environment().put("PATH", tmp.resolve("bin") + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE", fake.toString());
        pb.environment().put("LOCK_FILE", tmp.resolve("pg-standby.lock").toString());
        pb.environment().put("MANIFEST", repoRoot().resolve("k8s/91-postgres-standby.yaml").toString());
        pb.environment().put("YES", "1");
        pb.environment().put("SUDO", "");
        pb.environment().put("TEXTFILE_DIR", tmp.resolve("textfile").toString());
        pb.environment().put("SYSTEMD_DIR", tmp.resolve("systemd").toString());
        pb.environment().put("INSTALL_PATH", tmp.resolve("sbin-ecommerce-pg-standby.sh").toString());
        pb.environment().remove("FORCE");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private Run run(String action) throws Exception {
        return run(action, Map.of());
    }

    private String log() throws Exception {
        Path l = fake.resolve("kubectl.log");
        return Files.exists(l) ? Files.readString(l, StandardCharsets.UTF_8) : "";
    }

    private void state(String file, String value) throws Exception {
        Files.writeString(fake.resolve(file), value + "\n");
    }

    @Test
    @DisplayName("🔴 prepare — 처음에는 넷 다 만들고, 두 번째에는 아무것도 다시 하지 않는다 · 비밀번호는 명령줄에 없다")
    void prepareOnceThenNothing() throws Exception {
        Run first = run("prepare");
        assertThat(first.code()).as(first.out()).isZero();
        assertThat(Files.exists(fake.resolve("secret"))).isTrue();
        assertThat(Files.readString(fake.resolve("secret.mode")).trim()).as("Secret 원본 파일은 0600").isEqualTo("600");
        assertThat(Files.readString(fake.resolve("role")).trim()).isEqualTo("true,true");
        assertThat(Files.readString(fake.resolve("slot")).trim()).isEqualTo("physical,false");
        assertThat(Files.readString(fake.resolve("keep")).trim()).isEqualTo("10GB");
        String pw = Files.readString(fake.resolve("secret")).trim();
        assertThat(pw).hasSizeGreaterThan(20);
        assertThat(log()).as("비밀번호가 kubectl 명령줄에 없다(ps 에 보인다)").doesNotContain(pw);
        assertThat(Files.readString(fake.resolve("stdin.log"))).as("계정은 표준 입력으로").contains("CREATE ROLE replicator").contains(pw);
        assertThat(first.out()).as("화면에도 없다").doesNotContain(pw);

        Files.delete(fake.resolve("kubectl.log"));
        Run second = run("prepare");
        assertThat(second.code()).as(second.out()).isZero();
        assertThat(second.out()).contains("이미 끝났다 — 할 것 없음");
        assertThat(log()).as("두 번째에는 바꾸는 명령이 없다")
                .doesNotContain("create secret").doesNotContain("exec -i").doesNotContain("pg_create_physical_replication_slot")
                .doesNotContain("ALTER SYSTEM");
        assertThat(Files.readString(fake.resolve("secret")).trim()).as("Secret 을 다시 만들지 않는다").isEqualTo(pw);
    }

    @Test
    @DisplayName("prepare — 손으로 일부만 해 둔 상태면 나머지만 한다(Secret · 계정은 그대로)")
    void prepareOnlyWhatIsMissing() throws Exception {
        state("secret", "already-there-password");
        state("role", "true,true");
        Run r = run("prepare");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("Secret pg-replication — 이미 있다").contains("복제 계정 replicator — 이미 있다");
        assertThat(log()).doesNotContain("create secret").doesNotContain("exec -i")
                .contains("pg_create_physical_replication_slot").contains("ALTER SYSTEM");
        assertThat(Files.readString(fake.resolve("secret")).trim()).isEqualTo("already-there-password");
    }

    @Test
    @DisplayName("🔴 prepare — 계정은 있는데 Secret 이 없으면(비밀번호를 모른다) 아무것도 바꾸기 전에 멈춘다")
    void roleWithoutSecretStops() throws Exception {
        state("role", "true,true");
        Run r = run("prepare");
        assertThat(r.code()).isNotZero();
        assertThat(r.out()).contains("비밀번호를 모른다");
        assertThat(log()).doesNotContain("create secret").doesNotContain("pg_create_physical_replication_slot").doesNotContain("ALTER SYSTEM");
    }

    @Test
    @DisplayName("prepare — 다른 용도로 보이는 계정 · 논리 슬롯이면 멈춘다 · 누가 정한 WAL 상한은 덮지 않는다")
    void foreignObjectsAndOtherSettingAreRespected() throws Exception {
        state("secret", "pw");
        state("role", "false,true");
        Run role = run("prepare");
        assertThat(role.code()).isNotZero();
        assertThat(role.out()).contains("REPLICATION · LOGIN 이 아니다");

        state("role", "true,true");
        state("slot", "logical,false");
        Run slot = run("prepare");
        assertThat(slot.code()).isNotZero();
        assertThat(slot.out()).contains("물리 슬롯이 아니다");

        state("slot", "physical,false");
        state("keep", "20GB");
        Files.deleteIfExists(fake.resolve("kubectl.log"));
        Run keep = run("prepare");
        assertThat(keep.code()).as(keep.out()).isZero();
        assertThat(keep.out()).contains("다른 값(20GB)").contains("덮지 않는다").contains("할 것 없음");
        assertThat(log()).doesNotContain("ALTER SYSTEM");
    }

    @Test
    @DisplayName("🔴 바꾸는 동작은 한 번에 하나 — 다른 ops/65 가 잠금을 쥐고 있으면 멈춘다")
    void lockPreventsConcurrentRuns() throws Exception {
        Path lock = tmp.resolve("pg-standby.lock");
        Process holder = new ProcessBuilder("flock", lock.toString(), "sleep", "20").start();
        try {
            for (int i = 0; i < 50 && !Files.exists(lock); i++) {
                Thread.sleep(100);
            }
            Thread.sleep(300);
            Run r = run("prepare");
            assertThat(r.code()).isNotZero();
            assertThat(r.out()).contains("이미 돌고 있다");
            assertThat(log()).doesNotContain("create secret");
        } finally {
            holder.destroyForcibly();
        }
        Run after = run("check");
        assertThat(after.code()).as("check 는 읽기만 — 잠금과 상관없다").isZero();
    }

    @Test
    @DisplayName("묻지 않고는 바꾸지 않는다 — 터미널이 없으면 YES=1 이 있어야 한다")
    void refusesWithoutConfirmation() throws Exception {
        Run r = run("prepare", Map.of("YES", ""));
        assertThat(r.code()).isNotZero();
        assertThat(r.out()).contains("YES=1");
        assertThat(log()).doesNotContain("create secret").doesNotContain("pg_create_physical_replication_slot");
    }

    @Test
    @DisplayName("🔴 apply — prepare 전이면 멈추고, 한 번 적용하면 다시 적용하지 않는다 · 데이터 노드가 없으면 멈춘다")
    void applyGuards() throws Exception {
        Run early = run("apply");
        assertThat(early.code()).isNotZero();
        assertThat(early.out()).contains("먼저 ./ops/65-pg-standby.sh prepare");
        assertThat(log()).doesNotContain("apply -f");

        assertThat(run("prepare").code()).isZero();
        Run noNode = run("apply", Map.of("FAKE_DATA_NODE", "oci2 NotReady <none> 1d v1.30"));
        assertThat(noNode.code()).isNotZero();
        assertThat(noNode.out()).contains("데이터 노드");
        assertThat(log()).doesNotContain("apply -f");

        Run ok = run("apply");
        assertThat(ok.code()).as(ok.out()).isZero();
        assertThat(log()).contains("apply -f").contains("rollout status deploy/postgres-standby");
        assertThat(ok.out()).contains("복구 중");

        Files.delete(fake.resolve("kubectl.log"));
        Run again = run("apply");
        assertThat(again.code()).as(again.out()).isZero();
        assertThat(again.out()).contains("이미 있다 — 다시 적용하지 않는다");
        assertThat(log()).doesNotContain("apply -f");
    }

    @Test
    @DisplayName("🔴 promote — primary 가 Ready 면 거절(FORCE=1), 승격 · 옛 primary 0 · Service 전환, 두 번째는 할 것 없음")
    void promoteGuards() throws Exception {
        assertThat(run("prepare").code()).isZero();
        assertThat(run("apply").code()).isZero();

        Files.delete(fake.resolve("kubectl.log"));
        Run alive = run("promote");
        assertThat(alive.code()).isNotZero();
        assertThat(alive.out()).contains("아직 Ready").contains("FORCE=1");
        assertThat(log()).doesNotContain("pg_promote").doesNotContain("patch svc").doesNotContain("scale");

        Run silent = run("promote", Map.of("FAKE_PRIMARY_READY", "0", "FAKE_STANDBY_SILENT", "1"));
        assertThat(silent.code()).isNotZero();
        assertThat(silent.out()).contains("답하지 않는다");

        Run lost = run("promote", Map.of("FAKE_PRIMARY_READY", "0"));
        assertThat(lost.code()).as(lost.out()).isZero();
        assertThat(log()).contains("pg_promote").contains("scale deploy/postgres --replicas=0").contains("patch svc postgres");
        assertThat(Files.readString(fake.resolve("svc")).trim()).isEqualTo("postgres-standby");

        Files.delete(fake.resolve("kubectl.log"));
        Run again = run("promote", Map.of("FAKE_PRIMARY_READY", "0"));
        assertThat(again.code()).as(again.out()).isZero();
        assertThat(again.out()).contains("할 것 없음");
        assertThat(log()).doesNotContain("pg_promote").doesNotContain("patch svc");
    }

    @Test
    @DisplayName("🔴 remove — 슬롯까지 지운다 · 승격된 스탠바이(= 지금의 운영)는 지우지 않는다 · 두 번째는 할 것 없음")
    void removeGuards() throws Exception {
        assertThat(run("prepare").code()).isZero();
        assertThat(run("apply").code()).isZero();

        Run r = run("remove");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(log()).contains("delete -f").contains("pg_drop_replication_slot('standby_oci2')");
        assertThat(Files.exists(fake.resolve("slot"))).as("슬롯이 남으면 primary 가 WAL 을 쌓는다").isFalse();
        assertThat(Files.exists(fake.resolve("secret"))).as("Secret · 계정은 남긴다").isTrue();

        Files.delete(fake.resolve("kubectl.log"));
        Run again = run("remove");
        assertThat(again.code()).as(again.out()).isZero();
        assertThat(again.out()).contains("할 것 없음");
        assertThat(log()).doesNotContain("delete -f").doesNotContain("pg_drop_replication_slot");

        // 승격된 뒤 — Service 가 스탠바이를 가리킨다
        // [JA] 昇格した後 — Service がスタンバイを指す
        assertThat(run("prepare").code()).isZero();
        assertThat(run("apply").code()).isZero();
        assertThat(run("promote", Map.of("FAKE_PRIMARY_READY", "0")).code()).isZero();
        Files.delete(fake.resolve("kubectl.log"));
        Run promoted = run("remove");
        assertThat(promoted.code()).isNotZero();
        assertThat(promoted.out()).contains("지금의 운영 DB");
        assertThat(log()).doesNotContain("delete -f").doesNotContain("pg_drop_replication_slot");
    }


    // ── 감시(metrics · monitor) ─────────────────────────────────────────────
    // [JA] ── 監視(metrics · monitor) ──

    private String prom() throws Exception {
        return Files.readString(tmp.resolve("textfile/pg_standby.prom"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("🔴 metrics — 지표 파일을 원자적으로 쓰고, 질의가 실패하거나 모양이 이상하면 옛 파일을 그대로 둔다(낡으면 경보)")
    void metricsWritesAndKeepsOldFileOnFailure() throws Exception {
        Run ok = run("metrics");
        assertThat(ok.code()).as(ok.out()).isZero();
        String first = prom();
        assertThat(first).contains("pg_standby_configured{slot=\"standby_oci2\"} 1")
                .contains("pg_standby_streaming{application_name=\"standby-oci2\"} 1")
                .contains("pg_standby_replay_lag_seconds{application_name=\"standby-oci2\"} 0.250")
                .contains("pg_standby_replay_lag_bytes{application_name=\"standby-oci2\"} 2048")
                .contains("pg_standby_slot_active{slot=\"standby_oci2\"} 1")
                .contains("pg_standby_slot_retained_bytes{slot=\"standby_oci2\"} 1024")
                .contains("pg_standby_slot_lost{slot=\"standby_oci2\"} 0")
                .containsPattern("pg_standby_metrics_last_success_timestamp_seconds \\d{10}");

        Run fail = run("metrics", Map.of("FAKE_METRICS_FAIL", "1"));
        assertThat(fail.code()).isNotZero();
        assertThat(fail.out()).contains("그대로 둔다");
        assertThat(prom()).as("실패하면 옛 파일 그대로 — 마지막 성공 시각이 낡아 PgStandbyMetricsStale").isEqualTo(first);

        Run odd = run("metrics", Map.of("FAKE_METRICS_ROW", "1 1 x 0 1 0 0"));
        assertThat(odd.code()).isNotZero();
        assertThat(prom()).isEqualTo(first);
        try (var files = Files.list(tmp.resolve("textfile"))) {
            assertThat(files.map(f -> f.getFileName().toString())).as("임시 파일이 남지 않는다(node-exporter 가 반쪽을 읽지 않게)")
                    .containsExactly("pg_standby.prom");
        }
    }

    @Test
    @DisplayName("🔴 monitor-install — 처음에만 깔고, 같은 판이 돌고 있으면 할 것 없음, 깔린 복사본이 다르면 다시 깐다")
    void monitorInstallOnceThenNothing() throws Exception {
        Run first = run("monitor-install");
        assertThat(first.code()).as(first.out()).isZero();
        Path installed = tmp.resolve("sbin-ecommerce-pg-standby.sh");
        assertThat(Files.readString(installed)).isEqualTo(Files.readString(repoRoot().resolve("ops/65-pg-standby.sh")));
        assertThat(Files.readString(tmp.resolve("systemd/ecommerce-pg-standby-metrics.service")))
                .contains("ExecStart=" + installed + " metrics").contains("KUBECONFIG=/etc/rancher/k3s/k3s.yaml");
        assertThat(Files.readString(tmp.resolve("systemd/ecommerce-pg-standby-metrics.timer"))).contains("OnUnitActiveSec=1min");
        assertThat(Files.readString(fake.resolve("systemctl.log"))).contains("enable --now ecommerce-pg-standby-metrics.timer");

        Files.delete(fake.resolve("systemctl.log"));
        Run again = run("monitor-install");
        assertThat(again.code()).as(again.out()).isZero();
        assertThat(again.out()).contains("할 것 없음");
        assertThat(Files.readString(fake.resolve("systemctl.log"))).as("두 번째에는 다시 켜지 않는다")
                .doesNotContain("enable").doesNotContain("daemon-reload");

        Files.writeString(installed, "# 옛 판\\n");
        Run update = run("monitor-install");
        assertThat(update.code()).as(update.out()).isZero();
        assertThat(update.out()).doesNotContain("할 것 없음");
        assertThat(Files.readString(installed)).as("바뀐 판이면 다시 깐다").isEqualTo(Files.readString(repoRoot().resolve("ops/65-pg-standby.sh")));
    }

    @Test
    @DisplayName("monitor-remove — 타이머 · 복사본 · 지표 파일을 빼고, 두 번째는 할 것 없음")
    void monitorRemove() throws Exception {
        assertThat(run("monitor-install").code()).isZero();
        assertThat(run("metrics").code()).isZero();
        Run r = run("monitor-remove");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(tmp.resolve("systemd/ecommerce-pg-standby-metrics.timer")).doesNotExist();
        assertThat(tmp.resolve("sbin-ecommerce-pg-standby.sh")).doesNotExist();
        assertThat(tmp.resolve("textfile/pg_standby.prom")).as("남기면 낡은 지표가 경보를 울린다").doesNotExist();
        assertThat(Files.readString(fake.resolve("systemctl.log"))).contains("disable --now ecommerce-pg-standby-metrics.timer");
        Run again = run("monitor-remove");
        assertThat(again.code()).as(again.out()).isZero();
        assertThat(again.out()).contains("할 것 없음");
    }
}
