package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JWT 키 링이 세 곳(발급 user-service · 검증 gateway · chat)에 <b>똑같이</b> 걸려 있는가, 그리고 ops/46 이 키를 화면에 내지 않는가(docs/68 §17).
 *
 * <p>한 곳이라도 링 설정을 읽지 않으면 그 서비스는 legacy 키만 안다 — 전환(activate)하는 순간 그 서비스만 새 토큰을 거절하고, 다른 곳은
 * 멀쩡해서 원인을 찾기 어렵다.
 */
// [JA] JWT 鍵リングが発行側と検証側の 3 か所に同じように掛かっているか、ops/46 が鍵を画面に出さないか。
@DisplayName("JWT 키 링 — 세 서비스 배선 · ops/46")
class JwtKeyRingWiringTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    @Test
    @DisplayName("JWT_SECRET_KEY 를 읽는 설정마다 링 세 값도 같은 환경 변수로 읽는다 — user · gateway · chat 모두")
    void everyJwtConsumerReadsTheRing() throws IOException {
        List<Path> consumers = new ArrayList<>();
        // 모듈마다 src/main/resources 만 본다 — 저장소 전체를 걸으면 frontend/node_modules 를 훑느라 멈춘 것처럼 오래 걸린다.
        List<Path> ymls = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path resources = module.resolve("src/main/resources");
                if (Files.isDirectory(resources)) {
                    try (Stream<Path> files = Files.list(resources)) {
                        files.filter(f -> f.toString().endsWith(".yml")).forEach(ymls::add);
                    }
                }
            }
        }
        {
            for (Path p : ymls) {
                String yml = Files.readString(p, StandardCharsets.UTF_8);
                if (!yml.contains("jwtSecret: ${JWT_SECRET_KEY}")) {
                    continue;
                }
                consumers.add(p);
                assertThat(yml).as(p + " — 링을 읽지 않으면 전환 순간 이 서비스만 새 토큰을 거절한다")
                        .contains("jwtSigningKeys: ${JWT_SIGNING_KEYS:}")
                        .contains("jwtActiveKid: ${JWT_ACTIVE_KID:}")
                        .contains("jwtAcceptLegacy: ${JWT_ACCEPT_LEGACY:true}");
            }
        }
        assertThat(consumers.stream().map(p -> p.toString().split("/src/")[0].replaceAll(".*/", "")).toList())
                .contains("user-service", "gateway-server", "chat-service");
    }

    @Test
    @DisplayName("🔒 ops/46 은 키 값을 출력하지 않는다 — 새 키 · 링 전체는 변수로만, 클러스터에는 stdin 으로")
    void rotationScriptNeverPrintsKeys() throws IOException {
        String script = Files.readString(repoRoot().resolve("ops/46-rotate-jwt-key.sh"), StandardCharsets.UTF_8);
        for (String line : script.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#") || !(t.startsWith("echo") || t.contains("die ") || t.startsWith("printf '%s\\n'"))) {
                continue;
            }
            // kids_of 는 이름만 뽑는다(kid=비밀 의 왼쪽) — 그 호출은 출력해도 된다.
            t = t.replace("kids_of \"$KEYS\"", "kids_of");
            assertThat(t).as("화면에 키가 나갈 수 있는 줄: " + t)
                    .doesNotContain("$NEW").doesNotContain("$NEXT").doesNotContain("$KEYS").doesNotContain("${KEYS");
        }
        assertThat(script).contains("--patch-file /dev/stdin").contains("unset NEW NEXT");
    }
}
