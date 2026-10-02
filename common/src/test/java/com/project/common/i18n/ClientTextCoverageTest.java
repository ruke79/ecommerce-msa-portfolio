package com.project.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 손님에게 갈 수 있는 서버 문구는 모두 일본어 번역이 있다 (docs/60 §4).
 *
 * <p>일본어 화면에서 한국어 오류가 뜨는 것은 누군가 새 예외 문구를 쓰고 카탈로그({@code i18n/client-text.ja.txt})에 번역을 넣지
 * 않은 것이다. 한국어 화면에서는 멀쩡해서 아무도 모른다. 이 검사가 각 서비스의 소스에서 <b>응답으로 나갈 수 있는 자리</b>의 한국어
 * 문구를 뽑아 카탈로그와 맞춘다:
 *
 * <ul>
 *   <li>{@code new …Exception(…)}, {@code new MessageResponse/ApiError/ApiResponse/ErrorMessage/GenericResponse(…)}</li>
 *   <li>{@code ClientMessage.of(…, "…")} 의 대체 문구, {@code .body(…)}, {@code Map.of("message"|"error", …)}, {@code .put("message"|"error", …)}</li>
 *   <li>검증 어노테이션의 {@code message = "…"}</li>
 * </ul>
 *
 * <p>문자열을 이어 붙인 문구는 바뀌는 부분을 {@code {}} 로 — 카탈로그에도 그 모양으로 적는다({@code "주문 " + id + "을 찾을 수 없습니다"}
 * → {@code 주문 {}을 찾을 수 없습니다}). {@code String.format} 의 {@code %s·%d} 도 {@code {}}. 빠진 것은 모듈별로
 * {@code common/build/client-text-missing/<모듈>.txt} 에 카탈로그 모양 그대로 적힌다 — 번역만 채워 넣으면 된다.
 */
@DisplayName("서버 문구 번역 카탈로그")
class ClientTextCoverageTest {

    /** 사람에게 응답하지 않는 모듈 — 게이트웨이(WebFlux, 자체 문구 없음)·레지스트리·설정 서버·사가 투영. */
    static final Set<String> SKIP_MODULES = Set.of("gateway-server", "eureka-server", "config-server", "saga-projection", "e2e-tests");

    static final Pattern HANGUL = Pattern.compile("[\\uac00-\\ud7a3]");
    static final Pattern SITE = Pattern.compile(
            "new\\s+(\\w*Exception|MessageResponse|ApiError|ApiResponse|ErrorMessage|GenericResponse)\\s*\\("
            + "|ClientMessage\\.of\\s*\\("
            + "|\\.body\\s*\\("
            + "|Map\\.of\\s*\\(\\s*\"(?:message|error)\"\\s*,"
            + "|\\.put\\s*\\(\\s*\"(?:message|error)\"\\s*,"
            + "|\\bmessage\\s*=\\s*(?=\")");

