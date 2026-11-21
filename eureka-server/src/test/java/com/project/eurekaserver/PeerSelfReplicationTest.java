package com.project.eurekaserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.cloud.netflix.eureka.server.EurekaServerConfigBean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import com.netflix.eureka.cluster.PeerEurekaNodes;

/**
 * 운영 그대로 묶었을 때 eureka-server 가 <b>자기 자신을 피어로 보지 않는가</b> — 매니페스트 env(k8s/30) + ConfigMap(k8s/10) + yml.
 *
 * <p>🐛 2026-11-21(docs/99 §4): 운영 eureka-server 가 5분마다 ~100줄 {@code Batch update failure with HTTP status code 403} 을 냈다 —
 * 운영 ERROR 의 9할. 이 파드도 ConfigMap 의 {@code EUREKA_CLIENT_SERVICEURL_DEFAULTZONE}(자기 Service 주소)을 받아 피어 목록이 자기가
 * 됐는데, {@code my-url} 은 yml 의 localhost 그대로라 {@link PeerEurekaNodes#isThisMyUrl}(문자열 equals)이 걸러내지 못했다 — 모든 등록 ·
 * 하트비트를 인증서 없이 자기에게 복제해 쓰기 enforce 가 403. {@code EurekaServerPropertiesTest.serverDoesNotReplicateToItself} 는
 * yml 끼리만 비교해(localhost = localhost) 통과하고 있었다 — 운영에서 덮어쓰는 env 를 넣지 않은 것이 맹점이었다.
 *
 * <p>[JA] 本番どおりに束ねたとき eureka-server が自分自身をピアとみなさないか — マニフェストの env + ConfigMap + yml。ConfigMap の
 *      defaultZone(自分の Service アドレス)でピアが自分になり、my-url は yml の localhost のままで除外できず、自分への複製が 403 だった。
 */
@DisplayName("eureka-server — 운영 설정에서 자기에게 복제하지 않는다(피어 0)")
class PeerSelfReplicationTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("eureka-server") ? cwd.getParent() : cwd;
    }

    /** ConfigMap 의 키 하나. */
    // [JA] ConfigMap のキー一つ。
    static String configMapValue(String key) throws IOException {
        String cm = Files.readString(repoRoot().resolve("k8s/10-configmap.yaml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?m)^\\s+" + key + ":\\s*\"([^\"]*)\"").matcher(cm);
        assertThat(m.find()).as("k8s/10-configmap.yaml 에 " + key + " 가 없다").isTrue();
        return m.group(1);
    }

    /** eureka-server Deployment 의 env(이름 → 값, 값이 있는 것만). envFrom 보다 우선한다. */
    // [JA] eureka-server Deployment の env(名前 → 値)。envFrom より優先する。
    static Map<String, String> deploymentEnv() throws IOException {
        String yaml = Files.readString(repoRoot().resolve("k8s/30-eureka-server.yaml"), StandardCharsets.UTF_8);
        Map<String, String> env = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?m)^\\s+- name: ([A-Z0-9_]+)\\s*\\n\\s+value: \"([^\"]*)\"").matcher(yaml);
        while (m.find()) {
            env.put(m.group(1), m.group(2));
        }
        return env;
    }

    /** 운영과 같은 우선순위 — Deployment env &gt; ConfigMap(envFrom) &gt; yml. */
    // [JA] 本番と同じ優先順位 — Deployment env > ConfigMap(envFrom) > yml。
    static Binder productionBinder(Map<String, String> deploymentEnv) throws IOException {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("EUREKA_CLIENT_SERVICEURL_DEFAULTZONE", configMapValue("EUREKA_CLIENT_SERVICEURL_DEFAULTZONE"));
        env.putAll(deploymentEnv);
        List<PropertySource<?>> sources = new ArrayList<>();
        sources.add(new SystemEnvironmentPropertySource("systemEnvironment", env));
        sources.addAll(new YamlPropertySourceLoader().load("application-local.yml", new ClassPathResource("application-local.yml")));
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    /** 피어 목록에서 자기 자신으로 걸러지지 않는 URL — 실제 Eureka 의 isThisMyUrl 로. */
    // [JA] ピア一覧のうち自分として除外されない URL — 実際の Eureka の isThisMyUrl で。
    static List<String> peersOtherThanMe(Binder binder) {
        EurekaServerConfigBean server = binder.bind("eureka.server", Bindable.ofInstance(new EurekaServerConfigBean())).get();
        EurekaClientConfigBean client = binder.bind("eureka.client", Bindable.ofInstance(new EurekaClientConfigBean())).get();
        // isThisMyUrl 만 쓴다 — 노드를 만들지 않는다(추상 메서드는 쓰이지 않는다). [JA] isThisMyUrl だけを使う。
        PeerEurekaNodes nodes = new PeerEurekaNodes(null, server, client, null, null) {
            @Override
            protected com.netflix.eureka.cluster.PeerEurekaNode createPeerEurekaNode(String url) {
                throw new UnsupportedOperationException();
            }
        };
        List<String> urls = client.getEurekaServerServiceUrls(EurekaClientConfigBean.DEFAULT_ZONE);
        assertThat(urls).as("피어 후보(defaultZone)").isNotEmpty();
        return urls.stream().filter(u -> !nodes.isThisMyUrl(u)).toList();
    }

    @Test
    @DisplayName("🔴 k8s/30 env + ConfigMap defaultZone + yml 로 묶으면 피어 후보가 모두 '나' — 자기에게 복제하지 않는다")
    void productionConfigHasNoSelfPeer() throws IOException {
        Binder binder = productionBinder(deploymentEnv());

        assertThat(peersOtherThanMe(binder))
                .as("자기 Service 주소가 피어로 남으면 등록 · 하트비트를 인증서 없이 자기에게 복제해 403(EUREKA_SERVER_MYURL 은 "
                        + "ConfigMap defaultZone + '/' 와 글자 하나까지 같아야 한다)")
                .isEmpty();
    }

    @Test
    @DisplayName("my-url 을 매니페스트에서 빼면(2026-11-21 전) 자기 Service 주소가 피어로 남는다 — 이 시험이 잡는 것의 확인")
    void withoutManifestMyUrlTheServerReplicatesToItself() throws IOException {
        Map<String, String> env = new LinkedHashMap<>(deploymentEnv());
        env.remove("EUREKA_SERVER_MYURL");

        assertThat(peersOtherThanMe(productionBinder(env)))
                .containsExactly(configMapValue("EUREKA_CLIENT_SERVICEURL_DEFAULTZONE") + "/");
    }
}
