package com.project.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.env.Environment;

/**
 * Kafka 클라이언트의 <b>보안 프로퍼티</b>를 한 곳에서 만든다.
 *
 * <p>왜 만들었나. 24개의 Kafka 설정 클래스가 아래 여섯 줄을 그대로 복제하고 있었다:
 *
 * <pre>
 *   .put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG,   checkPath(keyStoreLocation))
 *   .put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG,   keyStorePassword)
 *   .put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG,       keyStoreType)
 *   .put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, checkPath(trustStoreLocation))
 *   ...
 * </pre>
 *
 * 복제 자체보다 나쁜 것은 <b>값이 비어 있어도 무조건 넣는다</b>는 점이었다. 로컬 기본값은
 * {@code KAFKA_SSL_KEY_STORE_LOCATION:} — 즉 빈 문자열이다. PLAINTEXT 브로커에서는
 * Kafka 클라이언트가 SSL 설정을 아예 읽지 않으므로 아무 일도 일어나지 않았고, 그래서
 * 이 조합이 안전한 것처럼 보였다. 그런데 프로토콜을 SSL/SASL_SSL 로 바꾸는 순간
 * {@code ssl.truststore.location=""} 가 실제로 해석되어
 * {@code NoSuchFileException: } (빈 경로) 로 클라이언트 생성이 실패한다.
 *
 * <p>관리형 Kafka(Confluent Cloud, Redpanda, Aiven 등)는 <b>공개 CA 인증서 + SASL</b> 조합을
 * 쓴다. 트러스트스토어를 지정하면 안 되고(JVM 기본 신뢰 저장소를 써야 한다), 클라이언트
 * 키스토어는 아예 없다. 그래서 규칙은 하나다 — <b>비어 있으면 넣지 않는다.</b>
 *
 * <p>키 이름을 {@code SslConfigs}/{@code SaslConfigs} 상수가 아니라 문자열로 쓴 이유:
 * common 모듈은 Kafka 를 쓰지 않는 모듈(eureka-server, config-server 등)도 함께 의존한다.
 * 상수 하나 때문에 kafka-clients 를 그쪽 런타임까지 끌고 갈 이유가 없다. 대신 이름이
 * 실제 상수와 일치하는지는 {@code KafkaSecuritySettingsTest} 가 kafka-clients 를
 * <b>테스트 클래스패스에서만</b> 가져와 검증한다.
 */
public final class KafkaSecuritySettings {

    private KafkaSecuritySettings() {
    }

    // ── Kafka 클라이언트 설정 키 (org.apache.kafka.common.config.SslConfigs 등과 동일) ──
    static final String SECURITY_PROTOCOL = "security.protocol";
    static final String SSL_TRUSTSTORE_LOCATION = "ssl.truststore.location";
    static final String SSL_TRUSTSTORE_PASSWORD = "ssl.truststore.password";
    static final String SSL_TRUSTSTORE_TYPE = "ssl.truststore.type";
    static final String SSL_KEYSTORE_LOCATION = "ssl.keystore.location";
    static final String SSL_KEYSTORE_PASSWORD = "ssl.keystore.password";
    static final String SSL_KEYSTORE_TYPE = "ssl.keystore.type";
    static final String SASL_MECHANISM = "sasl.mechanism";
    static final String SASL_JAAS_CONFIG = "sasl.jaas.config";

    private static final String PLAIN_LOGIN_MODULE =
            "org.apache.kafka.common.security.plain.PlainLoginModule";
    private static final String SCRAM_LOGIN_MODULE =
            "org.apache.kafka.common.security.scram.ScramLoginModule";

    /**
     * {@code kafka.security.protocol} / {@code kafka.ssl.*} / {@code kafka.sasl.*} 를 읽어
     * 프로듀서·컨슈머·AdminClient 에 그대로 {@code putAll} 할 수 있는 맵을 만든다.
     *
     * <p>프로토콜에 실제로 필요한 키만 담는다. PLAINTEXT 면 {@code security.protocol} 하나뿐이다.
     */
    public static Map<String, Object> from(Environment env) {
        String protocol = blankToNull(env.getProperty("kafka.security.protocol"));
        if (protocol == null) {
            protocol = "PLAINTEXT";
        }
        protocol = protocol.trim().toUpperCase();

        Map<String, Object> props = new LinkedHashMap<>();
        props.put(SECURITY_PROTOCOL, protocol);

        if (protocol.endsWith("SSL")) {
            putSsl(props, env);
        }
        if (protocol.startsWith("SASL_")) {
            putSasl(props, env, protocol);
        }
        return props;
    }

