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
 * 모든 Kafka 발행이 <b>키를 싣는지</b>를 지키는 가드레일.
 *
 * <p>키가 없는 레코드는 파티셔너가 라운드로빈(스티키)으로 흩뿌린다. 토픽이 파티션 하나일 때는
 * 아무 차이가 없어서 — 이 저장소의 주 토픽 전부가 그렇다(브로커 기본값 num.partitions=1) —
 * 눈에 띄지 않는다. 그런데 파티션을 늘리는 순간 같은 주문·같은 방·같은 사용자의 이벤트가 서로
 * 다른 파티션으로 가고, 소비자는 파티션 사이의 순서를 보장받지 못하므로 결제 응답보다 롤백이
 * 먼저 처리되거나 채팅이 뒤섞인다. **파티션을 늘린 사람은 키를 빠뜨린 코드를 본 적이 없다** —
 * 그래서 발행 지점에서 미리 못박는다(docs/38 §1.15).
 *
 * <p>검사 규칙: {@code kafkaTemplate.send(...)} 호출은 (topic, key, value) 세 인자이거나,
 * {@code MessageBuilder} 로 만든 메시지라면 같은 문장 안에 {@code KafkaHeaders.KEY} 헤더가 있어야 한다.
 */
@DisplayName("Kafka 가드레일 · 발행마다 키가 있는가")
class KafkaProducerKeyGuardrailTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static String live(String source) {
        return source.lines()
                .filter(l -> !l.strip().startsWith("//") && !l.strip().startsWith("*") && !l.strip().startsWith("/*"))
                .reduce("", (a, b) -> a + b + "\n");
    }

    /** 문장 하나(세미콜론까지)를 통째로 잡는다 — MessageBuilder 체인은 여러 줄이다. */
    private static final Pattern SEND_STATEMENT = Pattern.compile("kafkaTemplate\\s*\\.\\s*send\\s*\\(([^;]*);", Pattern.DOTALL);
    private static final Pattern BUILDER_STATEMENT = Pattern.compile("MessageBuilder\\s*\\.\\s*withPayload\\s*\\(([^;]*);", Pattern.DOTALL);

    @Test
    @DisplayName("kafkaTemplate.send 는 (topic, key, value) 이거나 KafkaHeaders.KEY 를 실은 Message 여야 한다")
    void everyPublishCarriesAKey() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(repoRoot())) {
            List<Path> sources = paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .toList();

            for (Path p : sources) {
                String body = live(Files.readString(p, StandardCharsets.UTF_8));
                if (!body.contains("kafkaTemplate")) {
                    continue;
                }
                String rel = repoRoot().relativize(p).toString();

                Matcher builder = BUILDER_STATEMENT.matcher(body);
                while (builder.find()) {
                    if (!builder.group(1).contains("KafkaHeaders.KEY")) {
                        offenders.add(rel + " → MessageBuilder 에 KafkaHeaders.KEY 없음");
                    }
                }

                // 프로듀서 설정 맵마다 AggregateKeyPartitioner 가 등록돼 있어야 한다 — 그래야 "order-123" 과
                // "123" 이 같은 파티션이고, CDC 릴레이와도 공동 파티셔닝된다(docs/38 §1.15).
                if (body.contains("ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG")
                        && !body.contains("PARTITIONER_CLASS_CONFIG")) {
                    offenders.add(rel + " → 프로듀서 설정에 PARTITIONER_CLASS_CONFIG(AggregateKeyPartitioner) 없음");
                }

                Matcher send = SEND_STATEMENT.matcher(body);
                while (send.find()) {
                    String args = send.group(1);
                    // Message 객체 하나를 넘기는 형태는 위의 builder 검사가 맡는다.
                    if (args.contains("kafkaMessage") || args.contains("Message")) {
                        continue;
                    }
                    if (topLevelArgCount(args) < 3) {
                        offenders.add(rel + " → send(topic, value) 두 인자 — 키가 없다");
                    }
                }
            }
        }

        assertThat(offenders)
                .as("키 없는 발행은 파티션을 늘리는 순간 같은 주문/방/사용자의 이벤트 순서를 잃는다. "
                        + "send(topic, key, value) 로 바꾸거나 MessageBuilder 에 KafkaHeaders.KEY 를 실을 것")
                .isEmpty();
    }

    /** 괄호·제네릭 안의 쉼표는 세지 않는다. */
    private static int topLevelArgCount(String args) {
        int depth = 0;
        int count = 1;
        for (char c : args.toCharArray()) {
            if (c == '(' || c == '<' || c == '{') depth++;
            else if (c == ')' || c == '>' || c == '}') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        // 마지막 닫는 괄호가 group 에 포함돼 depth 가 -1 로 끝나는 것은 무시한다.
        return count;
    }
}
