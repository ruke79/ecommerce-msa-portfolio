package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 JPA 매핑의 조용한 함정 두 가지를 모든 서비스에서 막는다(docs/62 §18).
 *
 * <ol>
 *   <li><b>to-one 연관은 {@code fetch = LAZY}</b> — {@code @ManyToOne} · {@code @OneToOne} 의 JPA 기본값은 EAGER 다.
 *       catalog 의 상품 ↔ 하위 카테고리 연결 행이 그 기본값으로 남아, 연결 행을 읽는 곳마다 상품 · 하위 카테고리를 함께 끌어왔다.
 *       필요한 곳은 페치 조인이나 {@code @BatchSize} 로 채운다.</li>
 *   <li><b>{@code @Lob} 금지</b> — Hibernate 6 은 PostgreSQL 에서 {@code @Lob String} 을 대형 객체로 저장한다. 공지 본문이 그래서
 *       열에는 OID 번호만 남고, 고칠 때마다 옛 대형 객체가 버려진 채 쌓였다. 긴 글은 {@code columnDefinition = "text"} 면 된다.</li>
 * </ol>
 */
// [JA] JPA マッピングの静かな罠二つを全サービスで防ぐ — to-one は LAZY、@Lob 禁止(PostgreSQL ではラージオブジェクトになる)。
@DisplayName("엔티티 매핑 가드")
class EntityMappingGuardTest {

    private static final Pattern TO_ONE = Pattern.compile("^\\s*@(ManyToOne|OneToOne)\\b(\\([^)]*\\))?", Pattern.MULTILINE);
    private static final Pattern LOB = Pattern.compile("^\\s*@(jakarta\\.persistence\\.)?Lob\\b", Pattern.MULTILINE);

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        return p;
    }

    private static List<Path> mainSources() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(x -> x.toString().endsWith(".java")).forEach(out::add);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("@ManyToOne · @OneToOne 은 모두 fetch = LAZY 를 적는다")
    void toOneAssociationsAreLazy() throws IOException {
        List<String> eager = new ArrayList<>();
        int seen = 0;
        for (Path f : mainSources()) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = TO_ONE.matcher(src);
            while (m.find()) {
                seen++;
                if (m.group(2) == null || !m.group(2).contains("LAZY")) {
                    eager.add(repoRoot().relativize(f) + ":" + (src.substring(0, m.start()).split("\n", -1).length));
                }
            }
        }
        assertThat(seen).as("연관을 하나도 못 찾으면 검사가 헛돈다").isGreaterThan(20);
        assertThat(eager).as("JPA 기본값(EAGER)인 to-one 연관 — fetch = FetchType.LAZY 를 적을 것").isEmpty();
    }

    @Test
    @DisplayName("@Lob 을 쓰지 않는다 — PostgreSQL 에서 대형 객체가 되어 열에는 OID 만 남는다")
    void noLob() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path f : mainSources()) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = LOB.matcher(src);
            while (m.find()) {
                found.add(repoRoot().relativize(f) + ":" + (src.substring(0, m.start()).split("\n", -1).length));
            }
        }
        assertThat(found).as("긴 글은 @Column(columnDefinition = \"text\") 로").isEmpty();
    }
}
