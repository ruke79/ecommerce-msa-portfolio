package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.core.io.support.SpringFactoriesLoader;

/**
 * B-83: 스토어 비밀번호는 {@code JAVA_TOOL_OPTIONS} 밖으로 — JVM 이 기동마다 그 변수를 통째로 stderr 에 찍는다.
 *
 * <p>🐛 예전: 13개 매니페스트가 {@code -Djavax.net.ssl.trustStorePassword=$(LOCAL_KEY_STORE_PASSWORD)}(서비스는 keyStorePassword 도)를
 * 넣어, "Picked up JAVA_TOOL_OPTIONS: …" 한 줄로 파드 로그 · Loki 에 평문 비밀번호가 남았다(logback 의 마스킹을 거치지 않는다).
 *
 * <p>[JA] B-83: ストアのパスワードは JAVA_TOOL_OPTIONS の外へ — JVM が起動のたびにその変数をまるごと stderr に出す。
 */
@DisplayName("B-83: JVM 스토어 비밀번호 — JAVA_TOOL_OPTIONS 가 아니라 환경 변수에서")
class JvmSslPasswordsTest {

    private static Path root() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    @Test
    @DisplayName("환경 변수 → javax.net.ssl.*Password — 비어 있으면 건너뛰고, 이미 정해진 속성은 덮지 않는다")
    void movesPasswordsFromEnv() {
        Properties p = new Properties();
        JvmSslPasswords.apply(Map.of("JAVAX_NET_SSL_TRUST_STORE_PASSWORD", "t-pw", "JAVAX_NET_SSL_KEY_STORE_PASSWORD", " "), p);
        assertThat(p.getProperty("javax.net.ssl.trustStorePassword")).isEqualTo("t-pw");
        assertThat(p.getProperty("javax.net.ssl.keyStorePassword")).isNull();

        p.setProperty("javax.net.ssl.keyStorePassword", "from-cmdline");
        JvmSslPasswords.apply(Map.of("JAVAX_NET_SSL_KEY_STORE_PASSWORD", "env"), p);
        assertThat(p.getProperty("javax.net.ssl.keyStorePassword")).isEqualTo("from-cmdline");
    }

    @Test
    @DisplayName("🔴 SpringApplication 의 실행 리스너로 등록돼 있다 — 설정 서버 · Eureka 의 첫 TLS 보다 먼저 돈다")
    void registeredAsRunListener() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(SpringApplicationRunListener.class, getClass().getClassLoader()))
                .contains(JvmSslPasswords.RunListener.class.getName());
    }

    /** JAVA_TOOL_OPTIONS · JDK_JAVA_OPTIONS 의 값(여러 줄 >- 포함)과 그 파일의 env 이름들. */
    private static List<String> jvmOptionValues(String yaml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^(\\s*)- name: (?:JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS)\\s*\\n((?:\\1  .*\\n?)+)").matcher(yaml);
        while (m.find()) {
            out.add(m.group(2));
        }
        return out;
    }

    @Test
    @DisplayName("🔴 매니페스트 · 스크립트의 JVM 옵션 변수에 *Password= 가 없다 — 있으면 기동마다 로그에 평문으로 남는다")
    void noPasswordInJvmOptions() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> k8s = Files.walk(root().resolve("k8s"))) {
            k8s.filter(f -> f.toString().endsWith(".yaml")).forEach(files::add);
        }
        try (Stream<Path> overlays = Files.walk(root().resolve("k8s-overlays"))) {
            overlays.filter(f -> f.toString().endsWith(".yaml")).forEach(files::add);
        }
        try (Stream<Path> ops = Files.list(root().resolve("ops"))) {
            ops.filter(f -> f.toString().endsWith(".sh")).forEach(files::add);
        }
        for (Path f : files) {
            String text = Files.readString(f);
            for (String v : jvmOptionValues(text)) {
                if (v.contains("Password=")) {
                    offenders.add(root().relativize(f).toString());
                }
            }
            // 셸 스크립트: JAVA_TOOL_OPTIONS="… Password=…" · JTO="…"
            for (String line : text.split("\n")) {
                if (line.matches(".*(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|JTO)=\".*Password=.*")) {
                    offenders.add(root().relativize(f) + ": " + line.strip().substring(0, Math.min(60, line.strip().length())));
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("🔴 JAVA_TOOL_OPTIONS 로 스토어를 바꾸는 매니페스트는 그 비밀번호 변수도 준다 — 없으면 스토어를 열지 못한다")
    void everyStoreHasItsPasswordVariable() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> k8s = Files.list(root().resolve("k8s"))) {
            for (Path f : k8s.filter(x -> x.toString().endsWith(".yaml")).sorted().toList()) {
                String text = Files.readString(f);
                for (String v : jvmOptionValues(text)) {
                    if (v.contains("-Djavax.net.ssl.trustStore=") && !text.contains("- name: JAVAX_NET_SSL_TRUST_STORE_PASSWORD")) {
                        missing.add(f.getFileName() + " trust");
                    }
                    if (v.contains("-Djavax.net.ssl.keyStore=") && !text.contains("- name: JAVAX_NET_SSL_KEY_STORE_PASSWORD")) {
                        missing.add(f.getFileName() + " key");
                    }
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("🔴 common 을 쓰지 않는 게이트웨이 · config-server 는 main 에서 SpringApplication.run 전에 같은 일을 한다")
    void gatewayAndConfigServerApplyBeforeRun() throws IOException {
        for (String main : List.of("gateway-server/src/main/java/com/project/gatewayserver/GatewayServerApplication.java",
                "config-server/src/main/java/com/project/configserver/ConfigServerApplication.java")) {
            String src = Files.readString(root().resolve(main));
            int apply = src.indexOf("applySslPasswordsFromEnv(System.getenv(), System.getProperties());");
            int run = src.indexOf("SpringApplication.run(");
            assertThat(apply).as(main).isPositive().isLessThan(run);
            JvmSslPasswords.ENV_TO_PROPERTY.forEach((env, prop) -> assertThat(src).as(main).contains(
                    "\"" + env + "\"", "\"" + prop + "\""));
        }
    }
}
