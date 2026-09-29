package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link KafkaSecuritySettings} 회귀 테스트.
 *
 * <p>여기서 검증하는 것들은 전부 "PLAINTEXT 로 개발하는 동안에는 절대 드러나지 않고,
 * 프로토콜을 바꾸는 순간 기동이 죽는" 종류다. 그래서 빌드가 대신 지킨다.
 */
class KafkaSecuritySettingsTest {

    /**
     * 키 이름을 문자열로 쓴 대가를 여기서 갚는다. kafka-clients 는 테스트 클래스패스에만
     * 있으므로, 이 검증이 런타임에 그 의존성을 끌고 오지는 않는다.
     */
    @Test
    @DisplayName("설정 키 이름이 Kafka 클라이언트 상수와 정확히 일치한다")
    void keyNamesMatchKafkaConstants() {
        assertThat(KafkaSecuritySettings.SECURITY_PROTOCOL)
                .isEqualTo(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG);
        assertThat(KafkaSecuritySettings.SSL_TRUSTSTORE_LOCATION)
                .isEqualTo(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG);
        assertThat(KafkaSecuritySettings.SSL_TRUSTSTORE_PASSWORD)
                .isEqualTo(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG);
        assertThat(KafkaSecuritySettings.SSL_TRUSTSTORE_TYPE)
                .isEqualTo(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG);
        assertThat(KafkaSecuritySettings.SSL_KEYSTORE_LOCATION)
                .isEqualTo(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG);
        assertThat(KafkaSecuritySettings.SSL_KEYSTORE_PASSWORD)
                .isEqualTo(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG);
        assertThat(KafkaSecuritySettings.SSL_KEYSTORE_TYPE)
                .isEqualTo(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG);
        assertThat(KafkaSecuritySettings.SASL_MECHANISM)
                .isEqualTo(SaslConfigs.SASL_MECHANISM);
        assertThat(KafkaSecuritySettings.SASL_JAAS_CONFIG)
                .isEqualTo(SaslConfigs.SASL_JAAS_CONFIG);
    }

    @Test
    @DisplayName("PLAINTEXT 는 security.protocol 하나만 남긴다")
    void plaintextCarriesNothingElse() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kafka.security.protocol", "PLAINTEXT")
                // 로컬 기본값처럼 빈 값이 함께 들어와도 새어 나가면 안 된다.
                .withProperty("kafka.ssl.trust-store-location", "")
                .withProperty("kafka.ssl.key-store-location", "");