    // ------------------------------------------------------------------ SSL

    private static void putSsl(Map<String, Object> props, Environment env) {
        // 경로가 없으면 **아무것도 넣지 않는다** → JVM 기본 트러스트스토어(공개 CA)를 쓴다.
        // 관리형 Kafka 는 이 경로가 정답이고, 사설 CA 로 띄운 자체 브로커만 경로를 채운다.
        String trustStore = stripFileScheme(blankToNull(env.getProperty("kafka.ssl.trust-store-location")));
        if (trustStore != null) {
            props.put(SSL_TRUSTSTORE_LOCATION, trustStore);
            props.put(SSL_TRUSTSTORE_TYPE, env.getProperty("kafka.ssl.trust-store-type", "JKS"));
            putIfPresent(props, SSL_TRUSTSTORE_PASSWORD, env.getProperty("kafka.ssl.trust-store-password"));
        }

        // 클라이언트 키스토어는 mTLS 에서만 쓴다. SASL 인증에는 존재하지 않는다.
        String keyStore = stripFileScheme(blankToNull(env.getProperty("kafka.ssl.key-store-location")));
        if (keyStore != null) {
            props.put(SSL_KEYSTORE_LOCATION, keyStore);
            props.put(SSL_KEYSTORE_TYPE, env.getProperty("kafka.ssl.key-store-type", "PKCS12"));
            putIfPresent(props, SSL_KEYSTORE_PASSWORD, env.getProperty("kafka.ssl.key-store-password"));
        }
    }

    // ----------------------------------------------------------------- SASL

    private static void putSasl(Map<String, Object> props, Environment env, String protocol) {
        String mechanism = blankToNull(env.getProperty("kafka.sasl.mechanism"));
        if (mechanism == null) {
            mechanism = "PLAIN";
        }
        mechanism = mechanism.trim().toUpperCase();
        props.put(SASL_MECHANISM, mechanism);

        // JAAS 를 통째로 준 경우 그대로 쓴다(관리형 서비스 콘솔이 이 문자열을 그대로 준다).
        String jaas = blankToNull(env.getProperty("kafka.sasl.jaas-config"));
        if (jaas != null) {
            props.put(SASL_JAAS_CONFIG, jaas);
            return;
        }

        String username = blankToNull(env.getProperty("kafka.sasl.username"));
        String password = env.getProperty("kafka.sasl.password", "");
        if (username == null) {
            // 여기서 죽는 편이 낫다. 이 값이 없으면 브로커가 돌려주는 것은
            // 'Authentication failed' 뿐이고, 원인이 설정 누락이라는 단서가 어디에도 없다.
            throw new IllegalStateException(
                    "kafka.security.protocol=" + protocol + " 인데 SASL 자격증명이 없습니다. "
                            + "KAFKA_SASL_JAAS_CONFIG 를 통째로 주거나 "
                            + "KAFKA_SASL_USERNAME/KAFKA_SASL_PASSWORD 를 채우세요.");
        }

        String loginModule = mechanism.startsWith("SCRAM-") ? SCRAM_LOGIN_MODULE : PLAIN_LOGIN_MODULE;
        props.put(SASL_JAAS_CONFIG, loginModule + " required"
                + " username=\"" + escapeJaas(username) + "\""
                + " password=\"" + escapeJaas(password) + "\";");
    }

    /**
     * JAAS 설정은 큰따옴표로 감싼 문자열이다. 값 안의 {@code "} 나 {@code \} 를 그대로 두면
     * 파서가 문자열을 엉뚱한 곳에서 끊어 'Unable to find login module' 같은 무관한 오류가 난다.
     * 관리형 서비스의 API 시크릿에는 실제로 이런 문자가 들어간다.
     */
    private static String escapeJaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------------------------------------------------------- 도우미

    /**
     * {@code file:} / {@code file://} 접두어를 떼어 낸다. Kafka 클라이언트는 URL 이 아니라
     * <b>파일 시스템 경로</b>를 기대하는데, 설정에는 Spring 습관대로 file: 이 붙어 오곤 한다.
     */
    private static String stripFileScheme(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("file://")) {
            return blankToNull(path.substring("file://".length()));
        }
        if (path.startsWith("file:")) {
            return blankToNull(path.substring("file:".length()));
        }
        return path;
    }

    private static void putIfPresent(Map<String, Object> props, String key, String value) {
        String trimmed = blankToNull(value);
        if (trimmed != null) {
            props.put(key, trimmed);
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
