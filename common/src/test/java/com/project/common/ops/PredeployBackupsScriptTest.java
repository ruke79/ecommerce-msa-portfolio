package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/63 — 배포 전 수동 백업(~/backups/pre-<버전>-<UTC>) 정리(4단계 ⑥). 정책은 사람이, 지우기는 자동으로: 최신 KEEP 개 · MIN_AGE_DAYS 일
 * 안 된 것은 남기고, 이름 모양이 다른 것 · 심볼릭 링크 · 파일은 건드리지 않는다. 기본은 dry-run.
 *
 * <p>[JA] ops/63 — デプロイ前の手動バックアップの整理。最新 KEEP 個 · MIN_AGE_DAYS 日未満は残し、名前の形が違うもの · シンボリックリンク ·
 *      ファイルは触らない。既定は dry-run。
 */
@DisplayName("ops/63 — 배포 전 백업 정리: 최신 2개 · 7일 안 된 것은 남긴다, 모르는 것은 건드리지 않는다")
class PredeployBackupsScriptTest {

    /** 2026-11-21 12:00 UTC */
    private static final long NOW = Instant.parse("2026-11-21T12:00:00Z").getEpochSecond();

    @TempDir
    Path tmp;
    Path root;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean bash() {
        return Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash"));
    }

    private record Run(int code, String out) {}

    private Run run(String action, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", repoRoot().resolve("ops/63-predeploy-backups.sh").toString(), action)
                .redirectErrorStream(true);
        pb.environment().put("PREDEPLOY_ROOT", root.toString());
        pb.environment().put("NOW_EPOCH", String.valueOf(NOW));
        pb.environment().remove("APPLY");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private void backup(String name) throws Exception {
        Path d = Files.createDirectories(root.resolve(name));
        Files.writeString(d.resolve("pg-base.tar"), "x");
    }

    @BeforeEach
    void backups() throws Exception {
        assumeTrue(bash(), "bash 가 없다");
        root = Files.createDirectories(tmp.resolve("backups"));
        backup("pre-0123456789ab-20261101T000000Z");   // 20일 — 지운다 ／ [JA] 20日 — 消す
        backup("pre-v2-20261110T063508Z");             // 11일 — 지운다 ／ [JA] 11日 — 消す
        backup("pre-v3-20261118T000000Z");             // 3일 — 최신 둘 안 ／ [JA] 3日 — 最新二つの内
        backup("pre-v4-20261121T063508Z");             // 오늘 — 최신 둘 안 ／ [JA] 今日 — 最新二つの内
        backup("pre-weird");                           // 이름 모양이 다르다 — 건드리지 않는다 ／ [JA] 名前の形が違う — 触らない
        Files.createSymbolicLink(root.resolve("pre-link-20261001T000000Z"), root.resolve("pre-weird"));
        Files.writeString(root.resolve("pre-file-20261001T000000Z"), "not a dir");
    }

    @Test
    @DisplayName("🔴 dry-run — 지울 것을 보여 주기만 한다(아무것도 지우지 않는다)")
    void dryRunDeletesNothing() throws Exception {
        Run r = run("prune", Map.of());
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("delete       pre-0123456789ab-20261101T000000Z").contains("dry-run");
        assertThat(Files.list(root).count()).isEqualTo(7);
    }

    @Test
    @DisplayName("🔴 APPLY=1 — 오래되고 최신 둘 밖인 것만 지운다, 이름이 다른 것 · 링크 · 파일은 그대로")
    void applyDeletesOnlyOldOnesBeyondNewest() throws Exception {
        Run r = run("prune", Map.of("APPLY", "1"));
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("지움 2 · 남김 2");
        assertThat(root.resolve("pre-0123456789ab-20261101T000000Z")).doesNotExist();
        assertThat(root.resolve("pre-v2-20261110T063508Z")).doesNotExist();
        assertThat(root.resolve("pre-v3-20261118T000000Z")).isDirectory();
        assertThat(root.resolve("pre-v4-20261121T063508Z")).isDirectory();
        assertThat(root.resolve("pre-weird")).isDirectory();
        assertThat(Files.isSymbolicLink(root.resolve("pre-link-20261001T000000Z"))).isTrue();
        assertThat(root.resolve("pre-file-20261001T000000Z")).isRegularFile();
    }

    @Test
    @DisplayName("🔴 최신 KEEP 개는 나이와 상관없이 · MIN_AGE_DAYS 안 된 것도 남긴다")
    void keepsNewestAndYoung() throws Exception {
        // 최신이 3일 · 오늘 — MIN_AGE_DAYS=2, KEEP=1 이면 3일짜리는 최신 밖 · 2일 넘음 → 지운다, 오늘 것은 최신
        // [JA] 最新が 3 日 · 今日 — MIN_AGE_DAYS=2、KEEP=1 なら 3 日のものは最新の外 · 2 日超え → 消す、今日のものは最新
        Run r = run("prune", Map.of("APPLY", "1", "KEEP", "1", "MIN_AGE_DAYS", "2"));
        assertThat(r.out()).as(r.out()).contains("지움 3 · 남김 1");
        assertThat(root.resolve("pre-v4-20261121T063508Z")).isDirectory();

        // 모두 오래됐어도 최신 KEEP 개는 남는다 [JA] すべて古くても最新 KEEP 個は残る
        Path only = Files.createDirectories(tmp.resolve("old"));
        root = only;
        backup("pre-a-20250101T000000Z");
        backup("pre-b-20250201T000000Z");
        backup("pre-c-20250301T000000Z");
        Run old = run("prune", Map.of("APPLY", "1"));
        assertThat(old.out()).as(old.out()).contains("지움 1 · 남김 2");
        assertThat(only.resolve("pre-a-20250101T000000Z")).doesNotExist();
    }

    @Test
    @DisplayName("KEEP=0 · MIN_AGE_DAYS=0 은 거절 — 최신 백업은 늘 남긴다")
    void rejectsZeroKeep() throws Exception {
        assertThat(run("prune", Map.of("APPLY", "1", "KEEP", "0")).code()).isNotZero();
        assertThat(run("prune", Map.of("APPLY", "1", "MIN_AGE_DAYS", "0")).code()).isNotZero();
        assertThat(Files.list(root).count()).isEqualTo(7);
    }
}
