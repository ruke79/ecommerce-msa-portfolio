package com.project.common.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kafka · 아웃박스로 나가는 메시지 DTO 에 토큰을 담는 필드가 없다 (docs/54 §1.7).
 *
 * <p>{@code TokenRequest}(JWT 원문 {@code tokenValue} · {@code @Data} 의 toString 이 그대로 찍음)가 남아 있었다 — 쓰는 곳도
 * 듣는 곳도 없는 배선이었지만, 남겨 두면 다음 사람이 "이미 있네" 하고 쓴다. 지웠고, 같은 모양이 다시 들어오면 여기서 막는다.
 * 기기 토큰처럼 꼭 실어야 하면 해시로 싣고 이름에 그렇다고 적는다(예: {@code tokenHash}) — 그때 이 테스트를 고친다.
 *
 * <p>[JA] Kafka・アウトボックスに出るメッセージ DTO にトークンを入れるフィールドがない。{@code TokenRequest} は書く側も聞く側もない
 * 配線だったが、残しておくと次の人が使ってしまう。削除し、同じ形が再び入ればここで止める。
 */
@DisplayName("메시지 DTO — 토큰을 싣는 필드가 없다")
class MessageDtoNoTokenGuardTest {

    private static final Pattern TOKEN = Pattern.compile("(?i).*token.*");

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    @Test
    @DisplayName("🔴 common/message/dto 의 클래스(중첩 포함) 어디에도 이름에 token 이 든 필드가 없다")
    void noTokenFields() throws Exception {
        Path src = repoRoot().resolve("common/src/main/java");
        Path dir = src.resolve("com/project/common/message/dto");
        List<String> offenders = new ArrayList<>();
        int classes = 0;
        for (String name : classNames(src, dir)) {
            Class<?> c = Class.forName(name);
            for (Class<?> k : withNested(c)) {
                classes++;
                for (Field f : k.getDeclaredFields()) {
                    if (TOKEN.matcher(f.getName()).matches()) {
                        offenders.add(k.getName() + "." + f.getName());
                    }
                }
            }
        }
        assertThat(classes).as("검사 대상을 찾지 못했다 — 경로가 바뀌었으면 이 테스트를 고친다").isGreaterThan(10);
        assertThat(offenders).as("토큰을 메시지에 싣지 않는다 — 신원은 userId 로").isEmpty();
    }

    private static List<String> classNames(Path src, Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("package-info.java"))
                    .map(p -> src.relativize(p).toString().replace(".java", "").replace('/', '.'))
                    .sorted().toList();
        }
    }

    private static List<Class<?>> withNested(Class<?> c) {
        List<Class<?>> all = new ArrayList<>(List.of(c));
        for (Class<?> n : c.getDeclaredClasses()) {
            all.addAll(withNested(n));
        }
        return all;
    }
}
