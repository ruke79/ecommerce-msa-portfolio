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
 * 🔴 JPA 엔티티의 Lombok 함정 두 가지를 모든 서비스에서 막는다(docs/62 §18).
 *
 * <ol>
 *   <li><b>{@code @Data} 금지</b> — equals/hashCode 가 감사 필드(버전 · 시각)나 지연 연관으로 만들어진다. 다른 행이 '같은'
 *       엔티티가 됐고(장바구니 선택 · 상품 상세 Set), 버전이 오르면 hashCode 가 바뀌었다.</li>
 *   <li><b>{@code @Builder.Default} 가 있는 엔티티의 손으로 쓴 생성자는 {@code this()} 로 시작</b> — 기본값은 빌더와 생성된
 *       무인자 생성자로 옮겨져 손으로 쓴 생성자에는 남지 않는다. {@code new User(…)} 의 tier 가 null 로 INSERT 돼
 *       <b>모든 가입이 DB 에서 거절</b>됐다.</li>
 * </ol>
 */
// [JA] JPA エンティティの Lombok の罠二つを全サービスで防ぐ — @Data 禁止、@Builder.Default を持つエンティティの手書きコンストラクタは this() で始める。
@DisplayName("엔티티 Lombok 가드")
class EntityLombokGuardTest {

    private static final Pattern ENTITY = Pattern.compile("^@(Entity|MappedSuperclass|Embeddable)\\b", Pattern.MULTILINE);
    private static final Pattern DATA = Pattern.compile("^@(lombok\\.)?Data\\b", Pattern.MULTILINE);

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        return p;
    }

    private static List<Path> entitySources() throws IOException {
        try (Stream<Path> modules = Files.list(repoRoot())) {
            List<Path> out = new ArrayList<>();
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                        if (ENTITY.matcher(Files.readString(f, StandardCharsets.UTF_8)).find()) {
                            out.add(f);
                        }
                    }
                }
            }
            return out;
        }
    }

    @Test
    @DisplayName("엔티티를 찾는다 — 검사가 헛돌지 않게")
    void findsEntities() throws IOException {
        assertThat(entitySources()).hasSizeGreaterThan(30);
    }

    @Test
    @DisplayName("🔴 엔티티 · @MappedSuperclass 에 @Data 가 없다")
    void noDataOnEntities() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path f : entitySources()) {
            if (DATA.matcher(Files.readString(f, StandardCharsets.UTF_8)).find()) {
                offenders.add(repoRoot().relativize(f).toString());
            }
        }
        assertThat(offenders).as("@Getter/@Setter 로 — 엔티티는 동일성으로 비교한다").isEmpty();
    }

    @Test
    @DisplayName("🔴 @Builder.Default 엔티티의 손으로 쓴 생성자는 this() 로 시작한다")
    void handWrittenConstructorsDelegateToNoArgs() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path f : entitySources()) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            if (!src.contains("@Builder.Default")) {
                continue;
            }
            Matcher cls = Pattern.compile("\\bclass\\s+(\\w+)").matcher(src);
            if (!cls.find()) {
                continue;
            }
            Matcher ctor = Pattern.compile("\\n\\s*(?:public|protected|private)?\\s*" + cls.group(1)
                    + "\\s*\\(([^)]+)\\)\\s*\\{\\s*([^;]*;)").matcher(src);
            while (ctor.find()) {
                String first = ctor.group(2).strip();
                if (!first.startsWith("this(") && !first.startsWith("super(")) {
                    offenders.add(repoRoot().relativize(f) + " — " + cls.group(1) + "(" + ctor.group(1).strip() + ")");
                }
            }
        }
        assertThat(offenders).as("this(); 로 시작해야 @Builder.Default 기본값(tier · 컬렉션 등)이 적용된다").isEmpty();
    }
}
