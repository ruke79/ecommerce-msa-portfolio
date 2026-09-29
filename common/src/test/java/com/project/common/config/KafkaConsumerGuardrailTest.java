package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 컨슈머 설정 가드레일 — 세 가지를 지킨다.
 *
 * <ol>
 *   <li><b>타입 없는 {@code JsonDeserializer} 금지.</b> {@code new JsonDeserializer<>()} 는 레코드의
 *       {@code __TypeId__} 헤더에만 의존한다. Debezium CDC 릴레이는 헤더를 싣지 못하므로 그 레코드는
 *       전량 DLT 로 간다(docs/38 §5 A3). 대상 타입을 주고 헤더는 "있으면" 쓴다.</li>
 *   <li><b>어사이너는 {@code RebalanceSettings} 로.</b> 모듈마다 손으로 적으면 두 단계 업그레이드
 *       (eager → cooperative)를 한 곳이 빼먹고, 그 그룹은 롤링 배포 중 깨진다.</li>
 *   <li><b>컨테이너 팩토리마다 리밸런스 리스너.</b> 리스너가 없는 팩토리는 파티션을 내려놓을 때
 *       커밋도 기록도 없다.</li>
 * </ol>
 */
@DisplayName("Kafka 컨슈머 가드레일")
class KafkaConsumerGuardrailTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static String live(String source) {
        return source.lines()
                .filter(l -> !l.strip().startsWith("//") && !l.strip().startsWith("*") && !l.strip().startsWith("/*"))
                .reduce("", (a, b) -> a + b + "\n");
    }

    private static final Pattern GROUP_ID = Pattern.compile("ConsumerConfig\\.GROUP_ID_CONFIG");
    private static final Pattern FACTORY = Pattern.compile("\\.setConsumerFactory\\(");
    private static final Pattern LISTENER = Pattern.compile("setConsumerRebalanceListener\\(");
    private static final Pattern ASSIGNOR = Pattern.compile("RebalanceSettings\\.assignors\\(");

    @Test
    @DisplayName("타입 없는 JsonDeserializer 가 없고, 그룹마다 어사이너가, 팩토리마다 리밸런스 리스너가 있다")
    void consumerConfigsAreComplete() throws IOException {
        List<String> offenders = new ArrayList<>();
        int inspected = 0;

        try (Stream<Path> paths = Files.walk(repoRoot())) {
            List<Path> configs = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("KafkaConsumerConfig.java"))
                    .filter(p -> p.toString().contains("/src/main/java/") && !p.toString().contains("/build/"))
                    .toList();

            for (Path p : configs) {
                inspected++;
                String body = live(Files.readString(p, StandardCharsets.UTF_8));
                String rel = repoRoot().relativize(p).toString();

                if (body.contains("new JsonDeserializer<>()")) {
                    offenders.add(rel + " → 타입 없는 new JsonDeserializer<>() — CDC 레코드(헤더 없음)를 못 읽는다");
                }
                int groups = count(GROUP_ID, body);
                int assignors = count(ASSIGNOR, body);
                if (assignors < groups) {
                    offenders.add(rel + " → 그룹 " + groups + "개 중 어사이너(RebalanceSettings.assignors) " + assignors + "개");
                }
                int factories = count(FACTORY, body);
                int listeners = count(LISTENER, body);
                if (listeners < factories) {
                    offenders.add(rel + " → 컨테이너 팩토리 " + factories + "개 중 리밸런스 리스너 " + listeners + "개");
                }
            }
        }

        assertThat(inspected).as("KafkaConsumerConfig 파일 수").isGreaterThanOrEqualTo(9);
        assertThat(offenders).isEmpty();
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
