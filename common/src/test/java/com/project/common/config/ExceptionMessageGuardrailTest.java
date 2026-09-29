package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * 컨트롤러·예외 처리기가 예외 문구를 <b>그대로</b> 응답에 싣지 않는지 (docs/57 §1).
 *
 * <p>{@code catch (RuntimeException e)} 는 서비스가 쓴 문구와 JDBC·JPA·Feign 의 문구를 함께 받는다.
 * {@code e.getMessage()} 를 본문에 실으면 SQL·제약 이름·입력값이 클라이언트로 간다. 문구는
 * {@code ClientMessage.of(e)} 를 거친다 — 로그에는 그대로 써도 된다.
 *
 * <p>검사 단위는 문장(세미콜론까지)이다. 여러 줄에 걸친 {@code log.warn(...)} 도 로그로 본다.
 */
@DisplayName("가드레일 · 예외 문구를 응답에 그대로 싣지 않는다")
class ExceptionMessageGuardrailTest {

    /** 예외의 문구를 읽는 호출. 검증 위반({@code violation.getMessage()})은 애너테이션에 우리가 쓴 문구라 뺀다. */
    private static final Pattern MESSAGE_CALL = Pattern.compile("(?<!violation)\\.get(Localized)?Message\\(\\)");
    private static final Pattern LOG_CALL =
            Pattern.compile("(?s).*\\b(log|logger|LOG|LOGGER)\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(.*");

    /** 문자열 리터럴 안의 {@code ; { }} 는 경계로 보지 않고 문장을 자른다 — 로그 형식 문자열의 "{}" 때문이다. */
    private static List<String> statements(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            cur.append(c);
            if (inString || inChar) {
                if (c == '\\' && i + 1 < body.length()) {
                    cur.append(body.charAt(++i));
                } else if (inString && c == '"') {
                    inString = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && i + 1 < body.length() && body.charAt(i + 1) == '/') {
                cur.setLength(cur.length() - 1);                   // 줄 끝 주석 — 안의 ' 가 문자 리터럴로 읽히면 안 된다
                int eol = body.indexOf('\n', i);
                i = eol < 0 ? body.length() : eol - 1;
            } else if (c == '/' && i + 1 < body.length() && body.charAt(i + 1) == '*') {
                cur.setLength(cur.length() - 1);
                int end = body.indexOf("*/", i + 2);
                i = end < 0 ? body.length() : end + 1;
            } else if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == ';' || c == '{' || c == '}') {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static String live(String source) {
        return source.lines()
                .filter(l -> !l.strip().startsWith("//") && !l.strip().startsWith("*") && !l.strip().startsWith("/*"))
                .reduce("", (a, b) -> a + b + "\n");
    }

    private static boolean isResponseLayer(Path p, String body) {
        return p.getFileName().toString().contains("Controller")
                || body.contains("@ControllerAdvice") || body.contains("@RestControllerAdvice");
    }

    @Test
    @DisplayName("컨트롤러·예외 처리기의 getMessage() 는 로그에서만 쓴다")
    void noRawMessageInResponses() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(repoRoot())) {
            List<Path> sources = paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .toList();
            for (Path p : sources) {
                String body = live(Files.readString(p, StandardCharsets.UTF_8));
                if (!isResponseLayer(p, body)) {
                    continue;
                }
                for (String stmt : statements(body)) {
                    if (!MESSAGE_CALL.matcher(stmt).find() || LOG_CALL.matcher(stmt).matches()) {
                        continue;
                    }
                    offenders.add(repoRoot().relativize(p) + " → " + stmt.strip().replaceAll("\\s+", " "));
                }
            }
        }
        assertThat(offenders)
                .as("예외 문구는 ClientMessage.of(e) 를 거쳐 응답에 싣는다 — JDBC·NPE 문구가 새지 않게")
                .isEmpty();
    }
}