        assertThat(KafkaSecuritySettings.from(env))
                .containsExactly(Map.entry("security.protocol", "PLAINTEXT"));
    }

    @Test
    @DisplayName("프로토콜이 없으면 PLAINTEXT 로 본다 — 설정 누락으로 기동이 죽지 않게")
    void missingProtocolDefaultsToPlaintext() {
        assertThat(KafkaSecuritySettings.from(new MockEnvironment()))
                .containsEntry("security.protocol", "PLAINTEXT");
    }

    @Test
    @DisplayName("빈 스토어 경로는 SSL 에서도 넣지 않는다 — 관리형 브로커의 공개 CA 를 쓰기 위해")
    void blankStorePathsAreOmittedUnderSsl() {
        // 예전 코드는 이 상황에서 ssl.truststore.location="" 를 그대로 넣었고,
        // Kafka 클라이언트가 빈 경로를 열려다 NoSuchFileException 으로 죽었다.
        MockEnvironment env = new MockEnvironment()
                .withProperty("kafka.security.protocol", "SASL_SSL")
                .withProperty("kafka.ssl.trust-store-location", "   ")
                .withProperty("kafka.ssl.key-store-location", "")
                .withProperty("kafka.sasl.username", "API_KEY")
                .withProperty("kafka.sasl.password", "API_SECRET");

        Map<String, Object> props = KafkaSecuritySettings.from(env);

        assertThat(props).doesNotContainKeys(
                "ssl.truststore.location", "ssl.truststore.type", "ssl.truststore.password",
                "ssl.keystore.location", "ssl.keystore.type", "ssl.keystore.password");
    }

    @Test
    @DisplayName("사설 CA mTLS: 경로가 있으면 형식까지 함께 넣고 file: 접두어는 떼어 낸다")
    void mutualTlsCarriesPathsAndTypes() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kafka.security.protocol", "SSL")
                .withProperty("kafka.ssl.trust-store-location", "file:/app/resources/kafka-truststore.jks")
                .withProperty("kafka.ssl.trust-store-password", "tspw")
                .withProperty("kafka.ssl.trust-store-type", "JKS")
                .withProperty("kafka.ssl.key-store-location", "file:///app/resources/kafka-keystore.p12")
                .withProperty("kafka.ssl.key-store-password", "kspw")
                .withProperty("kafka.ssl.key-store-type", "PKCS12");

        Map<String, Object> props = KafkaSecuritySettings.from(env);

        assertThat(props)
                .containsEntry("security.protocol", "SSL")
                .containsEntry("ssl.truststore.location", "/app/resources/kafka-truststore.jks")
                .containsEntry("ssl.truststore.password", "tspw")
                .containsEntry("ssl.truststore.type", "JKS")
                // file:// 뒤에 남은 /app/... 이 그대로여야 한다(슬래시 하나를 더 먹으면 경로가 깨진다).
                .containsEntry("ssl.keystore.location", "/app/resources/kafka-keystore.p12")
                .containsEntry("ssl.keystore.password", "kspw")
                .containsEntry("ssl.keystore.type", "PKCS12")
                // mTLS 에는 SASL 이 없다.
                .doesNotContainKeys("sasl.mechanism", "sasl.jaas.config");
    }

    @Test
    @DisplayName("SASL_SSL + 사용자/비밀번호 → PLAIN JAAS 문자열을 만든다")
    void saslPlainBuildsJaasFromCredentials() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kafka.security.protocol", "SASL_SSL")
                .withProperty("kafka.sasl.username", "API_KEY")
                .withProperty("kafka.sasl.password", "API_SECRET");

        assertThat(KafkaSecuritySettings.from(env))
                .containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "PLAIN")
                .containsEntry("sasl.jaas.config",
                        "org.apache.kafka.common.security.plain.PlainLoginModule required"
                                + " username=\"API_KEY\" password=\"API_SECRET\";");
    }

    @Test
    @DisplayName("SCRAM 은 로그인 모듈이 다르다")
    void scramUsesScramLoginModule() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kafka.security.protocol", "SASL_SSL")
                .withProperty("kafka.sasl.mechanism", "SCRAM-SHA-512")
                .withProperty("kafka.sasl.username", "u")
                .withProperty("kafka.sasl.password", "p");

        assertThat(KafkaSecuritySettings.from(env))
                .containsEntry("sasl.mechanism", "SCRAM-SHA-512")
                .hasEntrySatisfying("sasl.jaas.config",
                        v -> assertThat((String) v).startsWith(
                                "org.apache.kafka.common.security.scram.ScramLoginModule required"));
    }

    @Test
    @DisplayName("시크릿 안의 따옴표·백슬래시는 이스케이프한다")
    void jaasValuesAreEscaped() {
        // 관리형 서비스의 API 시크릿에는 실제로 이런 문자가 들어간다. 그대로 두면 JAAS 파서가
        // 문자열을 엉뚱한 곳에서 끊어 'Unable to find login module' 이라는 무관한 오류를 낸다.
        MockEnvironment env = new MockEnvironment()
                .withProperty("kafka.security.protocol", "SASL_SSL")
                .withProperty("kafka.sasl.username", "u")
                .withProperty("kafka.sasl.password", "a\"b\\c");

        assertThat(KafkaSecuritySettings.from(env))
                .containsEntry("sasl.jaas.config",
                        "org.apache.kafka.common.security.plain.PlainLoginModule required"
                                + " username=\"u\" password=\"a\\\"b\\\\c\";");
    }

    @Test
    @DisplayName("JAAS 를 통째로 주면 그대로 쓴다")
    void explicitJaasWins() {
        String jaas = "org.apache.kafka.common.security.plain.PlainLoginModule required"
                + " username='k' password='s';";
        MockEnvironment env = new MockEnvironment()
                .withProperty("kafka.security.protocol", "SASL_SSL")
                .withProperty("kafka.sasl.jaas-config", jaas)
                .withProperty("kafka.sasl.username", "ignored");

        assertThat(KafkaSecuritySettings.from(env)).containsEntry("sasl.jaas.config", jaas);
    }

    @Test
    @DisplayName("SASL 인데 자격증명이 없으면 기동 시점에 죽는다")
    void saslWithoutCredentialsFailsFast() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kafka.security.protocol", "SASL_SSL");

        assertThatThrownBy(() -> KafkaSecuritySettings.from(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KAFKA_SASL_USERNAME");
    }
}
