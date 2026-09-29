package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 엔티티가 <b>INSERT 때 SELECT 를 부르지 않는지</b>를 지키는 가드레일.
 *
 * <p>배경. {@code SimpleJpaRepository.save()} 는 한 줄로 갈린다.
 *
 * <pre>
 *   if (entityInformation.isNew(entity)) em.persist(entity);
 *   else                                 return em.merge(entity);
 * </pre>
 *
 * <p>그리고 {@code isNew()} 의 기본 판정은 <b>"@Id 가 null 인가"</b>다. 그래서 id 를 코드가
 * 직접 배정하는 엔티티(생성자에서 {@code UUID.randomUUID()}, 카프카가 준 eventId, 다른
 * 서비스가 준 userId)는 save() 시점에 id 가 이미 차 있고, 그것만으로 "기존 행"으로 판정돼
 * {@code merge()} 로 간다. merge 는 붙일 대상을 찾으려 <b>SELECT 를 먼저 던진다</b> —
 * 새 행을 넣을 때마다 반드시 빈손으로 끝나는 조회가 한 번씩 따라붙는다.
 *
 * <p>비용보다 나쁜 쪽은 <b>의미가 바뀐다</b>는 것이다. merge 는 같은 id 가 이미 커밋돼 있으면
 * PK 충돌 대신 조용히 UPDATE 를 낸다. 그래서 "중복이면 삽입이 실패할 것"에 기대던 코드가
 * 전부 무력해진다 — 실제로 catalog·coupon 의 멱등성 원장 주석("동시 중복은 PK 충돌로 전체
 * 롤백")과 chat 의 {@code catch (DataIntegrityViolationException)} 이 그런 상태였다.
 *
 * <p>빠져나갈 길은 셋이고, 셋 다 {@code isNew()} 가 신규를 신규라고 답하게 만든다.
 *
 * <ol>
 *   <li>{@code @GeneratedValue} — DB/시퀀스가 채우므로 save() 때 id 가 null 이다.</li>
 *   <li>{@code @Tsid} — {@code TsidGenerator} 가 {@code IdentifierGenerator} 라 마찬가지로
 *       insert 시점에 채운다(save() 직전에는 여전히 null).</li>
 *   <li>래퍼 타입 {@code @Version} 또는 {@link org.springframework.data.domain.Persistable}
 *       구현 — {@code JpaMetamodelEntityInformation} 이 id 대신 version 을 보거나,
 *       엔티티가 직접 답한다.</li>
 * </ol>
 *
 * <p>이 테스트는 넷 중 아무것도 없는 엔티티를 잡는다. 눈으로는 보이지 않는 결함이다 —
 * 매핑은 정상이고 컴파일도 되고 기능도 동작한다. 쿼리를 세어 보기 전에는 드러나지 않는다.
 */
@DisplayName("엔티티 가드레일 · 할당 ID 가 merge 로 새지 않는가")
class EntityPersistenceGuardrailTest {

    /** common 모듈 기준으로 저장소 루트를 찾는다(Gradle 테스트의 작업 디렉터리는 모듈 디렉터리). */
    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static final Pattern CLASS_DECL =
            Pattern.compile("\\b(?:public\\s+|abstract\\s+|final\\s+)*class\\s+(\\w+)");
    private static final Pattern EXTENDS =
            Pattern.compile("\\bclass\\s+\\w+(?:<[^>]*>)?\\s+extends\\s+(\\w+)");
    private static final Pattern VERSION_FIELD =
            Pattern.compile("@Version\\b[\\s\\S]{0,300}?\\bprivate\\s+(\\w+)\\s+\\w+\\s*[;=]");

    /** 줄 주석과 Javadoc 본문을 걷어낸다 — 이 저장소에는 {@code //@GeneratedValue} 가 많다. */
    private static String live(String source) {
        return source.lines()
                .map(String::strip)
                .filter(l -> !l.startsWith("//") && !l.startsWith("*") && !l.startsWith("/*"))
                .collect(Collectors.joining("\n"));
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> paths = Files.walk(repoRoot())) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("id 를 코드가 배정하는 엔티티는 Persistable 이나 @Version 으로 신규임을 알려야 한다")
    void assignedIdEntitiesMustNotFallThroughToMerge() throws IOException {
        List<Path> sources = mainSources();

        Map<String, String> bySimpleName = new HashMap<>();
        Map<Path, String> bodies = new HashMap<>();
        for (Path p : sources) {
            String body = live(Files.readString(p, StandardCharsets.UTF_8));
            bodies.put(p, body);
            Matcher m = CLASS_DECL.matcher(body);
            if (m.find()) {
                bySimpleName.putIfAbsent(m.group(1), body);
            }
        }

        List<String> offenders = new ArrayList<>();

        for (Path p : sources) {
            String body = bodies.get(p);
            if (!Pattern.compile("^\\s*@Entity\\b", Pattern.MULTILINE).matcher(body).find()) {
                continue;
            }

            // 상속을 따라 올라가며(@Id·@Version 이 BaseEntity 에 있는 엔티티가 많다) 전부 모은다.
            StringBuilder chain = new StringBuilder(body);
            String current = body;
            Set<String> visited = new LinkedHashSet<>();
            for (int hop = 0; hop < 8; hop++) {
                Matcher ext = EXTENDS.matcher(current);
                if (!ext.find()) {
                    break;
                }
                String parent = ext.group(1);
                if (!visited.add(parent)) {
                    break;
                }
                String parentBody = bySimpleName.get(parent);
                if (parentBody == null) {
                    break;
                }
                chain.append('\n').append(parentBody);
                current = parentBody;
            }
            String all = chain.toString();

            boolean generatedId = all.contains("@GeneratedValue") || all.contains("@Tsid");
            boolean persistable = Pattern.compile("\\bimplements\\b[^{]*\\bPersistable\\b").matcher(body).find();

            // JpaMetamodelEntityInformation 은 version 이 **원시 타입이면** id 판정으로 되돌아간다.
            boolean wrapperVersion = false;
            Matcher v = VERSION_FIELD.matcher(all);
            while (v.find()) {
                String type = v.group(1);
                if (!Set.of("long", "int", "short", "byte").contains(type)) {
                    wrapperVersion = true;
                    break;
                }
            }

            if (!generatedId && !persistable && !wrapperVersion) {
                offenders.add(repoRoot().relativize(p).toString());
            }
        }

        assertThat(offenders)
                .as("이 엔티티들은 save() 가 persist() 대신 merge() 로 가서 INSERT 마다 헛SELECT 를 내고, "
                        + "중복 id 에서 PK 충돌 대신 UPDATE 를 낸다. "
                        + "@GeneratedValue / @Tsid / 래퍼 @Version / Persistable 중 하나가 필요하다")
                .isEmpty();
    }

    @Test
    @DisplayName("Persistable 을 구현했다면 @PostLoad 로 플래그를 되돌려야 한다")
    void persistableEntitiesMustResetTheFlagOnLoad() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path p : mainSources()) {
            String body = live(Files.readString(p, StandardCharsets.UTF_8));
            if (!Pattern.compile("\\bimplements\\b[^{]*\\bPersistable\\b").matcher(body).find()) {
                continue;
            }
            // 되돌리지 않으면 불러온 엔티티도 isNew()=true 로 남아 save() 가 INSERT 를 시도하고,
            // 아웃박스 릴레이의 상태 변경(claim → setOutboxStatus → save)이 PK 충돌로 통째로 죽는다.
            // 성능 최적화가 만들 수 있는 최악의 회귀라 따로 못박아 둔다.
            if (!body.contains("@PostLoad")) {
                offenders.add(repoRoot().relativize(p) + " → @PostLoad 없음");
            }
        }

        assertThat(offenders)
                .as("Persistable 의 isNew 플래그를 @PostLoad 로 되돌리지 않으면 "
                        + "불러온 엔티티를 save() 할 때 UPDATE 가 아니라 INSERT 를 시도한다")
                .isEmpty();
    }
}
