package com.project.userservice.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
 * user-service 의 로그에 이메일 · 사용자명을 싣지 않는다(보안 경로 로그에는 userId 만).
 *
 * <p>🐛 예전: 가입 경로가 {@code email=} 을 WARN/INFO 로, SSE 연결이 username(실명 · provider_id)을 연결마다 INFO 로 남겼다.
 *
 * <p>[JA] user-service のログにメール · ユーザー名を載せない。以前は登録経路が email= を、SSE 接続が username を残していた。
 */
@DisplayName("로그 개인정보 — 이메일 · 사용자명 없음")
class SecurityLogPiiGuardTest {

    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\((.*?)\\);", Pattern.DOTALL);

    static String code(String src) {
        String noBlock = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(src).replaceAll(" ");
        return noBlock.replaceAll("(?m)(^|\\s)//.*$", "$1");
    }

    @Test
    @DisplayName("🔴 log.*(…) 의 인자에 getEmail() · getUsername() · email= 이 없다")
    void noEmailOrUsernameInLogs() throws IOException {
        Path cwd = Paths.get("").toAbsolutePath();
        Path main = (cwd.getFileName().toString().equals("user-service") ? cwd : cwd.resolve("user-service")).resolve("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = LOG_CALL.matcher(code(Files.readString(f)));
                while (m.find()) {
                    String args = m.group(2);
                    if (args.contains("getEmail()") || args.contains("getUsername()") || args.matches("(?s).*\\bemail=\\{\\}.*")) {
                        offenders.add(main.relativize(f) + ": " + args.replaceAll("\\s+", " ").trim());
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
