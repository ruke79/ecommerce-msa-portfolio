package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/28 의 {@code latest} 와 배포 태그(4단계 ②) — 함수만 떼어 가짜 curl · kubectl 과 임시 git 저장소로 돌린다.
 *
 * <ul>
 *   <li>{@code latest}: ops/tools/latest-green.sh 가 GitHub API 로 기본 브랜치의 가장 최근 성공한 build.yml push 실행을 찾는다.
 *       체크아웃이 그 커밋이 아니면 fetch · switch 명령을 알려 주고 멈춘다(매니페스트도 그 커밋이어야 한다). 토큰은 명령줄 · 출력에 없다.</li>
 *   <li>배포 태그: 운영 클러스터(APP_PUBLIC_URL 이 운영 주소)에서만 {@code prod/<UTC>-<SHA12>} 를 만들어 push — DEPLOY_TAG=0 끄기 ·
 *       1 강제.</li>
 * </ul>
 *
 * <p>[JA] ops/28 の latest とデプロイタグ — 関数だけ切り出し、偽の curl · kubectl と一時 git リポジトリで回す。latest はチェックアウトが
 *      そのコミットでなければ止まる。トークンはコマンドライン · 出力にない。タグは本番クラスターでだけ作って push する。
 */
@DisplayName("ops/28 — latest(가장 최근 초록 커밋) · 배포 태그 prod/<UTC>-<SHA12>")
class DeployLatestTagScriptTest {

    private static final String TOKEN = "ghp_testtoken_do_not_print_123";

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean tools() {
        return Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash"));
    }

    private record Run(int code, String out) {}

