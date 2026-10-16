package com.project.eurekaserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.netflix.eureka.server.EurekaServerConfigBean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code eureka.server.*} 에 모르는 속성이 없다 — 있으면 오류 없이 무시된다.
 *
 * <p>🐛 예전: {@code peer-awareness.enabled} · {@code registry-sync-interval-seconds} 가 있었지만 {@link EurekaServerConfigBean} 에 없는
 * 속성이라 바인딩이 조용히 넘어갔다 — 설정을 읽는 사람은 피어 동기화가 켜져 있다고 믿게 된다.
 *
 * <p>[JA] eureka.server.* に未知の属性がない — あればエラーなしで無視される。以前は存在しない属性が二つあり、黙って無視されていた。
 */
@DisplayName("eureka-server — 설정 속성")
class EurekaServerPropertiesTest {

    private static List<PropertySource<?>> load(String resource) throws IOException {
        return new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
    }

    @Test
    @DisplayName("🔴 application-local.yml 의 eureka.server.* 는 모두 EurekaServerConfigBean 의 속성이다")
    void everyServerPropertyIsKnown() throws IOException {
        Binder binder = new Binder(ConfigurationPropertySources.from(load("application-local.yml")));
        EurekaServerConfigBean bean = new EurekaServerConfigBean();

        assertThatCode(() -> binder.bind("eureka.server", Bindable.ofInstance(bean),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT))).doesNotThrowAnyException();
        assertThat(bean.isEnableSelfPreservation()).as("설정이 실제로 바인딩된다").isFalse();
        assertThat(bean.getPeerNodeReadTimeoutMs()).isEqualTo(30000);
    }

    @Test
    @DisplayName("모르는 속성이 있으면 이 검사가 잡는다 — 검사 자체의 확인")
    void unknownPropertyIsCaught() throws IOException {
        var source = new org.springframework.core.env.MapPropertySource("t",
                java.util.Map.of("eureka.server.peer-awareness.enabled", "true"));
        Binder binder = new Binder(ConfigurationPropertySources.from(List.of(source)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> binder.bind("eureka.server",
                Bindable.ofInstance(new EurekaServerConfigBean()), new NoUnboundElementsBindHandler(BindHandler.DEFAULT)))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }
}
