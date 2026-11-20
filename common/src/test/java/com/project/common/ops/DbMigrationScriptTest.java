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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DB 마이그레이션 상태 조회 · 복구(ops/32-db-migration.sh)의 **이력 표 스키마**.
 *
 * <p>🐛 2026-11-19: 이 스크립트는 표 이름만 적어(`from flyway_schema_history`) psql 의 search_path 에 맡겼다. 쿼리는 postgres
 * 수퍼유저로 도니 search_path 는 늘 public 이 된다. catalog 는 `spring.flyway.default-schema: product` 라 진짜 이력은 product
 * 에 있고 public 에는 옛 기록(V5)이 남아 있어, 운영에서 `pending` 이 "V6 부터 전부 대기" · `drift` 가 "적용됐는데 파일이 없다" 로
 * **거짓을 말했다**. 배포 전 점검이 거짓을 말하는 자리다.
 *
 * <p>지키는 것: ① 설정에서 스키마를 읽는다(catalog = product, 적지 않은 서비스 = public) ② summary · info · pending · drift ·
 * repair 의 **모든** 쿼리가 한정된 이름을 쓴다 — repair 의 DELETE 도 ③ 같은 DB 의 다른 스키마에 옛 이력 표가 있으면 알린다.
 *
 * <p>[JA] ops/32 の履歴表スキーマ — 設定から読み、すべてのクエリ(repair の DELETE も)を修飾する。
 */
@DisplayName("ops/32 — Flyway 이력 표 스키마 (catalog = product)")
class DbMigrationScriptTest {

    @TempDir
    Path tmp;