    private Run sh(Path dir, Map<String, String> env, String body) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", body).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", tmp.resolve("bin") + ":" + System.getenv("PATH"));
        pb.environment().put("GIT_CONFIG_GLOBAL", tmp.resolve("gitconfig").toString());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().remove("GITHUB_TOKEN");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private void exe(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }

    /** 커밋 하나짜리 저장소 + ops/tools/latest-green.sh 사본. @return 그 커밋 SHA [JA] コミット一つのリポジトリ。 */
    private String repo(Path dir, String origin) throws Exception {
        Files.writeString(tmp.resolve("gitconfig"), "[user]\n\tname = t\n\temail = t@example.com\n[init]\n\tdefaultBranch = main\n");
        Files.createDirectories(dir.resolve("ops/tools"));
        Files.copy(repoRoot().resolve("ops/tools/latest-green.sh"), dir.resolve("ops/tools/latest-green.sh"),
                StandardCopyOption.REPLACE_EXISTING);
        Run r = sh(dir, Map.of(), "git init -q && git add -A && git commit -qm init && git remote add origin '" + origin
                + "' && git rev-parse HEAD");
        assertThat(r.code()).as(r.out()).isZero();
        return r.out().strip();
    }

    /** ops/28 에서 이 시험이 쓰는 함수만. [JA] ops/28 からこの試験が使う関数だけ。 */
    private static String functions() {
        return "set -uo pipefail; NS=ecommerce; ENVIRONMENT=local; "
                + "eval \"$(sed -n '/^warn() {/p; /^info() {/p; /^die()  {/p' '" + repoRoot() + "/ops/28-deploy.sh')\"; "
                + "eval \"$(sed -n '/^resolve_latest() {/,/^}/p; /^is_prod_cluster() {/,/^}/p; /^tag_deploy() {/,/^}/p; /^prune_predeploy_backups() {/,/^}/p' '"
                + repoRoot() + "/ops/28-deploy.sh')\"; ";
    }

    /** 가짜 curl — 인자를 적고(토큰이 명령줄에 없는지 본다), 표준 입력(-K -)의 설정을 따로 적고, 실행 목록 JSON 을 돌려준다. */
    // [JA] 偽 curl — 引数を記録し(トークンがコマンドラインにないか見る)、標準入力(-K -)の設定を別に記録し、実行一覧 JSON を返す。
    private void fakeCurl(String headSha) throws Exception {
        exe(tmp.resolve("bin/curl"), "#!/usr/bin/env bash\necho \"$*\" >> '" + tmp.resolve("curl-args.log") + "'\n"
                + "cat > '" + tmp.resolve("curl-stdin.log") + "'\n"
                + "printf '{\"workflow_runs\":[{\"head_sha\":\"" + headSha + "\"}]}'\n");
    }

    @Test
    @DisplayName("🔴 latest — 체크아웃이 가장 최근 초록 커밋이면 그 SHA12, 토큰은 명령줄 · 출력에 없고 표준 입력으로만")
    void latestResolvesWhenCheckoutMatches() throws Exception {
        assumeTrue(tools(), "bash 가 없다");
        Path dir = tmp.resolve("repo");
        String head = repo(dir, "https://github.com/ruke79/ecommerce-msa.git");
        fakeCurl(head);

        Run r = sh(dir, Map.of("GITHUB_TOKEN", TOKEN), functions() + "ROOT='" + dir + "'; resolve_latest; echo \" rc=$?\"");

        assertThat(r.out()).as(r.out()).startsWith(head.substring(0, 12)).contains(" rc=0").doesNotContain(TOKEN);
        String args = Files.readString(tmp.resolve("curl-args.log"));
        assertThat(args).doesNotContain(TOKEN)
                .contains("/repos/ruke79/ecommerce-msa/actions/workflows/build.yml/runs?branch=main")
                .contains("status=success").contains("event=push");
        assertThat(Files.readString(tmp.resolve("curl-stdin.log"))).contains("Authorization: Bearer " + TOKEN);
    }

    @Test
    @DisplayName("🔴 latest — 체크아웃이 다른 커밋이면 멈추고 fetch · switch 명령을 알려 준다")
    void latestStopsWhenCheckoutDiffers() throws Exception {
        assumeTrue(tools(), "bash 가 없다");
        Path dir = tmp.resolve("repo");
        repo(dir, "https://github.com/ruke79/ecommerce-msa.git");
        fakeCurl("a".repeat(40));

        Run r = sh(dir, Map.of("GITHUB_TOKEN", TOKEN), functions() + "ROOT='" + dir + "'; resolve_latest; echo \" rc=$?\"");

        assertThat(r.code()).as(r.out()).isEqualTo(1);
        assertThat(r.out()).contains("git switch --detach aaaaaaaaaaaa").doesNotContain(" rc=").doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("latest — 성공한 실행이 없으면 멈춘다")
    void latestStopsWithoutGreenRun() throws Exception {
        assumeTrue(tools(), "bash 가 없다");
        Path dir = tmp.resolve("repo");
        repo(dir, "https://github.com/ruke79/ecommerce-msa.git");
        exe(tmp.resolve("bin/curl"), "#!/usr/bin/env bash\ncat >/dev/null\nprintf '{\"workflow_runs\":[]}'\n");

        Run r = sh(dir, Map.of("GITHUB_TOKEN", TOKEN), functions() + "ROOT='" + dir + "'; resolve_latest; echo \" rc=$?\"");

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("성공한 build.yml push 실행이 없다");
    }

    /** 운영 주소를 돌려주는(또는 아닌) 가짜 kubectl. [JA] 本番アドレスを返す(または返さない)偽の kubectl。 */
    private void fakeKubectl(String publicUrl) throws Exception {
        exe(tmp.resolve("bin/kubectl"), "#!/usr/bin/env bash\ncase \"$*\" in *APP_PUBLIC_URL*) printf '%s' '" + publicUrl
                + "' ;; *) exit 1 ;; esac\n");
    }

    private List<String> remoteTags(Path bare) throws Exception {
        return sh(bare, Map.of(), "git tag -l").out().lines().toList();
    }

    @Test
    @DisplayName("🔴 운영 클러스터에서 성공하면 prod/<UTC>-<SHA12> 를 만들어 원격에 push 한다 — 운영 밖에서는 만들지 않는다")
    void tagsOnlyOnProdCluster() throws Exception {
        assumeTrue(tools(), "bash 가 없다");
        Path bare = tmp.resolve("remote.git");
        Files.createDirectories(bare);
        assertThat(sh(bare, Map.of(), "git init -q --bare").code()).isZero();
        Path dir = tmp.resolve("repo");
        String head = repo(dir, bare.toString());
        String v = head.substring(0, 12);

        fakeKubectl("https://lab.example.com");
        Run lab = sh(dir, Map.of(), functions() + "ROOT='" + dir + "'; tag_deploy " + v + "; echo \" rc=$?\"");
        assertThat(lab.out()).as(lab.out()).contains(" rc=0");
        assertThat(remoteTags(bare)).as("실습 · 데모에서는 태그를 만들지 않는다").isEmpty();

        fakeKubectl("https://www.ysyproject.com");
        Run prod = sh(dir, Map.of(), functions() + "ROOT='" + dir + "'; tag_deploy " + v + "; echo \" rc=$?\"");
        assertThat(prod.out()).as(prod.out()).contains("배포 태그 prod/").contains(" rc=0");
        assertThat(remoteTags(bare)).singleElement().asString().matches("prod/\\d{8}-\\d{4}-" + v);
        assertThat(sh(bare, Map.of(), "git rev-list -n1 " + remoteTags(bare).get(0)).out().strip()).isEqualTo(head);
    }

    @Test
    @DisplayName("DEPLOY_TAG=0 은 운영에서도 끈다 · DEPLOY_TAG=1 은 운영 밖에서도 만든다 · 없는 커밋이면 경고만(배포는 성공)")
    void deployTagSwitches() throws Exception {
        assumeTrue(tools(), "bash 가 없다");
        Path bare = tmp.resolve("remote.git");
        Files.createDirectories(bare);
        sh(bare, Map.of(), "git init -q --bare");
        Path dir = tmp.resolve("repo");
        String v = repo(dir, bare.toString()).substring(0, 12);

        fakeKubectl("https://www.ysyproject.com");
        sh(dir, Map.of("DEPLOY_TAG", "0"), functions() + "ROOT='" + dir + "'; tag_deploy " + v);
        assertThat(remoteTags(bare)).isEmpty();

        fakeKubectl("https://lab.example.com");
        sh(dir, Map.of("DEPLOY_TAG", "1"), functions() + "ROOT='" + dir + "'; tag_deploy " + v);
        assertThat(remoteTags(bare)).hasSize(1);

        Run missing = sh(dir, Map.of("DEPLOY_TAG", "1"), functions() + "ROOT='" + dir + "'; tag_deploy ffffffffffff; echo \" rc=$?\"");
        assertThat(missing.out()).contains("건너뛴다").contains(" rc=0");
    }

    /** ROOT/ops/63 자리에 부른 방식을 적는 가짜. [JA] ROOT/ops/63 の位置に呼ばれ方を記録する偽物。 */
    private Path stub63(Path dir) throws Exception {
        Path log = tmp.resolve("ops63.log");
        exe(dir.resolve("ops/63-predeploy-backups.sh"), "#!/usr/bin/env bash\necho \"APPLY=${APPLY:-} $*\" >> '" + log + "'\n"
                + "echo '배포 전 백업 정리: 지움 1 · 남김 2'\n");
        return log;
    }

    @Test
    @DisplayName("🔴 운영 배포 성공 뒤 배포 전 백업을 정리한다(ops/63 prune, APPLY=1) — 운영 밖 · PREDEPLOY_PRUNE=0 은 부르지 않는다(4단계 ⑥)")
    void prunesPredeployBackupsOnlyOnProd() throws Exception {
        assumeTrue(tools(), "bash 가 없다");
        Path dir = tmp.resolve("repo");
        repo(dir, "https://github.com/ruke79/ecommerce-msa.git");
        Path log = stub63(dir);

        fakeKubectl("https://lab.example.com");
        sh(dir, Map.of(), functions() + "ROOT='" + dir + "'; prune_predeploy_backups");
        assertThat(log).as("실습 · 데모에서는 부르지 않는다").doesNotExist();

        fakeKubectl("https://www.ysyproject.com");
        sh(dir, Map.of("PREDEPLOY_PRUNE", "0"), functions() + "ROOT='" + dir + "'; prune_predeploy_backups");
        assertThat(log).as("PREDEPLOY_PRUNE=0").doesNotExist();

        Run prod = sh(dir, Map.of(), functions() + "ROOT='" + dir + "'; prune_predeploy_backups; echo \" rc=$?\"");
        assertThat(prod.out()).as(prod.out()).contains("지움 1 · 남김 2").contains(" rc=0");
        assertThat(Files.readString(log)).isEqualTo("APPLY=1 prune\n");
    }
}