    static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return Files.exists(cwd.resolve("settings.gradle")) ? cwd : cwd.getParent();
    }

    /** 모듈별로 뽑은 문구 — 원문 모양 → 처음 나온 곳. */
    static Map<String, Map<String, String>> extractAll() throws IOException {
        Map<String, Map<String, String>> byModule = new LinkedHashMap<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.sorted().toList()) {
                Path src = module.resolve("src/main/java");
                String name = module.getFileName().toString();
                if (!Files.isDirectory(src) || SKIP_MODULES.contains(name)) {
                    continue;
                }
                Map<String, String> found = new LinkedHashMap<>();
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                        extract(Files.readString(f, StandardCharsets.UTF_8), repoRoot().relativize(f).toString(), found);
                    }
                }
                byModule.put(name, found);
            }
        }
        return byModule;
    }

    /** 파일 전체의 한국어 문자열이 응답 문구인 파일 — 오류 코드의 설명, 공통 대체 문구. */
    static final Pattern WHOLE_FILE = Pattern.compile("(ErrorCode|ClientMessage|StatusMessages)\\.java$");

    static void extract(String code, String where, Map<String, String> out) {
        String src = stripComments(code);
        if (WHOLE_FILE.matcher(where).find()) {
            Matcher lit = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(src);
            while (lit.find()) {
                String text = unescape(lit.group(1));
                if (HANGUL.matcher(text).find()) {
                    out.putIfAbsent(text.strip(), where + ":" + lineOf(src, lit.start()));
                }
            }
        }
        Matcher m = SITE.matcher(src);
        while (m.find()) {
            int start = m.end();
            List<String> args = m.group().startsWith("message") || m.group().contains("\"message\"") || m.group().contains("\"error\"")
                    ? List.of(firstExpression(src, start))
                    : arguments(src, start);
            for (String arg : args) {
                String shape = shapeOf(arg);
                if (shape != null && HANGUL.matcher(shape).find()) {
                    out.putIfAbsent(shape, where + ":" + lineOf(src, m.start()));
                }
            }
        }
    }

    /** 주석을 지운다(문자열 안의 // 는 둔다). 줄 수는 유지한다. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' ) {
                if (s.startsWith("\"\"\"", i)) {
                    int end = s.indexOf("\"\"\"", i + 3);
                    end = end < 0 ? s.length() : end + 3;
                    out.append(s, i, end);
                    i = end;
                    continue;
                }
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != '"') {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(s, i, Math.min(j + 1, s.length()));
                i = j + 1;
            } else if (c == '\'' ) {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != '\'') {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(s, i, Math.min(j + 1, s.length()));
                i = j + 1;
            } else if (s.startsWith("//", i)) {
                int end = s.indexOf('\n', i);
                i = end < 0 ? s.length() : end;
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                for (int k = i; k < end; k++) {
                    if (s.charAt(k) == '\n') out.append('\n');
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 여는 괄호 바로 뒤부터 짝이 맞는 닫는 괄호까지를 최상위 쉼표로 나눈다. */
    static List<String> arguments(String s, int from) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int begin = from;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                i = skipString(s, i);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    args.add(s.substring(begin, i).strip());
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(s.substring(begin, i).strip());
                begin = i + 1;
            }
        }
        return args;
    }

    static String firstExpression(String s, int from) {
        List<String> a = arguments(s, from);
        return a.isEmpty() ? "" : a.get(0);
    }

    static int skipString(String s, int i) {
        if (s.startsWith("\"\"\"", i)) {
            int end = s.indexOf("\"\"\"", i + 3);
            return end < 0 ? s.length() : end + 2;
        }
        int j = i + 1;
        while (j < s.length() && s.charAt(j) != '"') {
            j += s.charAt(j) == '\\' ? 2 : 1;
        }
        return j;
    }

    /**
     * 식 하나의 "문구 모양" — 문자열 조각은 그대로, 이어 붙인 식은 {}. 문자열이 하나도 없으면 null.
     * {@code String.format("…%s…", x)} 는 형식 문자열의 %s·%d 를 {}. 삼항식({@code a ? "x" : "y"})은 양쪽을 따로 보지 않는다 —
     * 그런 문구는 카탈로그에 둘 다 적는다.
     */
    static String shapeOf(String expr) {
        String e = expr.strip();
        Matcher fmt = Pattern.compile("^String\\.format\\s*\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\")").matcher(e);
        if (fmt.find()) {
            return unescape(fmt.group(1).substring(1, fmt.group(1).length() - 1)).replaceAll("%[-0-9.,]*[sdfx]", "{}");
        }
        StringBuilder shape = new StringBuilder();
        boolean any = false;
        int depth = 0;
        int i = 0;
        boolean pendingExpr = false;
        while (i < e.length()) {
            char c = e.charAt(i);
            if (c == '"' && depth == 0) {
                int end = skipString(e, i);
                if (pendingExpr) { shape.append("{}"); pendingExpr = false; }
                String lit = e.substring(i + 1, Math.min(end, e.length()));
                shape.append(unescape(lit));
                any = true;
                i = end + 1;
                continue;
            }
            // 🐛 예전에는 여기서도 괄호 깊이를 셌다. 그런데 괄호로 시작하는 식은 아래 "식 끝까지 건너뛰기" 가 닫는 괄호까지 통째로
            //    삼키므로 여는 괄호만 세어지고, 다음 최상위 + 에서 깊이가 1 이라 건너뛰지 못한 채 제자리를 돌았다 —
            //    "…" + (a + 1) + "…" 모양의 문구 하나에 테스트가 끝나지 않았다(docs/68 §17 때 발견). 깊이는 건너뛰기 안에서만 센다.
            if (c == '+' && depth == 0) {
                i++;
                continue;
            }
            if (c == '?' && depth == 0) {
                return null;   // 삼항식 — 모양을 하나로 정할 수 없다
            }
            if (!Character.isWhitespace(c)) {
                pendingExpr = true;
                // 식 끝까지 건너뛴다(최상위 + 까지)
                int d = 0;
                int j = i;
                while (j < e.length()) {
                    char x = e.charAt(j);
                    if (x == '"') { j = skipString(e, j) + 1; continue; }
                    if (x == '(' || x == '[' || x == '{') d++;
                    if (x == ')' || x == ']' || x == '}') d--;
                    if (x == '+' && d == 0) break;
                    j++;
                }
                i = j;
                continue;
            }
            i++;
        }
        if (pendingExpr && any) {
            shape.append("{}");
        }
        return any ? shape.toString().replace("{}{}", "{}").strip() : null;
    }

    static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\");
    }

    static int lineOf(String s, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (s.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** 모든 모듈의 카탈로그 원문(정확히 적힌 모양). */
    static Set<String> catalogSources() throws IOException {
        Set<String> sources = new LinkedHashSet<>();
        // 🐛 처음에는 Files.walk(root, 5) 였다 — 카탈로그는 <모듈>/src/main/resources/i18n/ 아래 6단계라 하나도 찾지 못했고, 모든 문구가
        //    "빠졌다" 로 나왔다. 모듈마다 정해진 자리를 본다(깊이 제한 없이 걸으면 frontend/node_modules 까지 훑는다).
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path f : modules.map(m -> m.resolve("src/main/resources/i18n/client-text.ja.txt")).filter(Files::isRegularFile).toList()) {
                String pending = null;
                for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("=")) {
                        if (pending != null) sources.add(pending);
                        pending = null;
                    } else {
                        pending = line;
                    }
                }
            }
        }
        return sources;
    }

    @Test
    @DisplayName("🔴 응답으로 나갈 수 있는 한국어 문구는 모두 카탈로그에 번역이 있다")
    void everyClientMessageIsTranslated() throws IOException {
        Set<String> catalog = catalogSources();
        Map<String, Map<String, String>> all = extractAll();
        List<String> missing = new ArrayList<>();
        Path outDir = repoRoot().resolve("common/build/client-text-missing");
        Files.createDirectories(outDir);
        for (var module : all.entrySet()) {
            StringBuilder skeleton = new StringBuilder();
            for (var e : module.getValue().entrySet()) {
                if (!catalog.contains(e.getKey())) {
                    missing.add(e.getValue() + "  " + e.getKey());
                    skeleton.append("# ").append(e.getValue()).append('\n').append(e.getKey()).append("\n= \n\n");
                }
            }
            Path out = outDir.resolve(module.getKey() + ".txt");
            if (skeleton.length() > 0) {
                Files.writeString(out, skeleton.toString(), StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(out);
            }
        }
        assertThat(missing).as("번역이 없는 서버 문구 %d개 — common/build/client-text-missing/ 참조", missing.size()).isEmpty();
    }

    @Test
    @DisplayName("추출기 자기 점검 — 이어 붙인 문구·format·주석·로그")
    void extractorWorks() {
        Map<String, String> out = new LinkedHashMap<>();
        extract("""
                class A {
                  void f(long id) {
                    // throw new IllegalStateException("주석 안의 문구");
                    log.warn("로그 문구 {}", id);
                    throw new OrderDomainException("주문 " + id + "을 찾을 수 없습니다.");
                  }
                  void g() { throw new IllegalArgumentException(String.format("쿠폰 %s 은 %d원 이상", a, b)); }
                  void h() { return ResponseEntity.badRequest().body(new MessageResponse("잘못된 요청입니다.")); }
                  void i() { return Map.of("message", "처리했습니다."); }
                  @NotBlank(message = "이름을 입력해 주세요.") String name;
                }
                """, "A.java", out);
        assertThat(out.keySet()).containsExactlyInAnyOrder(
                "주문 {}을 찾을 수 없습니다.", "쿠폰 {} 은 {}원 이상", "잘못된 요청입니다.", "처리했습니다.", "이름을 입력해 주세요.");
    }
}