    private record Run(int code, String out) {
    }

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix") && Files.isExecutable(Path.of("/bin/bash"));
    }

    /** 저장소가 들고 있는 마이그레이션 수 — 가짜 DB 가 "그만큼 적용됐다" 고 답해 summary 가 '최신' 이 되게. */
    // [JA] リポジトリが持つマイグレーション数 — 偽の DB が「その数だけ適用済み」と答え、summary が「最新」になるようにする。
    private static int localMigrations(String service) throws IOException {
        try (Stream<Path> s = Files.list(repoRoot().resolve(service + "/src/main/resources/db/migration"))) {
            return (int) s.filter(p -> p.getFileName().toString().matches("V[0-9.]+__.*\\.sql")).count();
        }
    }

    private void exe(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/bash\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /**
     * 가짜 kubectl — `get deploy/postgres` 는 성공, `exec … psql … -c <SQL>` 은 SQL 을 sql.log 에 적고 답을 낸다.
     *
     * <p>핵심: **productdb 에서는 product 판에만 24행이 있고 public 판에는 옛 5행이 있다**(운영과 같은 모양). 한정하지 않은 쿼리는
     * public 을 읽어 '대기 19건' 이 된다 — 그래서 '최신' 이 보이면 product 를 읽은 것이다.
     *
     * <p>[JA] 偽の kubectl — `get deploy/postgres` は成功し、`exec … psql … -c <SQL>` は SQL を sql.log に書いて答えを返す。
     *
     * <p>[JA] 要点: **productdb では product 版にだけ 24 行があり、public 版には古い 5 行がある**(運用と同じ形)。スキーマを限定しないクエリは
     * public を読んで「待機 19 件」になる — だから「最新」が見えれば product を読んだということである。
     */
    private Run run(String action, String service, Map<String, String> env) throws Exception {
        assumeTrue(posix(), "bash · POSIX 가 없다");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        exe(bin.resolve("kubectl"), """
                echo "kubectl $*" >> "$FAKE/kubectl.log"
                case "$*" in
                  *"get deploy/postgres"*) exit 0 ;;
                esac
                db=""; prev=""
                for a in "$@"; do [ "$prev" = "-d" ] && db="$a"; prev="$a"; done
                sql="${@: -1}"
                printf '[%s] %s\\n' "$db" "$(echo "$sql" | tr -s " \\n" " ")" >> "$FAKE/sql.log"
                # psql 의 search_path 를 충실히 흉내낸다 — 수퍼유저(postgres)의 search_path 는 `"$user", public` 이고
                # postgres 스키마는 없으므로 **한정하지 않은 이름은 public 판**을 읽는다. 한정을 되돌리는 변이가
                # 그래서 운영과 똑같은 거짓(대기 19건)을 낸다.
                sql=$(printf '%s' "$sql" | sed -E 's/(from|into|update|delete from) flyway_schema_history/\\1 public.flyway_schema_history/g')
                case "$sql" in
                  *information_schema.tables*)
                      # productdb 에는 옛 public 판이 남아 있다
                      [ "$db" = "productdb" ] && echo public
                      exit 0 ;;
                  *"where not success"*)
                      case "$sql" in delete*) echo 0 ;; *) : ;; esac
                      exit 0 ;;
                  *"count(*)"*"type = 'BASELINE'"*)
                      # productdb 의 product 판은 2026-09-11 에 baseline 으로 시작했다(V0 행 하나)
                      case "$sql" in *product.flyway_schema_history*) echo 1 ;; *) echo 0 ;; esac
                      exit 0 ;;
                  *"count(*)"*"where success"*)
                      case "$sql" in
                        *product.flyway_schema_history*)
                            # baseline 을 빼지 않으면 저장소 파일 수보다 하나 많아져 '대기' 로 보인다
                            case "$sql" in
                              *"type <> 'BASELINE'"*) echo "$FAKE_APPLIED" ;;
                              *) echo $((FAKE_APPLIED + 1)) ;;
                            esac ;;
                        *public.flyway_schema_history*) [ "$db" = "productdb" ] && echo 5 ;;
                      esac
                      exit 0 ;;
                  *"select version from"*)
                      case "$sql" in
                        *product.flyway_schema_history*) seq 1 "$FAKE_APPLIED" ;;
                        *public.flyway_schema_history*) [ "$db" = "productdb" ] && seq 1 5 ;;
                      esac
                      exit 0 ;;
                  *"select version, checksum from"*) exit 0 ;;
                  *"select installed_rank"*) exit 0 ;;
                esac
                exit 0
                """);

        List<String> cmd = new ArrayList<>(List.of("bash", repoRoot().resolve("ops/32-db-migration.sh").toString(), action));
        if (service != null) {
            cmd.add(service);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(tmp.toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE", tmp.toString());
        pb.environment().put("FAKE_APPLIED", String.valueOf(localMigrations("catalog-service")));
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private String sqlLog() throws IOException {
        Path f = tmp.resolve("sql.log");
        return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
    }

    @Test
    @DisplayName("🔴 summary — catalog 는 product 판을 읽어 '최신', 옛 public 판(5행)을 읽으면 '대기' 가 된다")
    void summaryReadsTheConfiguredSchema() throws Exception {
        Run r = run("summary", null, Map.of());
        assertThat(r.code()).as(r.out()).isZero();

        int have = localMigrations("catalog-service");
        assertThat(r.out()).as("한정하지 않으면 public 의 옛 5행을 읽어 '대기 %d건' 이 된다", have - 5)
                .contains("catalog-service", "productdb")
                .doesNotContain("대기 " + (have - 5) + "건");
        assertThat(r.out().lines().filter(l -> l.contains("catalog-service")).toList())
                .as("catalog 행: 적용 %d · 저장소 %d · 최신", have, have)
                .anySatisfy(line -> assertThat(line).contains(String.valueOf(have)).contains("최신"));

        assertThat(sqlLog())
                .contains("from product.flyway_schema_history")
                .contains("from public.flyway_schema_history");
        assertThat(sqlLog().lines().filter(l -> l.startsWith("[productdb]")).toList())
                .as("productdb 로 가는 이력 쿼리는 product 판만 — public 은 정보 조회(information_schema)뿐")
                .allSatisfy(line -> assertThat(line)
                        .satisfiesAnyOf(l -> assertThat(l).contains("product.flyway_schema_history"),
                                l -> assertThat(l).contains("information_schema")));
    }

    @Test
    @DisplayName("🔴 baseline 행(V0)은 '적용' 에 세지 않는다 — 세면 저장소 파일 수보다 하나 많아 '대기' 로 보인다")
    void baselineRowIsNotCountedAsApplied() throws Exception {
        Run r = new Run(0, "");
        r = run("summary", null, Map.of());
        assertThat(r.code()).as(r.out()).isZero();

        int have = localMigrations("catalog-service");
        assertThat(r.out().lines().filter(l -> l.contains("catalog-service ")).toList())
                .as("baseline 을 세면 '적용 %d · 저장소 %d' 가 되어 상태가 흔들린다", have + 1, have)
                .anySatisfy(line -> assertThat(line).doesNotContain(String.valueOf(have + 1)));
        assertThat(r.out()).contains("Flyway baseline 에서 시작했습니다", "'적용' 은 그 행을 세지 않습니다");
        assertThat(sqlLog()).contains("where success and type <> 'BASELINE'");
    }

    @Test
    @DisplayName("🔴 같은 DB 의 다른 스키마에 옛 이력 표가 있으면 알린다 — 어느 판을 읽는지도 함께")
    void warnsAboutTheStaleHistoryTable() throws Exception {
        Run r = run("summary", null, Map.of());
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("이력 표는 product 를 읽습니다", "다른 스키마에도 있습니다", "public");
    }

    @Test
    @DisplayName("🔴 pending — catalog 는 product 판 기준이라 대기 0, public 판을 읽으면 V6 부터 전부 대기로 보인다")
    void pendingUsesTheConfiguredSchema() throws Exception {
        Run r = run("pending", null, Map.of());
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out())
                .as("product 판 기준이면 catalog 는 대기 0 이라 블록이 아예 없다 — 옛 public 판(5행)을 읽으면 V6 부터 줄줄이 나온다")
                .doesNotContain("catalog-service (productdb)");
        assertThat(sqlLog()).contains("select version from product.flyway_schema_history");
    }

    @Test
    @DisplayName("🔴 drift · info 의 쿼리도 한정된 이름 — info 는 어느 스키마를 읽는지 적는다")
    void driftAndInfoUseTheConfiguredSchema() throws Exception {
        Run drift = run("drift", null, Map.of());
        assertThat(drift.code()).as(drift.out()).isZero();
        assertThat(sqlLog()).contains("select version, checksum from product.flyway_schema_history");

        Files.deleteIfExists(tmp.resolve("sql.log"));
        Run info = run("info", "catalog-service", Map.of());
        assertThat(info.code()).as(info.out()).isZero();
        assertThat(info.out()).contains("이력 스키마 product");
        assertThat(sqlLog()).contains("from product.flyway_schema_history");
    }

    @Test
    @DisplayName("🔴 repair 의 DELETE 도 한정된 이름 — 한정하지 않으면 **다른 스키마의 행을 지운다**")
    void repairDeletesFromTheConfiguredSchema() throws Exception {
        Map<String, String> yes = new HashMap<>();
        yes.put("YES", "1");
        Run r = run("repair", "catalog-service", yes);
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("이력 스키마 product");
        // 지울 행이 없으면 DELETE 까지 가지 않는다 — 읽기 쿼리가 한정됐는지만 본다.
        // [JA] 消す行がなければ DELETE まで行かない — 読み取りクエリが限定されているかだけを見る。
        assertThat(sqlLog()).contains("from product.flyway_schema_history where not success");
        assertThat(sqlLog()).doesNotContain("from public.flyway_schema_history where not success");
    }

    @Test
    @DisplayName("🔴 어떤 쿼리에도 한정 없는 flyway_schema_history 가 없다 — search_path 에 맡기면 수퍼유저의 public 이 이긴다")
    void noQueryLeavesTheTableUnqualified() throws Exception {
        String script = Files.readString(repoRoot().resolve("ops/32-db-migration.sh"), StandardCharsets.UTF_8);
        List<String> offenders = new ArrayList<>();
        for (String line : script.lines().toList()) {
            String code = line.replaceFirst("^\\s*#.*$", "");
            if (code.matches(".*(from|into|update|join)\\s+flyway_schema_history\\b.*")) {
                offenders.add(line.trim());
            }
        }
        assertThat(offenders).as("스키마를 한정하지 않은 쿼리 — hist_of 를 쓸 것").isEmpty();
    }
}
