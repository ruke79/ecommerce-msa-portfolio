package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 상품 생성기의 데이터 모양(generate-catalog-data.py --skew · --long-names, ops/44 SKEW · LONG_NAMES, docs/83 §2.5) — CSV 모드로 돌려 본다.
 *
 * <p>기본값은 예전과 <b>같은 상품</b>이어야 한다 — 이미 넣은 데이터에 이어 넣기(--resume)는 묶음마다 같은 시드로 만든다. 쏠림 · 긴 이름이
 * 난수를 더 쓰면 그 뒤 모든 상품이 바뀐다.
 *
 * <p>[JA] 商品生成器のデータの形 — 既定は以前と同じ商品(続きの投入が同じシードで作る)。偏り · 長い名前は乱数を余分に使う。
 */
@DisplayName("generate-catalog-data.py — 쏠림 · 긴 이름 · 기본값은 그대로")
class CatalogGeneratorShapeTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean python() {
        try {
            Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private record Run(int code, String out) {}

    private Run gen(String dir, String... more) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("python3", "-I", "ops/tools/generate-catalog-data.py", "--products", "3000",
                "--out", tmp.resolve(dir).toString()));
        cmd.addAll(List.of(more));
        Process p = new ProcessBuilder(cmd).directory(repoRoot().toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    /** products.csv 의 (이름, 카테고리 id) — 이름은 따옴표 안의 쉼표가 있을 수 있어 csv 규칙대로 읽는다. */
    private List<String[]> products(String dir) throws Exception {
        List<String[]> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(tmp.resolve(dir).resolve("products.csv"));
        Pattern field = Pattern.compile("\"((?:[^\"]|\"\")*)\"|([^,]*)");
        for (String line : lines.subList(1, lines.size())) {
            List<String> f = new ArrayList<>();
            Matcher m = field.matcher(line);
            int pos = 0;
            while (pos <= line.length() && m.find(pos)) {
                f.add(m.group(1) != null ? m.group(1).replace("\"\"", "\"") : m.group(2));
                pos = m.end() + 1;
            }
            out.add(new String[] { f.get(1), f.get(5) });   // name, category_id(HEADERS 순서)
        }
        return out;
    }

    private static double topShare(List<String[]> rows, String category) {
        return rows.stream().filter(r -> r[1].equals(category)).count() / (double) rows.size();
    }

    private static String top(List<String[]> rows) {
        Map<String, Integer> c = new HashMap<>();
        rows.forEach(r -> c.merge(r[1], 1, Integer::sum));
        return c.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    @Test
    @DisplayName("🔴 기본값은 예전과 같은 상품 — --skew 0 을 줘도 같다(이어 넣기의 전제)")
    void defaultIsUnchanged() throws Exception {
        assumeTrue(python(), "python3 가 없다");
        assertThat(gen("a").code()).isZero();
        assertThat(gen("b", "--skew", "0").code()).isZero();
        assertThat(Files.readString(tmp.resolve("b/products.csv"))).isEqualTo(Files.readString(tmp.resolve("a/products.csv")));
        assertThat(Files.readString(tmp.resolve("b/product_sizes.csv"))).isEqualTo(Files.readString(tmp.resolve("a/product_sizes.csv")));
        List<String[]> rows = products("a");
        assertThat(topShare(rows, top(rows))).as("기본 분포 — 가장 큰 대분류도 2할 아래").isLessThan(0.2);
        // 2026-10-20(쏠림 · 긴 이름을 더하기 전)의 기본 상품 3,000개의 이름 · 카테고리 지문 — 날짜는 오늘 기준이라 뺀다.
        // 바뀌면 이미 넣은 데이터에 이어 넣은 묶음이 다른 상품이 된다. 일부러 바꿨다면 이 값을 새로 적고 docs/83 §2.5 에 남긴다.
        StringBuilder s = new StringBuilder();
        rows.forEach(r -> s.append(r[0]).append('\t').append(r[1]).append('\n'));
        String sha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8)));
        assertThat(sha).as("기본 상품이 예전과 같다").isEqualTo("bb5c2b63058ea02b02bc7fc18cc60d742030412544323d5c95f8ad4d971c3c8f");
    }

    @Test
    @DisplayName("🔴 --skew 0.5 — 한 대분류가 절반 넘게 · --skew-category 로 고른다 · 없는 이름 · 범위 밖은 거절")
    void skewConcentratesOneCategory() throws Exception {
        assumeTrue(python(), "python3 가 없다");
        assertThat(gen("s", "--skew", "0.5").code()).isZero();
        List<String[]> s = products("s");
        assertThat(topShare(s, top(s))).isBetween(0.5, 0.65);

        assertThat(gen("f", "--skew", "0.5", "--skew-category", "fashion").code()).isZero();
        String fashion = Files.readAllLines(tmp.resolve("f/categories.csv")).stream()
                .filter(l -> l.contains(",fashion,")).findFirst().orElseThrow().split(",")[0];
        assertThat(topShare(products("f"), fashion)).as("fashion 이 절반 넘게").isGreaterThan(0.5);

        Run none = gen("n", "--skew", "0.5", "--skew-category", "nope");
        assertThat(none.code()).isNotZero();
        assertThat(none.out()).contains("--skew-category 'nope' 가 없다");
        Run over = gen("o", "--skew", "1.5");
        assertThat(over.code()).isNotZero();
        assertThat(over.out()).contains("--skew 는 0 ~ 1");
    }

    @Test
    @DisplayName("🔴 --long-names — 이름 100 ~ 180자(name · slug 255 안) · 앞부분은 그대로라 이름 검색어가 계속 맞는다")
    void longNamesKeepSearchTerms() throws Exception {
        assumeTrue(python(), "python3 가 없다");
        assertThat(gen("l", "--long-names").code()).isZero();
        List<String[]> rows = products("l");
        double avg = rows.stream().mapToInt(r -> r[0].length()).average().orElse(0);
        assertThat(avg).isGreaterThan(100);
        assertThat(rows).allSatisfy(r -> assertThat(r[0].length()).isLessThanOrEqualTo(180));
        // 이름 검색어는 k6 의 browse 가 쓴다 — k6 도구가 없는 브랜치(cdc · jdk)에서는 그 부분만 건너뛴다
        Path browse = repoRoot().resolve("ops/k6/browse.js");
        if (!Files.exists(browse)) {
            return;
        }
        Matcher terms = Pattern.compile("NAME_TERMS = \\[(.*?)\\]", Pattern.DOTALL).matcher(Files.readString(browse));
        assertThat(terms.find()).isTrue();
        Matcher t = Pattern.compile("'([^']+)'").matcher(terms.group(1));
        while (t.find()) {
            String term = t.group(1).toLowerCase();
            assertThat(rows).as("검색어 " + term).anySatisfy(r -> assertThat(r[0].toLowerCase()).contains(term));
        }
    }
}
