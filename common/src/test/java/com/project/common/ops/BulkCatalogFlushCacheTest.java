package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/44 flush-cache — 지우는 캐시 이름과 {@code KEEP_FACETS=1}(필터 메뉴는 남긴다).
 *
 * <p>🐛 2026-11-24 실습(상품 1,000만): 천장 회차 사이에 flush-cache 로 통째로 비웠더니 필터 메뉴 캐시 넷도 사라져
 * 첫 쪽 검색마다 메뉴를 1.5초 기다렸다(p99 1,518ms · catalog CPU 87%). 운영은 FacetWarmer 가 메뉴를 늘 채워 두므로
 * 측정은 그 상태여야 한다. 메뉴 캐시 목록은 catalog {@code SearchFilterFacets} 의 이름과 같아야 한다 — 새 메뉴 캐시가
 * 생기면 여기서 드러난다.
 *
 * <p>[JA] ops/44 flush-cache — KEEP_FACETS=1 ならフィルターメニューのキャッシュは残す。名前は SearchFilterFacets と一致する。
 */
@DisplayName("ops/44 flush-cache — KEEP_FACETS")
class BulkCatalogFlushCacheTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean posix() {
        return Files.isExecutable(Path.of("/bin/bash"));
    }

    private record Run(int code, String out, String kubectl) {}

    /** 가짜 kubectl — 받은 인자(파드 안에서 돌 sh -c 스크립트 포함)를 남긴다. */
    // [JA] 偽の kubectl — 受け取った引数(Pod 内で動く sh -c のスクリプトを含む)を残す。
    private Run flush(boolean keepFacets) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path log = tmp.resolve("kubectl.log");
        Files.deleteIfExists(log);
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, "#!/bin/bash\nprintf '%s\\n' \"$*\" >> \"" + log + "\"\necho '  지운 캐시 키: 0'\n");
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwxr-xr-x"));
        ProcessBuilder pb = new ProcessBuilder("bash", "ops/44-bulk-catalog.sh", "flush-cache")
                .directory(repoRoot().toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        if (keepFacets) {
            pb.environment().put("KEEP_FACETS", "1");
        }
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out, Files.exists(log) ? Files.readString(log) : "");
    }

    /** 파드 안 스크립트의 {@code for c in …; do} 목록. */
    // [JA] Pod 内スクリプトの {@code for c in …; do} の一覧。
    private static List<String> flushed(String kubectlLog) {
        Matcher m = Pattern.compile("for c in ([a-z_ ]+); do").matcher(kubectlLog);
        assertThat(m.find()).as(kubectlLog).isTrue();
        return List.of(m.group(1).trim().split("\\s+"));
    }

    @Test
    @DisplayName("🔴 KEEP_FACETS=1 이면 필터 메뉴 캐시 넷은 남기고 상품 · 검색 결과만 지운다 — 없으면 전부")
    void keepFacetsLeavesFacetCaches() throws Exception {
        assumeTrue(posix(), "bash 가 없다");
        Set<String> facets = Set.of("colors_cache", "sizes_cache", "details_cache", "brands_cache");

        Run all = flush(false);
        assertThat(all.code()).as(all.out()).isZero();
        assertThat(flushed(all.kubectl())).containsAll(facets)
                .contains("products", "product", "products_by_category", "search_cache");

        Run keep = flush(true);
        assertThat(keep.code()).as(keep.out()).isZero();
        assertThat(flushed(keep.kubectl())).containsExactlyInAnyOrder("products", "product", "products_by_category", "search_cache");
        assertThat(keep.out()).contains("필터 메뉴 캐시는 남긴다");
    }

    @Test
    @DisplayName("🔴 FACET_CACHES = catalog SearchFilterFacets 가 쓰는 캐시 이름 — 새 메뉴 캐시가 생기면 KEEP_FACETS 가 지우지 않게")
    void facetCacheNamesMatchCatalog() throws Exception {
        String script = Files.readString(repoRoot().resolve("ops/44-bulk-catalog.sh"));
        Matcher m = Pattern.compile("(?m)^FACET_CACHES=\"([a-z_ ]+)\"").matcher(script);
        assertThat(m.find()).isTrue();
        Set<String> inScript = new LinkedHashSet<>(List.of(m.group(1).trim().split("\\s+")));

        String facets = Files.readString(repoRoot().resolve(
                "catalog-service/src/main/java/com/project/catalog_service/service/SearchFilterFacets.java"));
        Matcher l = Pattern.compile("lookup\\(\"([a-z_]+)\"").matcher(facets);
        Set<String> inCode = new LinkedHashSet<>();
        while (l.find()) {
            inCode.add(l.group(1));
        }
        assertThat(inCode).isNotEmpty();
        assertThat(inScript).isEqualTo(inCode);
    }
}
