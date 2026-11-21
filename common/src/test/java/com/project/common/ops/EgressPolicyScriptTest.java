package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
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
 * ops/35 의 egress 1단계(k8s/97, docs/57 §23) — 사전 점검 · 노드 IP 펼치기 · 적용 전/후 두드리기 · 막히면 되돌리기를 가짜 kubectl 로.
 *
 * <p>NetworkPolicy 는 틀리면 조용히 끊는다 — 결제는 멈추는데 파드는 Running 이다. 그래서 판단(되돌릴지 · 집행되는지 · 이 환경에
 * 적용해도 되는지)을 고정한다. 실제 집행(kube-router)은 실습 클러스터에서 `egress-apply` 로 본다.
 *
 * <p>[JA] ops/35 の egress 第 1 段階 — 事前点検 · ノード IP の展開 · 適用前後の確認 · 塞がれば戻す、を偽の kubectl で固定する。
 */
@DisplayName("ops/35 — egress 1단계 적용 · 되돌리기")
class EgressPolicyScriptTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    /**
     * 가짜 kubectl. 두드리기 파드(`run netpol-probe-<그룹>-N … sh -c "<스크립트>"`)는 스크립트의 대상마다 OK 를 내되,
     * 적용 전이면 FAIL_BEFORE, 적용 뒤면 FAIL_AFTER 에 있는 "그룹:대상" 은 FAIL. apply 는 본문을 남기고, delete 는 기록한다.
     *
     * <p>[JA] 偽の kubectl。叩き用ポッド(`run netpol-probe-<グループ>-N … sh -c "<スクリプト>"`)はスクリプトの対象ごとに OK を出すが、
     * 適用前なら FAIL_BEFORE、適用後なら FAIL_AFTER にある "グループ:対象" は FAIL。apply は本文を残し、delete は記録する。
     */
    private static final String FAKE_KUBECTL = """
            #!/usr/bin/env bash
            a="$*"
            case "$a" in
              *"config current-context"*) echo "registry-vm-local" ;;
              *) ;;
            esac
            [[ "$a" == *"config current-context"* ]] && exit 0
            if [ "${FAKE_MODE:-}" = unreachable ]; then echo "The connection to the server localhost:8080 was refused" >&2; exit 1; fi
            if [ "${FAKE_MODE:-}" = no-configmap ]; then
              case "$a" in
                *"get configmap ecommerce-config"*) echo 'Error from server (NotFound): configmaps "ecommerce-config" not found' >&2; exit 1 ;;
                *"get namespace ecommerce"*) echo "namespace/ecommerce"; exit 0 ;;
              esac
            fi
            case "$a" in
              *"get nodes"*) printf '%s\\n' $NODE_IPS ;;
              *"get configmap ecommerce-config -o name"*) echo "configmap/ecommerce-config" ;;
              *"get endpoints kubernetes"*)
                [ -n "${FAKE_API_IP:-}" ] || exit 1
                case "$a" in *addresses*) printf '%s' "$FAKE_API_IP" ;; *) printf '6443' ;; esac ;;
              *"get configmap metrics-agent-config -o yaml"*)
                [ -n "${FAKE_RW_URL:-}" ] || exit 1
                printf '    remote_write:\n      - url: %s\n' "$FAKE_RW_URL" ;;
              *"get configmap ecommerce-config"*)
                k=$(sed -E 's/.*\\{\\.data\\.([A-Z_]+)\\}.*/\\1/' <<<"$a"); v="CM_$k"; printf '%s' "${!v:-}" ;;
              *"run netpol-probe-"*)
                g=$(sed -E 's/.*app=netpol-probe-([a-z]+).*/\\1/' <<<"$a")
                if [ -f "$STATE/applied" ]; then fails="$FAIL_AFTER"; else fails="$FAIL_BEFORE"; fi
                grep -oE "echo '[^ ']+ OK'" <<<"$a" | sed -E "s/echo '([^ ]+) OK'/\\1/" | while read -r t; do
                  if [[ " $fails " == *" $g:$t "* ]]; then echo "$t FAIL"; else echo "$t OK"; fi
                done ;;
              *"apply -f -"*) cat > "$STATE/rendered.yaml"; touch "$STATE/applied"; echo applied ;;
              *"delete networkpolicy"*) echo "$a" >> "$STATE/deleted"; rm -f "$STATE/applied" ;;
              *"get pods --no-headers"*) echo "cart-service-1 1/1 Running 0 1m" ;;
              *) echo "unexpected: $a" >&2; exit 1 ;;
            esac
            """;

    private record Run(int code, String out) {}

    private Path state;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash")));
        state = Files.createDirectories(tmp.resolve("state"));
    }

    private Run run(String command, String failBefore, String failAfter, String postgresHost) throws Exception {
        return run(command, failBefore, failAfter, postgresHost, "");
    }

    private Run run(String command, String failBefore, String failAfter, String postgresHost, String fakeMode)
            throws Exception {
        return run(command, failBefore, failAfter, postgresHost, fakeMode, Map.of());
    }

    private Run run(String command, String failBefore, String failAfter, String postgresHost, String fakeMode,
            Map<String, String> extra) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, FAKE_KUBECTL);
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwx------"));
        ProcessBuilder pb = new ProcessBuilder("bash", "ops/35-network-policy.sh", command)
                .directory(repoRoot().toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("STATE", state.toString());
        pb.environment().put("NODE_IPS", "10.0.0.5 10.0.0.6");
        pb.environment().put("CM_POSTGRES_HOST", postgresHost);
        pb.environment().put("CM_SPRING_DATA_REDIS_HOST", "redis.redis.svc.cluster.local");
        pb.environment().put("CM_KAFKA_URL", "kafka.kafka.svc.cluster.local:9092");
        pb.environment().put("FAIL_BEFORE", failBefore);
        pb.environment().put("FAIL_AFTER", failAfter);
        pb.environment().put("NETPOL_SETTLE", "0");
        pb.environment().put("FAKE_MODE", fakeMode);
        pb.environment().putAll(extra);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    /**
     * 정책이 제대로 집행될 때 막히는 것들 — internal 은 바깥 전부, 모든 그룹이 메타데이터 · 노드 kubelet, public · mail 은 443 밖(2단계),
     * mongo · metrics · cloudflared · backup 은 제 할 일 밖(2단계).
     *
     * <p>[JA] 方針が正しく施行されたときに遮断されるもの — internal は外部すべて、全グループがメタデータ · ノードの kubelet、public · mail は 443 以外(第 2 段階)、
     * mongo · metrics · cloudflared · backup は自分の役割以外(第 2 段階)。
     */
    private static final String ENFORCED = "internal:1.1.1.1:443 internal:api.stripe.com:443 internal:169.254.169.254:80 "
            + "internal:10.0.0.5:10250 public:169.254.169.254:80 public:10.0.0.5:10250 public:1.1.1.1:80 "
            + "mail:169.254.169.254:80 mail:10.0.0.5:10250 mail:1.1.1.1:80 "
            + "mongo:169.254.169.254:80 mongo:10.0.0.5:10250 mongo:1.1.1.1:443 mongo:postgres.postgres.svc.cluster.local:5432 "
            + "mongo:gateway-server.ecommerce.svc.cluster.local:8000 "
            + "metrics:169.254.169.254:80 metrics:10.0.0.5:10250 metrics:1.1.1.1:443 metrics:postgres.postgres.svc.cluster.local:5432 "
            + "metrics:user-service.ecommerce.svc.cluster.local:8080 "
            + "cloudflared:169.254.169.254:80 cloudflared:10.0.0.5:10250 cloudflared:1.1.1.1:80 "
            + "cloudflared:postgres.postgres.svc.cluster.local:5432 cloudflared:user-service.ecommerce.svc.cluster.local:8080 "
            + "backup:169.254.169.254:80 backup:10.0.0.5:10250 backup:1.1.1.1:443 backup:redis.redis.svc.cluster.local:6379 "
            + "backup:gateway-server.ecommerce.svc.cluster.local:8000";

    private static final String IN_CLUSTER = "postgres.postgres.svc.cluster.local";

    @Test
    @DisplayName("🔴 집행되면 적용을 남긴다 — 노드 IP 가 노드마다 펼쳐진 매니페스트로")
    void appliesWhenAllowedFlowsStayOpen() throws Exception {
        Run r = run("egress-apply", "", ENFORCED, IN_CLUSTER);

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("✅ egress 1 · 2단계 적용");
        String rendered = Files.readString(state.resolve("rendered.yaml"));
        assertThat(rendered).contains("cidr: 10.0.0.5/32", "cidr: 10.0.0.6/32").doesNotContain("__NODE_IP__");
        for (String policy : List.of("egress-smtp", "egress-mongo-atlas", "egress-metrics-agent", "egress-cloudflared",
                "egress-backup-postgres")) {
            assertThat(rendered).as("2단계 정책").contains("name: " + policy);
        }
        assertThat(state.resolve("deleted")).doesNotExist();
    }

    @Test
    @DisplayName("🔴 허용돼야 할 흐름(DNS · DB · 노드 수집기)이 막히면 스스로 되돌린다")
    void rollsBackWhenAnAllowedFlowIsBlocked() throws Exception {
        Run r = run("egress-apply", "", ENFORCED + " internal:dns:kafka.kafka.svc.cluster.local", IN_CLUSTER);

        assertThat(r.code()).as(r.out()).isEqualTo(1);
        assertThat(r.out()).contains("막혔다 — 되돌린다");
        assertThat(Files.readString(state.resolve("deleted"))).contains("egress-cluster-only", "egress-public-no-private",
                "egress-smtp", "egress-mongo-atlas", "egress-metrics-agent", "egress-cloudflared", "egress-backup-postgres");
    }

    @Test
    @DisplayName("🔴 '바깥이 필요 없는' 그룹의 DB 가 클러스터 밖이면(external 오버레이) 적용하지 않는다")
    void refusesWhenBackendsAreOutsideTheCluster() throws Exception {
        Run r = run("egress-apply", "", ENFORCED, "ep-REPLACE.example.neon.tech");

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("클러스터 밖이다", "적용하지 않는다");
        assertThat(state.resolve("rendered.yaml")).doesNotExist();
    }

    /**
     * 🐛 2026-11-19 실습: 클러스터를 가리키지 않는 셸(이미지 빌드 VM)에서 돌려 "읽지 못했다" 한 줄로 멈췄다 — 까닭을 나눠 말한다.
     * [JA] クラスタを指していないシェルで「読めなかった」一行で止まった — 理由を分けて言う。
     */
    @Test
    @DisplayName("🔴 사전 점검이 멈춘 까닭을 나눠 말한다 — 클러스터에 닿지 못함(컨텍스트) · ConfigMap 없음")
    void precheckExplainsWhyItCouldNotRead() throws Exception {
        Run unreachable = run("egress-apply", "", ENFORCED, IN_CLUSTER, "unreachable");
        assertThat(unreachable.code()).isEqualTo(1);
        assertThat(unreachable.out()).contains("kubectl 이 클러스터에 닿지 못한다", "registry-vm-local", "connection to the server");

        Run missing = run("egress-apply", "", ENFORCED, IN_CLUSTER, "no-configmap");
        assertThat(missing.code()).isEqualTo(1);
        assertThat(missing.out()).contains("ConfigMap ecommerce-config 가 없다").doesNotContain("닿지 못한다");
        assertThat(state.resolve("rendered.yaml")).doesNotExist();
    }

    @Test
    @DisplayName("막혀야 할 흐름이 하나도 안 막히면 '집행하지 않는다' 로 알린다(되돌리지는 않는다)")
    void warnsWhenNotEnforced() throws Exception {
        Run r = run("egress-apply", "", "", IN_CLUSTER);

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.out()).contains("집행하지 않거나");
        assertThat(state.resolve("deleted")).doesNotExist();
    }

    @Test
    @DisplayName("일부만 막히면(바깥 연결이 열려 있다) 성공으로 끝내지 않는다")
    void partialEnforcementIsNotSuccess() throws Exception {
        Run r = run("egress-apply", "", ENFORCED.replace("internal:1.1.1.1:443 ", ""), IN_CLUSTER);

        assertThat(r.code()).as(r.out()).isEqualTo(2);
        assertThat(r.out()).contains("⚠️  1.1.1.1:443 — 열려 있다");
    }

    @Test
    @DisplayName("정책 전에도 닿지 않던 것(이 환경엔 메타데이터 서버 · 바깥 연결이 없다)은 판정에서 뺀다")
    void ignoresFlowsThatWereClosedBefore() throws Exception {
        String closedBefore = "internal:169.254.169.254:80 public:169.254.169.254:80 public:1.1.1.1:443 "
                + "public:api.stripe.com:443 internal:1.1.1.1:443 internal:api.stripe.com:443";
        Run r = run("egress-apply", closedBefore, ENFORCED + " public:1.1.1.1:443 public:api.stripe.com:443", IN_CLUSTER);

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("정책 전에도 그랬다");
    }

    @Test
    @DisplayName("🔴 2단계 — public 의 443 밖(1.1.1.1:80)이 열려 있으면 성공으로 끝내지 않는다")
    void publicPortNarrowingIsChecked() throws Exception {
        Run r = run("egress-apply", "", ENFORCED.replace("public:1.1.1.1:80 ", ""), IN_CLUSTER);

        assertThat(r.code()).as(r.out()).isEqualTo(2);
        assertThat(r.out()).contains("▶ public 그룹 — 막혀야 할 흐름", "⚠️  1.1.1.1:80 — 열려 있다");
    }

    @Test
    @DisplayName("🔴 2단계 전제 — 메일 포트가 465 · 587 밖 · 설정 저장소가 ssh · 백업 PGHOST 가 클러스터 밖이면 적용하지 않는다(값은 출력하지 않는다)")
    void stage2PrecheckRefusesPortsOutsideThePolicy() throws Exception {
        Run mail = run("egress-apply", "", ENFORCED, IN_CLUSTER, "", Map.of("NETPOL_MAIL_PORT", "2525"));
        assertThat(mail.code()).isEqualTo(1);
        assertThat(mail.out()).contains("메일 포트(Secret MAIL_PORT)가 465 · 587 이 아니다").doesNotContain("2525");

        Run git = run("egress-apply", "", ENFORCED, IN_CLUSTER, "", Map.of("NETPOL_GIT_URI", "git@github.com:org/config.git"));
        assertThat(git.code()).isEqualTo(1);
        assertThat(git.out()).contains("ssh/git 프로토콜").doesNotContain("org/config");

        Run backup = run("egress-apply", "", ENFORCED, IN_CLUSTER, "", Map.of("NETPOL_BACKUP_PGHOST", "db.example.neon.tech"));
        assertThat(backup.code()).isEqualTo(1);
        assertThat(backup.out()).contains("백업 PGHOST");
        assertThat(state.resolve("rendered.yaml")).doesNotExist();

        Run ok = run("egress-apply", "", ENFORCED, IN_CLUSTER, "", Map.of("NETPOL_MAIL_PORT", "587",
                "NETPOL_GIT_URI", "https://github.com/org/config.git", "NETPOL_BACKUP_PGHOST", "postgres.postgres.svc.cluster.local"));
        assertThat(ok.code()).as(ok.out()).isZero();
    }

    @Test
    @DisplayName("🔴 2단계 전제 — 메일 호스트가 localhost 면(데모 — 보내지 않는 자리) 포트가 25 여도 적용한다, 바깥 호스트의 25 는 거절")
    void localhostMailIsNotUsed() throws Exception {
        Run demo = run("egress-apply", "", ENFORCED, IN_CLUSTER, "", Map.of("NETPOL_MAIL_HOST", "localhost", "NETPOL_MAIL_PORT", "25"));
        assertThat(demo.code()).as(demo.out()).isZero();
        assertThat(demo.out()).contains("메일 포트 — 465 · 587 이거나 쓰지 않는다");

        Files.deleteIfExists(state.resolve("applied"));
        Run real = run("egress-apply", "", ENFORCED, IN_CLUSTER, "", Map.of("NETPOL_MAIL_HOST", "smtp.example.com", "NETPOL_MAIL_PORT", "25"));
        assertThat(real.code()).isEqualTo(1);
        assertThat(real.out()).contains("메일 포트(Secret MAIL_PORT)가 465 · 587 이 아니다").doesNotContain("smtp.example.com");
    }

    @Test
    @DisplayName("🔴 지표 에이전트 그룹은 실제 API 서버(kubernetes 엔드포인트) · 실제 remote write 대상을 두드린다 — 여러 노드면 첫 노드가 아니다(2026-11-20 실습)")
    void metricsProbesRealTargets() throws Exception {
        Run multi = run("egress-check", "", "", IN_CLUSTER, "", Map.of("FAKE_API_IP", "10.0.0.9",
                "FAKE_RW_URL", "http://obs-prometheus.ecommerce.svc.cluster.local:9090/api/v1/write"));
        assertThat(multi.out()).as(multi.out()).contains("10.0.0.9:6443", "obs-prometheus.ecommerce.svc.cluster.local:9090")
                .doesNotContain("10.0.0.5:6443", "10.0.0.5:9090");

        Run single = run("egress-check", "", "", IN_CLUSTER, "", Map.of("FAKE_RW_URL", "http://__HOST_IP__:9090/api/v1/write"));
        assertThat(single.out()).as(single.out()).contains("10.0.0.5:6443", "10.0.0.5:9090");
    }
}
