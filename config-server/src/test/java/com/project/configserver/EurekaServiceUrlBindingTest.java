package com.project.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * config-server 가 운영에서 <b>ConfigMap 의 Eureka 주소로 붙는가</b> — yml + ConfigMap 의 {@code EUREKA_CLIENT_SERVICEURL_DEFAULTZONE}.
 *
 * <p>🐛 2026-11-21(docs/99 §4): 운영 config-server 가 7일 내내 {@code http://localhost:8761} 로 하트비트를 보내 {@code Connection refused}
 * 였다(5분마다 ~10줄 ERROR) — 한 번도 Eureka 에 등록되지 않았다. env 가 있는데도 쓰이지 않은 까닭: {@link EurekaClientConfigBean} 의
 * {@code serviceUrl} 은 처음부터 {@code {"defaultZone": 기본값}} 을 갖고, env 변수 이름에는 대소문자가 없어 yml 에 {@code defaultZone} 키가
 * 없으면 env 값은 소문자 키 {@code defaultzone} 으로 따로 들어간다 — 조회는 {@code get("defaultZone")} 이다. 다른 서비스는 common 의
 * application-common.yml 에 그 키가 있어 괜찮았다.
 *
 * <p>[JA] config-server が本番で ConfigMap の Eureka アドレスにつながるか。yml に defaultZone キーがないと env の値は小文字キー
 *      "defaultzone" に入り、get("defaultZone") は既定値(平文 localhost)のままだった — 一度も Eureka に登録されなかった。
 */
@DisplayName("config-server — Eureka 주소는 ConfigMap 의 것으로 붙는다")
class EurekaServiceUrlBindingTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("config-server") ? cwd.getParent() : cwd;
    }

    private static String configMapDefaultZone() throws IOException {
        String cm = Files.readString(repoRoot().resolve("k8s/10-configmap.yaml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?m)^\\s+EUREKA_CLIENT_SERVICEURL_DEFAULTZONE:\\s*\"([^\"]*)\"").matcher(cm);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static List<String> serviceUrls(boolean withYml) throws IOException {
        List<PropertySource<?>> sources = new ArrayList<>();
        sources.add(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("EUREKA_CLIENT_SERVICEURL_DEFAULTZONE", configMapDefaultZone())));
        if (withYml) {
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            sources.addAll(loader.load("application-local.yml", new ClassPathResource("application-local.yml")));
            sources.addAll(loader.load("application.yml", new ClassPathResource("application.yml")));
        }
        EurekaClientConfigBean client = new Binder(ConfigurationPropertySources.from(sources))
                .bind("eureka.client", Bindable.ofInstance(new EurekaClientConfigBean())).get();
        return client.getEurekaServerServiceUrls(EurekaClientConfigBean.DEFAULT_ZONE);
    }

    @Test
    @DisplayName("🔴 yml + ConfigMap env → ConfigMap 의 주소(https · 클러스터 DNS)로 붙는다 — 평문 localhost 가 아니다")
    void productionUsesConfigMapEurekaUrl() throws IOException {
        assertThat(serviceUrls(true))
                .as("yml 에 eureka.client.service-url.defaultZone 키가 없으면 env 가 덮어쓰지 못하고 기본값 http://localhost:8761 로 붙는다")
                .containsExactly(configMapDefaultZone() + "/");
    }

    @Test
    @DisplayName("yml 에 키가 없으면(2026-11-21 전) env 가 있어도 기본값 localhost — 이 시험이 잡는 함정의 확인")
    void envAloneDoesNotOverrideTheDefaultKey() throws IOException {
        assertThat(serviceUrls(false)).containsExactly("http://localhost:8761/eureka/");
    }

    @Test
    @DisplayName("로컬 기본값은 다른 서비스(common 의 application-common.yml)와 같다")
    void localDefaultMatchesCommon() throws IOException {
        String common = Files.readString(repoRoot().resolve("common/src/main/resources/application-common.yml"), StandardCharsets.UTF_8);
        String mine = Files.readString(repoRoot().resolve("config-server/src/main/resources/application-local.yml"), StandardCharsets.UTF_8);
        Pattern p = Pattern.compile("(?m)^\\s+defaultZone:\\s*(\\S+)");
        Matcher a = p.matcher(common);
        Matcher b = p.matcher(mine);
        assertThat(a.find()).isTrue();
        assertThat(b.find()).as("config-server yml 에 defaultZone 키가 있어야 env 가 덮어쓴다").isTrue();
        assertThat(b.group(1)).isEqualTo(a.group(1));
    }
}
