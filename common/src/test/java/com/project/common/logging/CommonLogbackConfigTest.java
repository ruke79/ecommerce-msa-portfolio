package com.project.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.mock.env.MockEnvironment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;

/**
 * 공통 logback 설정을 <b>스프링이 서비스 기동 때 여는 방식 그대로</b> 열어 본다.
 *
 * <p>이 파일은 모든 서비스가 쓴다. 클래스 이름 오타 하나, 없는 의존성 하나면 스프링이
 * "Logback configuration error detected" 로 <b>열두 서비스의 기동을 한꺼번에 막는다.</b>
 * 서비스를 띄워 보기 전에 여기서 잡는다. 그리고 실제로 찍힌 줄을 읽어 가려졌는지, JSON 이 맞는지 본다.
 */
@DisplayName("공통 logback-spring.xml")
class CommonLogbackConfigTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private LogbackLoggingSystem system;
    private PrintStream originalOut;
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();

    private Path jsonFile() {
        return dir.resolve("test_svc.json");
    }

    /** 서비스 기동과 같은 경로로 설정을 연다. 오류가 있으면 여기서 예외가 난다. */
    private void start(String... extraProperties) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.application.name", "test-svc")
                .withProperty("logging.file.name", jsonFile().toString());
        for (int i = 0; i + 1 < extraProperties.length; i += 2) {
            env.withProperty(extraProperties[i], extraProperties[i + 1]);
        }
        originalOut = System.out;
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));

        system = new LogbackLoggingSystem(getClass().getClassLoader());
        system.beforeInitialize();
        system.initialize(new LoggingInitializationContext(env), "classpath:logback-spring.xml", LogFile.get(env));
    }

    @AfterEach
    void cleanUp() {
        if (originalOut != null) {
            System.setOut(originalOut);
        }
        MDC.clear();
        if (system != null) {
            system.cleanUp();
        }
        ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
    }

    /** 비동기 큐까지 비워 파일을 끝까지 쓰게 한다 — 서비스 종료 때 스프링이 하는 것과 같다. */
    private void flush() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
    }

    private String consoleText() {
        return console.toString(StandardCharsets.UTF_8);
    }

    private List<JsonNode> jsonLines(String text) throws IOException {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(JSON.readTree(line));
            }
        }
        return lines;
    }

    private static void logSample() {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("userId", "736459283746592837");
        LoggerFactory.getLogger("com.project.cart_service.CartService")
                .warn("User not found for email: hong@example.com phone=+821012345678",
                        new IllegalStateException("lookup failed for hong@example.com"));
    }

    @Test
    @DisplayName("🔴 오류 없이 열린다 — 오류가 있으면 모든 서비스가 기동에 실패한다")
    void loadsWithoutErrors() {
        start();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertThat(context.getStatusManager().getCopyOfStatusList())
                .as("logback 설정 상태")
                .noneMatch(s -> s.getLevel() == Status.ERROR);
        assertThat(context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders())
                .as("루트에 붙은 appender").hasNext();
    }

    @Test
    @DisplayName("🔴 콘솔(기본 text): 이메일·전화가 가려지고, traceId·userId 는 그대로 찍힌다")
    void textConsoleIsMasked() {
        start();
        logSample();

        String out = consoleText();
        assertThat(out).contains("h****@example.com").contains("+82****5678")
                .contains("[4bf92f3577b34da6a3ce929d0e0e4736,,736459283746592837]")
                .contains("IllegalStateException");
        assertThat(out).doesNotContain("hong@example.com").doesNotContain("+821012345678");
        assertThat(out.trim()).as("text 모드에서는 JSON 이 콘솔에 섞이지 않는다").doesNotStartWith("{");
    }

    /**
     * 🐛 운영에서 {@code ...enabled.%n2026-09-24 12:53:07 ...} 처럼 줄바꿈 자리에 {@code %n} 이 글자로
     * 찍혀 두 줄이 한 줄로 붙었다. 콘솔 줄이 붙으면 kubectl logs 로 오류를 읽을 수 없다.
     */
    @Test
    @DisplayName("🔴 콘솔: 줄마다 끝에 줄바꿈이 들어간다 — %n 이 글자로 찍히지 않는다")
    void textConsoleEndsEveryLine() {
        start();
        Logger log = LoggerFactory.getLogger("com.project.order_service.OrderService");
        log.info("first");
        log.info("second");

        String out = consoleText();
        assertThat(out).doesNotContain("%n");
        assertThat(out.lines().filter(l -> l.contains(" - first") || l.contains(" - second")).count())
                .as("두 이벤트가 각자 한 줄: [" + out + "]").isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 파일: JSON 한 줄씩 — 메시지·스택트레이스가 가려지고 service·traceId·userId 가 필드로 남는다")
    void jsonFileIsStructuredAndMasked() throws IOException {
        start();
        logSample();
        flush();

        assertThat(jsonFile()).exists();
        String written = Files.readString(jsonFile(), StandardCharsets.UTF_8);
        assertThat(written).doesNotContain("hong@example.com").doesNotContain("+821012345678");

        List<JsonNode> lines = jsonLines(written);
        assertThat(lines).hasSize(1);
        JsonNode line = lines.get(0);
        assertThat(line.path("message").asText())
                .isEqualTo("User not found for email: h****@example.com phone=+82****5678");
        assertThat(line.path("level").asText()).isEqualTo("WARN");
        assertThat(line.path("service").asText()).isEqualTo("test-svc");
        assertThat(line.path("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(line.path("userId").asText()).isEqualTo("736459283746592837");
        assertThat(line.path("logger_name").asText()).isEqualTo("com.project.cart_service.CartService");
        assertThat(line.path("stack_trace").asText())
                .contains("IllegalStateException").contains("h****@example.com").doesNotContain("hong@example.com");
        assertThat(line.has("@timestamp")).isTrue();
        // 스프링이 넣는 컨텍스트 값이 줄마다 붙으면 안 된다(includeContext=false).
        assertThat(line.has("LOG_FILE")).isFalse();
        assertThat(line.has("CONSOLE_FORMAT")).isFalse();
    }

    @Test
    @DisplayName("LOG_CONSOLE_FORMAT=json 이면 콘솔이 JSON 이다 — 텍스트 줄은 섞이지 않는다")
    void jsonConsoleWhenAsked() throws IOException {
        start("app.logging.console-format", "json");
        logSample();

        List<JsonNode> lines = jsonLines(consoleText());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).path("message").asText()).contains("h****@example.com");
        assertThat(lines.get(0).path("service").asText()).isEqualTo("test-svc");
    }

    @Test
    @DisplayName("LOG_JSON_FILE=false 면 파일에 쓰지 않는다 — k8s 에서는 아무도 읽지 않고 디스크만 쓴다")
    void jsonFileCanBeTurnedOff() throws IOException {
        start("app.logging.json-file", "false");
        logSample();
        flush();

        assertThat(consoleText()).contains("h****@example.com");   // 콘솔은 그대로
        String written = Files.exists(jsonFile()) ? Files.readString(jsonFile(), StandardCharsets.UTF_8) : "";
        assertThat(written).isEmpty();
    }
}
