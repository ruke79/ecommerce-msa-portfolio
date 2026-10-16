package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/02 · DLT 토픽 — 살아 있는 확인 링크가 흐르는 토픽(outgoing-mail)은 본 토픽과 DLT 모두 보존 1시간(docs/29 §5.3).
 *
 * <p>예전에는 DLT 를 모두 14일로 만들어, 처리에 실패한 메일 요청의 확인 링크가 DLT 에 2주 남았다. DRY_RUN 으로 실행해 명령을 본다.
 *
 * <p>[JA] ops/02 · DLT トピック — 生きた確認リンクが流れるトピックは本トピック・DLT とも保持 1 時間。DRY_RUN で実行してコマンドを見る。
 */
@DisplayName("ops/02 · DLT 토픽 — 비밀이 흐르는 토픽의 보존")
class DltTopicsScriptTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    /** ops/02 를 DRY_RUN 으로 — 브로커 없이 낼 명령만 본다. */
    private String dryRun(boolean skipState) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX 가 없다");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        for (String name : new String[] {"kafka-topics.sh", "kafka-configs.sh"}) {
            Path f = bin.resolve(name);
            Files.writeString(f, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        ProcessBuilder pb = new ProcessBuilder("bash", repoRoot().resolve("ops/02-create-dlt-topics.sh").toString());
        pb.environment().put("DRY_RUN", "1");
        pb.environment().put("SKIP_STATE", skipState ? "1" : "0");
        pb.environment().put("KAFKA_BIN", bin.toString());
        pb.environment().put("BOOTSTRAP", "localhost:9092");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        return out;
    }

    @Test
    @DisplayName("🔴 saga-projection 의 입력 토픽을 상태 토픽보다 먼저, 브로커 기본 파티션으로 만든다 — 없으면 Streams 가 ERROR 로 멈춘다")
    void sagaSourceTopicsAreCreatedFirst() throws Exception {
        // 새 클러스터에서 주문이 0 건이면 order · payment 가 없다 — Streams 는 MissingSourceTopicException 으로 내려가 돌아오지 않는다
        // [JA] 新しいクラスタで注文 0 件なら order・payment がない — Streams は落ちて戻らない
        String out = dryRun(false);
        for (String t : List.of("order", "payment", "order-status")) {
            Matcher m = Pattern.compile("(?m)^\\[DRY_RUN\\] .*--create --if-not-exists --topic " + Pattern.quote(t) + " (.*)$").matcher(out);
            assertThat(m.find()).as("%s 생성 명령", t).isTrue();
            assertThat(m.group(1)).as("자동 생성과 같은 브로커 기본 파티션").doesNotContain("--partitions");
        }
        assertThat(out.indexOf("--topic order --replication-factor"))
                .as("상태 토픽은 order 의 파티션 수를 본뜬다 — 입력이 먼저")
                .isLessThan(out.indexOf("--topic order-saga-state"));
    }

    @Test
    @DisplayName("ops/02 의 SOURCE_TOPICS 기본값 = saga-projection.topics 의 order · payment · order-status")
    void sourceTopicsMatchSagaProjection() throws Exception {
        String yml = Files.readString(repoRoot().resolve("saga-projection/src/main/resources/application-local.yml"));
        String script = Files.readString(repoRoot().resolve("ops/02-create-dlt-topics.sh"));
        Matcher d = Pattern.compile("SOURCE_TOPICS=\"\\$\\{SOURCE_TOPICS:-([^}]+)\\}\"").matcher(script);
        assertThat(d.find()).as("ops/02 의 SOURCE_TOPICS 기본값").isTrue();
        List<String> defaults = List.of(d.group(1).trim().split("\\s+"));
        for (String key : List.of("order", "payment", "order-status")) {
            Matcher m = Pattern.compile("(?m)^    " + Pattern.quote(key) + ": (\\S+)").matcher(yml);
            assertThat(m.find()).as("saga-projection.topics.%s", key).isTrue();
            assertThat(defaults).as("앱의 saga-projection.topics.%s", key).contains(m.group(1));
        }
    }

    @Test
    @DisplayName("🔴 outgoing-mail.DLT 는 1시간으로 만들고, 이미 있는 outgoing-mail · .DLT 에도 1시간을 건다 — 다른 DLT 는 14일")
    void secretTopicsAreShortLived() throws Exception {
        String out = dryRun(true);

        assertThat(out).containsPattern("--topic outgoing-mail\\.DLT .*retention\\.ms=3600000")
                .containsPattern("--topic order\\.DLT .*retention\\.ms=1209600000")
                .contains("--entity-name outgoing-mail --add-config retention.ms=3600000",
                        "--entity-name outgoing-mail.DLT --add-config retention.ms=3600000");
    }
}
