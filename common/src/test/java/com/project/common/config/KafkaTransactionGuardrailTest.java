package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 트랜잭셔널 프로듀서의 {@code transactional.id} 규칙을 지킨다(docs/38 §1.17).
 *
 * <p>{@code transactional.id} 는 "이 프로듀서의 다음 화신"을 식별하는 값이라 <b>재시작 사이에 같아야</b>
 * 펜싱이 된다. 기동마다 {@code UUID.randomUUID()} 를 넣으면 컴파일도 기동도 멀쩡하고 트랜잭션도
 * 되지만, 좀비 프로듀서는 영원히 펜싱되지 않고 브로커에는 다시는 안 쓰일 트랜잭션 상태가 쌓인다 —
 * 즉 <b>설정이 걸려 있다고 믿지만 그 설정이 주는 보장은 없는</b> 상태다. 반대로 접두사가 같은
 * id 를 두 팩토리(또는 두 서비스)가 쓰면 서로를 펜싱해 'ProducerFencedException' 이 난다.
 */
@DisplayName("Kafka 가드레일 · transactional.id 는 안정적이고 유일해야 한다")
class KafkaTransactionGuardrailTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static final Pattern STABLE_ID = Pattern.compile("KafkaTransactionalIds\\s*\\.\\s*stable\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");

    @Test
    @DisplayName("TRANSACTIONAL_ID_CONFIG 는 KafkaTransactionalIds.stable(접두사) 로만 넣고, 접두사는 저장소 전체에서 유일하다")
    void transactionalIdsAreStableAndUnique() throws IOException {
        List<String> offenders = new ArrayList<>();
        Map<String, String> prefixOwners = new HashMap<>();

        try (Stream<Path> paths = Files.walk(repoRoot())) {
            List<Path> sources = paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .toList();

            for (Path p : sources) {
                String rel = repoRoot().relativize(p).toString();
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                        continue;
                    }
                    if (!line.contains("TRANSACTIONAL_ID_CONFIG")) {
                        continue;
                    }
                    // 설정을 **빼는** 줄은 규칙 밖이다(트랜잭션을 쓰지 않겠다는 뜻).
                    if (line.contains(".remove(")) {
                        continue;
                    }
                    if (line.contains("UUID")) {
                        offenders.add(rel + ":" + (i + 1) + " → 기동마다 다른 id (UUID) — 펜싱이 되지 않는다");
                        continue;
                    }
                    Matcher m = STABLE_ID.matcher(line);
                    if (!m.find()) {
                        offenders.add(rel + ":" + (i + 1) + " → KafkaTransactionalIds.stable(\"<유일 접두사>\") 로 넣을 것");
                        continue;
                    }
                    String prefix = m.group(1);
                    String owner = prefixOwners.putIfAbsent(prefix, rel + ":" + (i + 1));
                    if (owner != null) {
                        offenders.add(rel + ":" + (i + 1) + " → 접두사 '" + prefix + "' 이 " + owner
                                + " 와 같다 — 두 프로듀서가 서로를 펜싱한다");
                    }
                }
            }
        }

        assertThat(offenders)
                .as("transactional.id 는 재시작 사이에 같아야 펜싱이 되고, 프로듀서마다 달라야 서로를 펜싱하지 않는다")
                .isEmpty();
        // 규칙이 실제로 무언가를 검사하고 있는지 — 트랜잭셔널 프로듀서가 하나도 없으면 이 테스트는 공허하다.
        assertThat(prefixOwners).as("order-service 의 사가 보상 프로듀서 넷").hasSizeGreaterThanOrEqualTo(4);
    }
}
