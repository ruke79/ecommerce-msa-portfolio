package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Tomcat 스레드 지표가 나오는가 — 부하 시험(docs/83 §4)이 스레드 포화를 이 지표로 읽는다.
 *
 * <p>🐛 예전: {@code tomcat_threads_busy_threads} 로 보라고 적었는데 2026-10-18 실습에서 쿼리가 비어 있었다. Spring Boot 는
 * Tomcat 의 MBean 레지스트리를 기본으로 끄고, 꺼져 있으면 {@code TomcatMetrics} 가 스레드 지표를 등록하지 않는다(세션 지표만).
 *
 * <p>[JA] Tomcat のスレッド指標が出るか。MBean レジストリは既定で無効で、無効だとスレッド指標は登録されない。
 */
@DisplayName("Tomcat 스레드 지표 — MBean 레지스트리")
class TomcatMetricsConfigTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    @Test
    @DisplayName("🔴 공통 설정이 MBean 레지스트리를 켠다 — 꺼져 있으면 tomcat_threads_* 가 없다")
    void commonConfigEnablesTheMbeanRegistry() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application-common", new ClassPathResource("application-common.yml"));
        assertThat(sources).isNotEmpty();
        assertThat(sources.get(0).getProperty("server.tomcat.mbeanregistry.enabled")).hasToString("true");
    }

    @Test
    @DisplayName("서비스 설정이 그것을 다시 끄지 않는다")
    void noServiceTurnsItBackOff() throws IOException {
        List<Path> ymls;
        try (Stream<Path> s = Files.walk(repoRoot(), 5)) {
            ymls = s.filter(p -> p.toString().replace('\\', '/').contains("/src/main/resources/"))
                    .filter(p -> p.getFileName().toString().matches("(application|bootstrap).*\\.ya?ml"))
                    .toList();
        }
        assertThat(ymls).hasSizeGreaterThan(10);
        for (Path y : ymls) {
            String text = Files.readString(y);
            assertThat(text.contains("mbeanregistry") && !y.endsWith("application-common.yml"))
                    .as("공통 설정 밖에서 mbeanregistry 를 정한다: " + repoRoot().relativize(y))
                    .isFalse();
        }
    }
}
