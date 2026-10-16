package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 설정 파일에 대한 가드레일 테스트.
 *
 * <p>이 저장소에서 가장 비싼 버그들은 코드가 아니라 <b>설정</b>에 있었고, 하나같이
 * "컴파일도 되고 단위 테스트도 통과하지만 실행하면 죽는" 종류였다(docs/14, docs/16).
 * 리뷰로는 거의 걸러지지 않으므로 빌드가 대신 지키게 한다.
 *
 * <p>각 테스트는 실제로 발생했던 장애 하나씩에 대응한다.
 */
class ConfigurationGuardrailTest {

    /** common 모듈 기준으로 저장소 루트를 찾는다(Gradle 테스트의 작업 디렉터리는 모듈 디렉터리). */
    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static List<Path> serviceApplicationYmls() throws IOException {
        Path root = repoRoot();
        List<Path> result = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path resources = module.resolve("src/main/resources");
                if (!Files.isDirectory(resources)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(resources)) {
                    files.filter(p -> p.getFileName().toString().matches("application-.*\\.ya?ml"))
                            .forEach(result::add);
                }
            }
        }
        return result;
    }

    @Test
    @DisplayName("B-1: 프로필 전용 파일은 spring.profiles.active 를 선언하면 안 된다")
    void profileSpecificFilesMustNotActivateProfiles() throws IOException {
        // Spring Boot 2.4+ 는 application-<profile>.yml 안의 spring.profiles.active 를 금지하고
        // InvalidConfigDataPropertyException 으로 **기동을 즉시 중단**한다. 9개 서비스가 이 상태였다.
        List<String> offenders = new ArrayList<>();
        for (Path yml : serviceApplicationYmls()) {
            for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                if (stripped.startsWith("#")) {
                    continue;
                }
                if (stripped.startsWith("active:") || stripped.matches("^profiles:\\s*$")) {
                    // 'active:' 는 profiles 블록 아래에서만 의미가 있다. 주석이 아닌 선언만 잡는다.
                    if (stripped.startsWith("active:")) {
                        offenders.add(repoRoot().relativize(yml) + " → " + stripped);
                    }
                }
            }
        }
        assertThat(offenders)
                .as("프로필 전용 파일에서 프로필을 다시 활성화하면 기동이 중단된다(docs/14 B-1)")
                .isEmpty();
    }

    @Test
    @DisplayName("B-22: 공유 설정은 spring.application.name 을 선언하면 안 된다")
    void sharedConfigMustNotDeclareApplicationName() throws IOException {
        // application-common.yml 은 모든 서비스가 import 한다. 여기에 이름을 두면 각 서비스가
        // 선언한 이름을 덮어써, 모든 서비스가 스스로를 'common' 이라고 보고하게 된다.
        // 그 결과 분산 추적(Jaeger)이 한 덩어리로 뭉개지고 Eureka 등록까지 위험해진다.
        Path shared = repoRoot().resolve("common/src/main/resources/application-common.yml");
        assertThat(shared).exists();

        String content = Files.readString(shared, StandardCharsets.UTF_8);
        List<String> active = content.lines()
                .map(String::strip)
                .filter(l -> !l.startsWith("#"))
                .filter(l -> l.startsWith("name:"))
                .toList();

        assertThat(active)
                .as("공유 설정 파일이 애플리케이션 이름을 정하면 안 된다(docs/16 B-22)")
                .isEmpty();
    }

    @Test
    @DisplayName("B-26: es-settings.json 은 settings 본문이어야 한다 (중복 래퍼 금지)")
    void elasticsearchSettingsMustNotBeDoubleWrapped() throws IOException {
        // @Setting(settingPath=...) 은 settings **본문**을 기대한다. 최상위에 "settings" 로 한 번
        // 더 감싸면 ES 가 index.settings.analysis... 라는 설정을 받고
        // unknown setting 오류로 **인덱스 생성 자체가 실패**한다.
        Path settings = repoRoot().resolve("common/src/main/resources/es/es-settings.json");
        assertThat(settings).exists();

        JsonNode root = new ObjectMapper().readTree(Files.readString(settings, StandardCharsets.UTF_8));

        assertThat(root.has("settings"))
                .as("중복된 최상위 'settings' 래퍼가 있으면 인덱스 생성이 실패한다(docs/16 B-26)")
                .isFalse();
        assertThat(root.has("analysis"))
                .as("애널라이저 정의는 최상위에 있어야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("es-settings.json 이 선언한 애널라이저는 문서가 참조하는 이름과 일치해야 한다")
    void declaredAnalyzersMustCoverReferencedOnes() throws IOException {
        Path settings = repoRoot().resolve("common/src/main/resources/es/es-settings.json");
        JsonNode analyzers = new ObjectMapper()
                .readTree(Files.readString(settings, StandardCharsets.UTF_8))
                .path("analysis").path("analyzer");

        // ProductDocument 등이 @Field(analyzer = ...) 로 참조하는 이름들.
        assertThat(analyzers.has("korean_analyzer")).isTrue();
        assertThat(analyzers.has("japanese_analyzer")).isTrue();

        // 플러그인이 주는 토크나이저(nori · kuromoji)는 기본 ES 이미지에 없다 — 클러스터의 ES 가 설치해야 한다.
        // 없으면 재색인이 색인 생성 단계에서 "Unknown tokenizer type" 으로 실패하는데, 단위 테스트는 모두 초록이다(docs/19 §5.2).
        // [JA] プラグインのトークナイザ(nori · kuromoji)は既定の ES イメージにない — クラスタの ES がインストールする必要がある。
        JsonNode tokenizers = new ObjectMapper()
                .readTree(Files.readString(settings, StandardCharsets.UTF_8))
                .path("analysis").path("tokenizer");
        String infra = Files.readString(repoRoot().resolve("k8s/90-dev-infra.yaml"), StandardCharsets.UTF_8);
        java.util.Map<String, String> pluginOf = java.util.Map.of(
                "nori_tokenizer", "analysis-nori", "kuromoji_tokenizer", "analysis-kuromoji");
        List<String> missing = new ArrayList<>();
        tokenizers.forEach(t -> {
            String plugin = pluginOf.get(t.path("type").asText());
            if (plugin != null && !infra.contains(plugin)) {
                missing.add(t.path("type").asText() + " → " + plugin);
            }
        });
        assertThat(missing).as("k8s/90-dev-infra.yaml 의 Elasticsearch 가 이 플러그인을 설치하지 않는다").isEmpty();
        assertThat(infra).as("플러그인 설치는 initContainer 에서 elasticsearch-plugin 으로")
                .contains("elasticsearch-plugin install");
    }

    @Test
    @DisplayName("B-12: PostgreSQL 데이터소스에 MySQL dialect 를 쓰면 안 된다")
    void postgresDatasourceMustNotUseMySqlDialect() throws IOException {
        // chat-service 가 PostgreSQL URL 에 MySQLDialect 를 쓰고 있었다. Hibernate 가 MySQL DDL
        // ("engine=", "auto_increment")을 만들어 보내 **모든 테이블 생성이 실패**했고, 그 실패는
        // WARN 으로만 남아 테이블 없이 서비스가 떠 있는 상태가 됐다.
        List<String> offenders = new ArrayList<>();
        for (Path yml : serviceApplicationYmls()) {
            String content = Files.readString(yml, StandardCharsets.UTF_8);
            boolean usesPostgres = content.contains("jdbc:postgresql");
            boolean declaresMySql = content.lines()
                    .map(String::strip)
                    .filter(l -> !l.startsWith("#"))
                    .anyMatch(l -> l.contains("MySQLDialect"));
            if (usesPostgres && declaresMySql) {
                offenders.add(repoRoot().relativize(yml).toString());
            }
        }
        assertThat(offenders)
                .as("데이터소스와 dialect 가 어긋나면 스키마가 생성되지 않는다(docs/14 B-12)")
                .isEmpty();
    }

    @Test
    @DisplayName("B-10: yml 이 참조하는 환경변수 이름에 오타(STOE)가 없어야 한다")
    void environmentVariableNamesMustNotContainKnownTypos() throws IOException {
        // ${LOCAL_KEY_STOE_PASSWORD} — 'STORE' 의 R 이 빠져 있었다. @Value 가 기본값 없이 읽어
        // 'Could not resolve placeholder' 로 chat-service 가 아예 뜨지 않았다.
        List<String> offenders = new ArrayList<>();
        for (Path yml : serviceApplicationYmls()) {
            // 주석은 제외한다 — 수정 이력을 설명하느라 옛 이름을 인용하는 경우가 있다.
            boolean hasTypo = Files.readAllLines(yml, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(l -> !l.startsWith("#"))
                    .anyMatch(l -> l.contains("STOE"));
            if (hasTypo) {
                offenders.add(repoRoot().relativize(yml).toString());
            }
        }
        assertThat(offenders).as("환경변수 이름 오타는 기동 불가로 이어진다(docs/14 B-10)").isEmpty();
    }

    @Test
    @DisplayName("B-28: 설정에 개발자 PC 의 절대 경로가 남아 있으면 안 된다")
    void configMustNotHardcodeDeveloperMachinePaths() throws IOException {
        // 7개 서비스가 Kafka 트러스트스토어를 file:E:/github/... 로 가리키고 있었다.
        // 그 경로는 원저자의 윈도우 PC 에만 존재한다 — 다른 어떤 머신에서도, 컨테이너에서도
        // 존재하지 않는 파일을 가리킨 채 protocol: "SSL" 이 켜져 있었다.
        // 인증서 파일은 ops/07-issue-certificates.sh 가 만들고 환경변수로 주입한다(docs/18).
        List<String> offenders = new ArrayList<>();
        for (Path yml : serviceApplicationYmls()) {
            for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                if (stripped.startsWith("#")) {
                    continue; // 옛 경로를 인용하는 설명 주석은 허용한다
                }
                // 윈도우 드라이브 문자(E:/...) 또는 홈 디렉터리 절대 경로
                if (stripped.matches(".*\\b[A-Za-z]:[/\\\\].*") || stripped.contains("/home/")
                        || stripped.contains("/Users/")) {
                    offenders.add(repoRoot().relativize(yml) + " → " + stripped);
                }
            }
        }
        assertThat(offenders)
                .as("설정은 특정 머신의 경로가 아니라 환경변수로 파일 위치를 받아야 한다(docs/18 §1)")
                .isEmpty();
    }

    @Test
    @DisplayName("B-29: Kafka SSL 스토어 형식은 하드코딩하지 않고 주입받아야 한다")
    void kafkaStoreTypesMustBeInjected() throws IOException {
        // 19개 Kafka 설정 클래스가 ssl.keystore.type 을 "JKS" 로 박아 놓고, 정작 스토어 경로는
        // .p12(PKCS12) 파일을 가리켰다. SSL 을 실제로 켜는 순간 'Invalid keystore format' 으로
        // 죽는 조합인데, 로컬 브로커가 PLAINTEXT 라 아무도 밟아 보지 못했다.
        List<String> offenders = new ArrayList<>();
        Path root = repoRoot();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path java : paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .toList()) {
                String content = Files.readString(java, StandardCharsets.UTF_8);
                if (!content.contains("SSL_KEYSTORE_TYPE_CONFIG")
                        && !content.contains("SSL_TRUSTSTORE_TYPE_CONFIG")) {
                    continue;
                }
                // 줄바꿈이 낀 경우도 있어 개행을 접어서 본다.
                String folded = content.replaceAll("\\s+", " ");
                if (folded.contains("SSL_KEYSTORE_TYPE_CONFIG, \"")
                        || folded.contains("SSL_TRUSTSTORE_TYPE_CONFIG, \"")) {
                    offenders.add(root.relativize(java).toString());
                }
            }
        }
        assertThat(offenders)
                .as("스토어 형식이 파일 확장자와 어긋나면 mTLS 를 켜는 순간 기동이 죽는다(docs/18 §2)")
                .isEmpty();
    }

    @Test
    @DisplayName("B-36: 셸 스크립트에 CR 이 섞이면 안 된다 — 셔뱅이 'bash\\r' 가 된다")
    void shellScriptsMustNotContainCarriageReturns() throws IOException {
        // Windows 의 git 은 core.autocrlf=true 가 기본이라 체크아웃에서 LF 를 CRLF 로 바꾼다.
        // 그러면 `#!/usr/bin/env bash` 가 `bash\r` 를 찾게 되고, 실행 자체가 안 된다:
        //
        //     /usr/bin/env: ‘bash\r’: No such file or directory
        //
        // 증상이 스크립트 내용과 무관해서 원인을 찾기 어렵고, 한 파일을 고쳐도 그것이 호출하는
        // 다음 스크립트에서 똑같이 막힌다(ops/13 → ops/07 에서 실측). .gitattributes 가 이걸
        // 막지만, 규칙이 지워지거나 파일이 CRLF 인 채로 커밋되면 다시 돌아온다.
        List<String> offenders = new ArrayList<>();
        Path root = repoRoot();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path script : paths
                    .filter(p -> p.getFileName().toString().endsWith(".sh"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .filter(p -> !p.toString().contains("/node_modules/"))
                    .filter(p -> !p.toString().contains("/.git/"))
                    .toList()) {
                if (Files.readString(script, StandardCharsets.UTF_8).indexOf('\r') >= 0) {
                    offenders.add(root.relativize(script).toString());
                }
            }
        }
        assertThat(offenders)
                .as("CRLF 스크립트는 실행조차 되지 않는다. 고치려면: sed -i 's/\\r$//' <파일>")
                .isEmpty();

        // 규칙 자체가 사라지면 다음 Windows 체크아웃에서 전부 되돌아간다.
        assertThat(Files.readString(root.resolve(".gitattributes"), StandardCharsets.UTF_8))
                .as(".gitattributes 가 셸 스크립트의 줄바꿈을 LF 로 고정해야 한다")
                .contains("*.sh")
                .contains("eol=lf");
    }

    @Test
    @DisplayName("B-35: Kafka 보안 프로퍼티는 서비스마다 복제하지 않고 공용 헬퍼가 만든다")
    void kafkaSecurityPropertiesComeFromOneHelper() throws IOException {
        // 24개 Kafka 설정 클래스가 SSL 여섯 줄을 그대로 복제하고 있었다. 복제 자체보다 나쁜 것은
        // **값이 비어 있어도 무조건 넣는다**는 점이었다 — PLAINTEXT 에서는 무해하지만 프로토콜을
        // SSL/SASL_SSL 로 바꾸는 순간 ssl.truststore.location="" 을 열려다 죽는다(docs/32 §4).
        // 이제 KafkaSecuritySettings 만 이 키들을 만든다. 다시 흩어지면 여기서 막는다.
        List<String> offenders = new ArrayList<>();
        Path root = repoRoot();
        Path helper = root.resolve("common/src/main/java/com/project/common/config/KafkaSecuritySettings.java");
        assertThat(helper).as("공용 헬퍼가 있어야 한다").exists();

        try (Stream<Path> paths = Files.walk(root)) {
            for (Path java : paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.equals(helper))
                    .toList()) {
                String content = Files.readString(java, StandardCharsets.UTF_8);
                if (content.contains("SslConfigs.") || content.contains("SaslConfigs.")) {
                    offenders.add(root.relativize(java).toString());
                }
            }
        }
        assertThat(offenders)
                .as("Kafka 보안 키는 KafkaSecuritySettings 한 곳에서만 만든다")
                .isEmpty();
    }

    @Test
    @DisplayName("B-30: Redis 비밀번호는 빈 값으로 조용히 기본화되면 안 된다")
    void redisPasswordMustFallBackToSharedSecret() throws IOException {
        // gateway·order·cart 세 서비스가 Redis 비밀번호를 ${REDIS_PASSWORD:} (빈 기본값) 로
        // 두거나 아예 읽지 않았다. 비밀번호 없는 개발 Redis 에서는 전혀 드러나지 않다가,
        // requirepass 가 걸린 Redis(운영 표준) 앞에서 해당 서비스만 WRONGPASS 로 기동이 죽거나
        // 레이트리미터가 조용히 무력화된다 — 셋 다 스테이징 리허설(ops/12)에서 실측으로 잡혔다.
        // 규칙: REDIS_PASSWORD 를 읽되, 다른 서비스들처럼 LOCAL_DB_PASSWORD 로 폴백해야 한다.
        List<String> offenders = new ArrayList<>();
        for (Path yml : serviceApplicationYmls()) {
            for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                if (stripped.startsWith("#")) {
                    continue;
                }
                if (stripped.matches("password:\\s*\\$\\{REDIS_PASSWORD:\\}")) {
                    offenders.add(repoRoot().relativize(yml) + " → " + stripped);
                }
            }
        }
        assertThat(offenders)
                .as("빈 기본값 Redis 비밀번호는 requirepass 앞에서만 터지는 지뢰다(ops/12 리허설)")
                .isEmpty();
    }

    @Test
    @DisplayName("B-37: Feign 타임아웃은 spring.cloud.openfeign 접두사여야 한다 — feign.client.config 는 읽히지 않는다")
    void feignTimeoutsMustUseTheOpenFeign4Prefix() throws IOException {
        // 모든 yml 이 `feign.client.config.default.readTimeout: 5000` 을 갖고 있었고, 문서(docs/02,
        // docs/03)도 "readTimeout 5s 로 무한 대기 차단"이라고 적고 있었다. 그런데 그 키는
        // Spring Cloud 2022.0(OpenFeign 4.x)부터 `spring.cloud.openfeign.*` 로 바뀌어 **아무도
        // 읽지 않았다.** 폴백도 경고도 없다. 실제 적용값은 Feign 기본값인 연결 10초 / 읽기 60초였다
        // (docs/37 §2.1 — FeignClientProperties 상수 풀과 feign-core Request$Options 바이트코드로 확인).
        //
        // 설정을 바꿨다고 믿었는데 안 읽히고 있던 것 — 이 부류의 결함은 리뷰로는 절대 안 잡힌다.
        // 옛 접두사가 어디든 다시 나타나면 여기서 막는다. 값 자체의 바인딩은 cart-service 의
        // FeignTimeoutBindingTest 가 실제 yml 을 읽어 검증한다.
        List<String> offenders = new ArrayList<>();
        List<Path> ymls = new ArrayList<>(serviceApplicationYmls());
        ymls.add(repoRoot().resolve("common/src/main/resources/application-common.yml"));

        for (Path yml : ymls) {
            List<String> lines = Files.readAllLines(yml, StandardCharsets.UTF_8);
            // 최상위 `feign:` 아래에 `client:` 가 있으면 옛 접두사다. (`feign.*-url` 은 이 저장소의
            // 자체 키라 최상위 feign: 자체는 허용한다 — 문제는 그 아래의 client.config 다.)
            boolean underTopLevelFeign = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.strip().startsWith("#")) {
                    continue;
                }
                if (line.matches("^feign:\\s*$")) {
                    underTopLevelFeign = true;
                    continue;
                }
                if (!line.isBlank() && !line.startsWith(" ")) {
                    underTopLevelFeign = false;   // 다른 최상위 키로 넘어감
                }
                if (underTopLevelFeign && line.matches("^\\s+client:\\s*$")) {
                    offenders.add(repoRoot().relativize(yml) + ":" + (i + 1));
                }
            }
        }
        assertThat(offenders)
                .as("feign.client.config.* 는 OpenFeign 4.x 가 읽지 않는다 — spring.cloud.openfeign.client.config.* 로")
                .isEmpty();

        // 그리고 새 접두사가 공유 설정에 **실제로** 있어야 한다. 옛 것을 지우기만 하고 새 것을
        // 안 넣으면 타임아웃이 다시 60초로 돌아간다 — 이 테스트가 통과하면서 그 상태가 되면 안 된다.
        Path shared = repoRoot().resolve("common/src/main/resources/application-common.yml");
        String common = Files.readString(shared, StandardCharsets.UTF_8);
        assertThat(common)
                .as("공유 설정에 spring.cloud.openfeign.client.config.default 가 있어야 한다")
                .containsPattern("(?m)^\\s+openfeign:\\s*$")
                .containsPattern("(?m)^\\s+readTimeout:\\s*\\d+");
    }

    @Test
    @DisplayName("Kafka SSL 프로퍼티는 공유 설정 한 곳에서만 정의되어야 한다")
    void kafkaSslPropertiesMustComeFromSharedConfig() throws IOException {
        // 각 서비스가 kafka.ssl.* 을 다시 선언하면, 환경변수 주입이 어느 파일에 걸리는지
        // 추적이 불가능해진다. 실제로 그렇게 갈라져 있던 탓에 KAFKA_SSL_* 주입이 죽어 있었다.
        Path shared = repoRoot().resolve("common/src/main/resources/application-common.yml");
        String sharedContent = Files.readString(shared, StandardCharsets.UTF_8);
        // 자바 @Value 가 읽는 네 개의 위치/비밀번호 키와 두 개의 형식 키가 모두 있어야
        // 어떤 서비스에서도 'Could not resolve placeholder' 가 나지 않는다.
        assertThat(sharedContent)
                .contains("trust-store-location:")
                .contains("trust-store-password:")
                .contains("key-store-location:")
                .contains("key-store-password:")
                .contains("key-store-type:")
                .contains("trust-store-type:");

        List<String> offenders = new ArrayList<>();
        for (Path yml : serviceApplicationYmls()) {
            if (yml.equals(shared)) {
                continue;
            }
            boolean redeclares = Files.readAllLines(yml, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(l -> !l.startsWith("#"))
                    .anyMatch(l -> l.startsWith("trust-store-location:") || l.startsWith("key-store-location:"));
            if (redeclares) {
                offenders.add(repoRoot().relativize(yml).toString());
            }
        }
        assertThat(offenders)
                .as("Kafka 스토어 경로는 application-common.yml 만 정의한다(docs/18 §1)")
                .isEmpty();
    }

    @Test
    @DisplayName("B-31: 서비스가 요구하는 환경변수를 k8s 매니페스트가 전부 제공해야 한다")
    void everyRequiredEnvVarIsProvidedByManifests() throws IOException {
        // ${VAR} 에 기본값이 없으면 Spring 은 'Could not resolve placeholder' 로 **기동을 거부**한다.
        // 실제로 AMAZON_ACCESS_KEY 4종이 어느 매니페스트에도 없어, S3 를 쓰는 두 서비스
        // (user·catalog)만 k3s 배포에서 CrashLoop 였다 — 나머지 7개는 멀쩡해서 원인이
        // 서비스별 문제로 보였다(docs/19). 배포 전에 빌드가 잡는다.
        Path root = repoRoot();
        Path k8s = root.resolve("k8s");
        if (!Files.isDirectory(k8s)) {
            return;   // 매니페스트가 없는 체크아웃에서는 검사할 것이 없다
        }

        Set<String> provided = new HashSet<>();
        try (Stream<Path> manifests = Files.list(k8s)) {
            for (Path m : manifests.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                String text = Files.readString(m, StandardCharsets.UTF_8);
                // 매니페스트가 변수를 선언하는 두 가지 형태를 모두 읽는다:
                //   ConfigMap/Secret 의 키   →  "  VAR: value"
                //   Deployment 의 env 항목   →  "- name: VAR"
                for (Pattern p : List.of(Pattern.compile("^\\s*([A-Z][A-Z0-9_]{2,}):", Pattern.MULTILINE),
                                         Pattern.compile("name:\\s*([A-Z][A-Z0-9_]{2,})"))) {
                    Matcher matcher = p.matcher(text);
                    while (matcher.find()) {
                        provided.add(matcher.group(1));
                    }
                }
            }
        }

        List<String> offenders = new ArrayList<>();
        for (Path yml : serviceApplicationYmls()) {
            if (!yml.getFileName().toString().equals("application-local.yml")) {
                continue;
            }
            // 기본값이 없는 ${VAR} 만 — ${VAR:기본값} 은 없어도 뜬다.
            Matcher required = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]+)}")
                    .matcher(Files.readString(yml, StandardCharsets.UTF_8));
            while (required.find()) {
                String var = required.group(1);
                if (!provided.contains(var)) {
                    offenders.add(root.relativize(yml) + " → " + var);
                }
            }
        }

        assertThat(offenders)
                .as("매니페스트가 주지 않는 필수 환경변수는 배포 즉시 CrashLoop 이 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("B-32: 커넥션 풀 총합이 PostgreSQL max_connections 를 넘지 않아야 한다")
    void connectionPoolTotalMustFitWithinServerLimit() throws IOException {
        // 풀 크기는 서비스 하나만 보면 항상 합리적으로 보인다. 문제는 **총합**이다:
        // 데이터소스 수 × 풀 크기 × 레플리카 수가 서버의 max_connections 를 넘는 순간
        // 늦게 뜬 파드가 'FATAL: sorry, too many clients already' 로 기동조차 못 한다.
        // 기본값(10)을 그대로 두면 8 × 10 × 3 = 240 으로 기본 한도 100 을 두 배 넘게 초과했다.
        int datasources = 0;
        List<String> unsized = new ArrayList<>();
        for (Path yml : serviceApplicationYmls()) {
            if (!yml.getFileName().toString().equals("application-local.yml")) {
                continue;
            }
            String content = Files.readString(yml, StandardCharsets.UTF_8);
            long count = content.lines()
                    .map(String::strip)
                    .filter(l -> l.startsWith("url:") && l.contains("jdbc:postgresql"))
                    .count();
            if (count == 0) {
                continue;
            }
            datasources += (int) count;
            long pools = content.lines().map(String::strip)
                    .filter(l -> l.startsWith("maximum-pool-size:")).count();
            if (pools < count) {
                unsized.add(repoRoot().relativize(yml)
                        + " (데이터소스 " + count + "개 중 풀 명시 " + pools + "개)");
            }
        }

        assertThat(unsized)
                .as("풀 크기를 명시하지 않으면 HikariCP 기본값 10 이 적용된다 — 총합 계산이 불가능해진다")
                .isEmpty();

        // 선언된 기본 풀 크기를 읽어 총합을 계산한다.
        int poolSize = Integer.parseInt(
                Pattern.compile("maximum-pool-size:\\s*\\$\\{DB_POOL_SIZE:(\\d+)}")
                        .matcher(Files.readString(
                                repoRoot().resolve("order-service/src/main/resources/application-local.yml"),
                                StandardCharsets.UTF_8))
                        .results().findFirst().orElseThrow().group(1));

        int maxReplicas = 3;   // k8s/70-autoscaling.yaml
        int serverLimit = 200; // docs/19 §3 이 요구하는 max_connections (기본 100 에서 상향)
        int total = datasources * poolSize * maxReplicas;

        assertThat(total)
                .as("데이터소스 %d × 풀 %d × 레플리카 %d = %d 커넥션. "
                        + "서버 max_connections(%d)를 넘으면 파드가 기동 실패한다(docs/19 §3).",
                        datasources, poolSize, maxReplicas, total, serverLimit)
                .isLessThanOrEqualTo(serverLimit);
    }

    @Test
    @DisplayName("B-33: 스키마는 Flyway 가 소유한다 — ddl-auto 로 DDL 을 내면 안 된다")
    void hibernateMustNotOwnTheSchema() throws IOException {
        // `ddl-auto: update` 는 세 가지가 동시에 문제였다(docs/20):
        //   · 스키마 변경이 이력에 남지 않는다
        //   · 운영 DB 에 리뷰도 롤백 계획도 없는 DDL 이 나간다
        //   · 제약을 **지우지 않는다** — 잘못 걸린 UNIQUE 가 영원히 남았고, 그래서
        //     엔티티를 고친 뒤에도 V4 마이그레이션을 따로 써야 했다
        // 되돌아오기 쉬운 설정이라(한 줄이면 된다) 빌드가 막는다.
        Pattern ddlAuto = Pattern.compile("ddl-auto:\\s*(\\S+)");
        List<String> offenders = new ArrayList<>();
        Path root = repoRoot();

        for (Path yml : serviceApplicationYmls()) {
            for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                if (stripped.startsWith("#")) {
                    continue;
                }
                Matcher m = ddlAuto.matcher(stripped);
                if (!m.find()) {
                    continue;
                }
                // ${DDL_AUTO:validate} 처럼 주입 가능한 형태도 **기본값**으로 판정한다 —
                // 운영에서 환경변수로 덮어쓰는 것은 의도적 행위이고, 위험한 것은 커밋된 기본값이다.
                String value = m.group(1);
                Matcher def = Pattern.compile("\\$\\{[^:}]+:([^}]*)}").matcher(value);
                String effective = def.matches() ? def.group(1) : value;
                if (!effective.equals("validate") && !effective.equals("none")) {
                    offenders.add(root.relativize(yml) + " → ddl-auto: " + value);
                }
            }
        }

        assertThat(offenders)
                .as("스키마는 Flyway 의 V*__baseline_schema.sql 이 소유한다. "
                        + "엔티티를 바꿨다면 새 마이그레이션을 쓰고, 베이스라인은 "
                        + "`-PexportSchema` + ops/tools/hbm2flyway.py 로 다시 뽑는다(docs/20)")
                .isEmpty();

        // 마이그레이션이 실제로 존재하는지도 본다 — validate 만 켜고 베이스라인이 없으면
        // 빈 DB 에서 'Schema-validation: missing table' 로 기동조차 못 한다.
        List<String> missingBaseline = new ArrayList<>();
        for (Path yml : serviceApplicationYmls()) {
            if (!yml.getFileName().toString().equals("application-local.yml")) {
                continue;
            }
            String content = Files.readString(yml, StandardCharsets.UTF_8);
            if (!content.contains("jdbc:postgresql")) {
                continue;
            }
            Path migrations = yml.getParent().resolve("db/migration");
            boolean hasBaseline = false;
            if (Files.isDirectory(migrations)) {
                try (Stream<Path> files = Files.list(migrations)) {
                    hasBaseline = files.anyMatch(
                            p -> p.getFileName().toString().contains("baseline_schema"));
                }
            }
            if (!hasBaseline) {
                missingBaseline.add(root.relativize(yml).toString());
            }
        }

        assertThat(missingBaseline)
                .as("ddl-auto 를 끈 서비스는 스키마를 만들어 줄 베이스라인 마이그레이션이 있어야 한다")
                .isEmpty();
    }

    /** 파드에 상태를 두기 때문에 수평 확장하면 <b>기능이 깨지는</b> 서비스. docs/21 참조. */
    private static final Map<String, String> SINGLETON_SERVICES = Map.of(
            "eureka-server",
            "standalone 설정(register-with-eureka=false)이라 2개면 서로 모르는 레지스트리 두 개가 된다",
            "chat-service",
            "STOMP 브로커가 인메모리(enableSimpleBroker)라 다른 파드가 소비한 메시지를 구독자가 못 받는다");

    @Test
    @DisplayName("B-34: 수평 확장이 불가능한 서비스는 레플리카 1 + HPA 대상 제외여야 한다")
    void singletonServicesMustNotBeScaled() throws IOException {
        // 숫자를 1에서 2로 바꾸는 건 한 줄이고, 리뷰에서 "왜 여기만 1이지?" 하며 통과되기 쉽다.
        // 그런데 이 둘은 늘리는 순간 **조용히** 깨진다 — 파드는 정상이고 로그도 깨끗한데
        // 사용자가 채팅을 못 받거나, 디스커버리가 파드마다 다르게 보인다. 빌드가 막는다.
        Path k8s = repoRoot().resolve("k8s");
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.list(k8s)) {
            for (Path manifest : files.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                String content = Files.readString(manifest, StandardCharsets.UTF_8);
                for (Map.Entry<String, String> entry : SINGLETON_SERVICES.entrySet()) {
                    String service = entry.getKey();

                    if (manifest.getFileName().toString().contains(service)) {
                        // 주석이 아닌 replicas 선언만 본다.
                        content.lines().map(String::strip)
                                .filter(l -> !l.startsWith("#") && l.startsWith("replicas:"))
                                .forEach(l -> {
                                    String value = l.substring("replicas:".length()).strip();
                                    if (!value.equals("1")) {
                                        offenders.add(service + ": replicas " + value + " — " + entry.getValue());
                                    }
                                });
                    }

                    // HPA 는 파일 이름과 무관하게 어디에나 있을 수 있다.
                    if (content.contains("HorizontalPodAutoscaler")
                            && content.contains("kind: Deployment, name: " + service)) {
                        offenders.add(service + ": HPA 대상 — " + entry.getValue());
                    }
                }
            }
        }

        assertThat(offenders)
                .as("이 서비스들을 늘리려면 먼저 상태를 파드 밖으로 빼야 한다 "
                        + "(브로커 릴레이 / peer-aware Eureka). 그 전까지는 늘리면 조용히 깨진다")
                .isEmpty();
    }

    /** 베이스 매니페스트가 배포하는 서비스 = {@code image: REGISTRY_PLACEHOLDER/ecommerce-msa/<이름>}. */
    private static Set<String> baseServiceImages() throws IOException {
        Path k8s = repoRoot().resolve("k8s");
        Set<String> names = new HashSet<>();
        try (Stream<Path> files = Files.list(k8s)) {
            for (Path m : files.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                Matcher matcher = Pattern.compile("image:\\s*REGISTRY_PLACEHOLDER/ecommerce-msa/([a-z0-9-]+)")
                        .matcher(Files.readString(m, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        return names;
    }

    @Test
    @DisplayName("B-39: 새 서비스를 더하면 오버레이 5개가 전부 따라와야 한다 — 빠지면 ImagePullBackOff 이거나 자원 예산이 조용히 깨진다")
    void everyBaseServiceIsCoveredByOverlays() throws IOException {
        // 서비스를 하나 더하는 일은 파일 한 개(k8s/4x-*.yaml)로 끝나지 않는다. 오버레이마다
        // **이름을 손으로 열거한** 자리가 있고, 빠뜨렸을 때의 증상이 자리마다 다르다:
        //
        //   images: 누락        → REGISTRY_PLACEHOLDER 가 그대로 남아 ImagePullBackOff.
        //                        ops/28 은 배포 전에 레지스트리를 확인하다 죽는다(그나마 시끄럽다).
        //   자원 패치 누락      → **조용하다.** 베이스의 250m/384Mi 가 그대로 적용되고, 2 OCPU
        //                        박스에서는 그 한 개 때문에 다른 파드가 Pending 이 된다.
        //                        "왜 스케줄이 안 되지"로 보이지 saga-projection 문제로 보이지 않는다.
        //
        // 실제로 saga-projection 을 더할 때 다섯 자리 중 네 자리를 빠뜨렸다(docs/38 §1.17).
        Set<String> services = baseServiceImages();
        assertThat(services).as("베이스가 배포하는 서비스").contains("gateway-server", "order-service");

        List<String> offenders = new ArrayList<>();
        Path overlays = repoRoot().resolve("k8s-overlays");

        try (Stream<Path> dirs = Files.list(overlays)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path kust = dir.resolve("kustomization.yaml");
                if (!Files.exists(kust)) {
                    continue;
                }
                String body = Files.readString(kust, StandardCharsets.UTF_8);
                String overlay = dir.getFileName().toString();

                // ① images: 를 선언하는 오버레이는 **전부** 열거해야 한다. 상속만 하는
                //    오버레이(external·oci)는 images 블록이 없으므로 검사 대상이 아니다.
                if (body.contains("\nimages:")) {
                    Matcher declared = Pattern.compile("^\\s*-\\s*name:\\s*\\S*ecommerce-msa/([a-z0-9-]+)\\s*$",
                            Pattern.MULTILINE).matcher(body);
                    Set<String> named = new HashSet<>();
                    while (declared.find()) {
                        named.add(declared.group(1));
                    }
                    for (String svc : services) {
                        if (!named.contains(svc)) {
                            offenders.add("k8s-overlays/" + overlay + " images: 에 " + svc + " 없음 → ImagePullBackOff");
                        }
                    }
                }

                // ② 자원을 깎는 오버레이(external·oci)는 frontend 를 뺀 자바 서비스 전부를
                //    정규식에 담아야 한다. frontend 는 nginx 라 따로 다룬다.
                if (overlay.equals("external") || overlay.equals("oci")) {
                    for (String svc : services) {
                        if (!svc.equals("frontend") && !body.contains(svc)) {
                            offenders.add("k8s-overlays/" + overlay + " 자원 패치 대상에 " + svc
                                    + " 없음 → 베이스 요청량(250m)이 그대로 적용된다");
                        }
                    }
                }
            }
        }

        assertThat(offenders)
                .as("서비스를 더할 때 오버레이를 같이 고치지 않으면, 증상이 그 서비스와 무관한 곳에서 나타난다")
                .isEmpty();
    }

    @Test
    @DisplayName("B-41: ops/02 의 DLT 목록이 모든 @KafkaListener 토픽을 덮어야 한다")
    void dltTopicListCoversEveryListener() throws IOException {
        // DLT 가 없는 토픽은 **평소에 아무 증상이 없다.** DeadLetterPublishingRecoverer 는 처리
        // 실패가 났을 때만 동작하기 때문이다. 그러다 정말 poison message 가 하나 들어오면 없는
        // 토픽에 쓰려다 실패하고 그 파티션이 막힌다 — DLQ 를 도입한 이유였던 바로 그 장애다
        // (docs/03 F-2). outgoing-mail·user-presence 가 실제로 그렇게 빠져 있었다.
        Path script = repoRoot().resolve("ops/02-create-dlt-topics.sh");
        assertThat(script).exists();

        Matcher decl = Pattern.compile("^TOPICS=\\(([^)]*)\\)", Pattern.MULTILINE)
                .matcher(Files.readString(script, StandardCharsets.UTF_8));
        assertThat(decl.find()).as("ops/02 의 TOPICS 선언").isTrue();
        Set<String> provisioned = new HashSet<>(List.of(decl.group(1).trim().split("\\s+")));

        Set<String> listened = new HashSet<>();
        try (Stream<Path> paths = Files.walk(repoRoot())) {
            for (Path p : paths.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/build/"))
                    .toList()) {
                listened.addAll(listenerTopics(Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        assertThat(listened).as("리스너가 있는 토픽").contains("order", "payment");

        List<String> missing = listened.stream().filter(t -> !provisioned.contains(t)).sorted().toList();
        assertThat(missing)
                .as("이 토픽들은 처리 실패 시 <topic>.DLT 로 격리되는데 그 토픽을 아무도 만들지 않는다")
                .isEmpty();
    }

    @Test
    @DisplayName("B-42: 리스너 컨테이너 팩토리는 전부 DLT 에러 핸들러를 달아야 한다")
    void everyListenerContainerFactorySetsTheErrorHandler() throws IOException {
        // DLT 토픽을 만들어 두는 것만으로는 부족하다. 컨테이너 팩토리가 에러 핸들러를 달지 않으면
        // 그 토픽의 DLT 는 **만들어만 놓고 아무도 쓰지 않는다.** 실패한 레코드는 격리되지 않고
        // 무한 재시도로 파티션을 막는다.
        //
        // 특히 이름이 kafkaListenerContainerFactory 인 빈은 containerFactory 를 지정하지 않은
        // 리스너의 **기본값**이라 조용히 선택된다. coupon-service 가 정확히 그 상태였다.
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(repoRoot())) {
            for (Path p : paths.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equals("KafkaConsumerConfig.java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/build/"))
                    .toList()) {
                String rel = repoRoot().relativize(p).toString();
                // @Bean 단위로 자른다. 마지막 조각까지 포함해야 파일 끝의 팩토리를 놓치지 않는다.
                String[] beans = Files.readString(p, StandardCharsets.UTF_8).split("\n    @Bean");
                for (int i = 1; i < beans.length; i++) {
                    Matcher factory = Pattern.compile("ConcurrentKafkaListenerContainerFactory<[^>]*>\\s+(\\w+)\\s*\\(")
                            .matcher(beans[i]);
                    if (factory.find() && !beans[i].contains("setCommonErrorHandler")) {
                        offenders.add(rel + " → " + factory.group(1) + "() 에 setCommonErrorHandler 없음");
                    }
                }
            }
        }

        assertThat(offenders)
                .as("에러 핸들러가 없는 팩토리의 리스너는 실패 레코드를 DLT 로 보내지 못하고 파티션을 막는다")
                .isEmpty();
    }

    /** {@code @KafkaListener(... topics = "x" ...)} 의 토픽 리터럴. 주석 처리된 리스너는 세지 않는다. */
    private static Set<String> listenerTopics(String source) {
        String live = source.lines()
                .filter(l -> !l.strip().startsWith("//") && !l.strip().startsWith("*"))
                .reduce("", (a, b) -> a + b + "\n");

        Set<String> found = new HashSet<>();
        int from = 0;
        while (true) {
            int at = live.indexOf("@KafkaListener", from);
            if (at < 0) {
                break;
            }
            int open = live.indexOf('(', at);
            if (open < 0) {
                break;
            }
            // 괄호 균형을 맞춰 애노테이션 본문만 잘라낸다 — properties = {...} 같은 중첩이 있다.
            int depth = 0;
            int end = open;
            while (end < live.length()) {
                char c = live.charAt(end);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) break;
                end++;
            }
            String body = live.substring(open, Math.min(end + 1, live.length()));
            Matcher topics = Pattern.compile("topics\\s*=\\s*(\\{[^}]*\\}|\"[^\"]*\")").matcher(body);
            while (topics.find()) {
                Matcher literal = Pattern.compile("\"([a-z0-9-]+)\"").matcher(topics.group(1));
                while (literal.find()) {
                    found.add(literal.group(1));
                }
            }
            from = end + 1;
        }
        return found;
    }

    @Test
    @DisplayName("B-40: 클러스터 내 PostgreSQL 초기화가 만드는 DB 목록이 서비스의 JDBC URL 을 전부 덮어야 한다")
    void devInfraCreatesEveryDatabaseTheManifestsConnectTo() throws IOException {
        // 90-dev-infra 의 init 스크립트는 DB 이름을 **손으로 나열한다**. 서비스를 더하면서 여기를
        // 빠뜨리면 그 서비스만 `FATAL: database "notificationdb" does not exist` 로 죽고 나머지는
        // 멀쩡하다. 증상이 그 서비스 안에 있는 것처럼 보여서 엉뚱한 곳을 뒤지게 된다.
        // 게다가 init 스크립트는 **빈 PGDATA 에서 한 번만** 돌기 때문에, 고쳐도 파드를 다시
        // 만들지 않으면 반영되지 않는다 — 두 번 헷갈리는 종류다.
        Path infra = repoRoot().resolve("k8s/90-dev-infra.yaml");
        if (!Files.exists(infra)) {
            return;
        }

        Matcher loop = Pattern.compile("for db in ([a-z0-9 ]+); do")
                .matcher(Files.readString(infra, StandardCharsets.UTF_8));
        assertThat(loop.find()).as("90-dev-infra 의 DB 생성 루프").isTrue();
        Set<String> created = new HashSet<>(List.of(loop.group(1).trim().split("\\s+")));

        Set<String> required = new HashSet<>();
        try (Stream<Path> files = Files.list(repoRoot().resolve("k8s"))) {
            for (Path m : files.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                Matcher url = Pattern.compile("jdbc:postgresql://\\$\\(POSTGRES_HOST\\):\\$\\(POSTGRES_PORT\\)/([a-z0-9]+)")
                        .matcher(Files.readString(m, StandardCharsets.UTF_8));
                while (url.find()) {
                    required.add(url.group(1));
                }
            }
        }
        assertThat(required).as("매니페스트가 붙는 DB").contains("userdb", "orderdb");

        List<String> missing = required.stream().filter(db -> !created.contains(db)).sorted().toList();
        assertThat(missing)
                .as("90-dev-infra 의 init 스크립트가 만들지 않는 DB 에 붙는 서비스는 기동하지 못한다")
                .isEmpty();
    }

    @Test
    @DisplayName("B-38: 컴팩션이 필요한 토픽은 ops/02 가 만든다 — 이름이 앱 설정과 같아야 한다")
    void compactedStateTopicIsProvisionedUnderTheSameName() throws IOException {
        // saga-projection 의 출력 토픽은 **컴팩션이어야** 한다. Streams 는 싱크 토픽을 만들지 않으므로
        // 아무도 안 만들면 브로커가 delete 정책으로 자동 생성하고, 증상은 retention 이 지난 뒤에야
        // 나타난다 — 과거 주문의 상태가 조용히 사라진다(docs/38 §1.17).
        //
        // 그래서 ops/02 가 만드는데, 그 스크립트는 토픽 이름을 **자기 기본값으로** 들고 있다.
        // 앱의 이름을 바꾸고 스크립트를 안 고치면 둘 다 조용히 어긋난다: 앱은 자기가 자동 생성한
        // delete 토픽에 쓰고, 스크립트는 아무도 안 쓰는 토픽을 컴팩션으로 만든다. 둘 다 성공으로 보인다.
        Path yml = repoRoot().resolve("saga-projection/src/main/resources/application-local.yml");
        Path script = repoRoot().resolve("ops/02-create-dlt-topics.sh");
        if (!Files.exists(yml)) {
            return;   // 모듈이 없는 체크아웃에서는 검사할 것이 없다
        }
        assertThat(script).as("컴팩션 토픽을 만드는 스크립트").exists();

        // 'state-dir:' 이 아니라 'state:' 만 — topics 블록의 값이다. timeline 은 주문 진행 타임라인(docs/76).
        String ymlText = Files.readString(yml, StandardCharsets.UTF_8);
        String scriptText = Files.readString(script, StandardCharsets.UTF_8);
        Matcher defaults = Pattern.compile("STATE_TOPICS=\"\\$\\{STATE_TOPICS:-([^}]+)\\}\"").matcher(scriptText);
        assertThat(defaults.find()).as("ops/02 의 STATE_TOPICS 기본값").isTrue();
        List<String> provisioned = List.of(defaults.group(1).trim().split("\\s+"));

        for (String key : List.of("state", "timeline")) {
            Matcher m = Pattern.compile("^\\s*" + key + ":\\s*(\\S+)\\s*$", Pattern.MULTILINE).matcher(ymlText);
            assertThat(m.find()).as("saga-projection.topics." + key + " 선언").isTrue();
            String topic = m.group(1);
            assertThat(provisioned)
                    .as("ops/02 의 STATE_TOPICS 기본값에 앱의 saga-projection.topics.%s('%s')가 없다", key, topic)
                    .contains(topic);
        }
        assertThat(scriptText).as("상태 토픽은 컴팩션으로 만들어야 한다").contains("cleanup.policy=compact");
    }

    @Test
    @DisplayName("B-43: 배포 스크립트의 롤아웃 대기 시간은 매니페스트의 startupProbe 예산보다 길어야 한다")
    void rolloutTimeoutMustOutlastTheStartupBudget() throws IOException {
        // 둘이 **같으면** 정상 기동이 타임아웃과 경합해서 진다. 그러면 ops/28 은 실패로 판정해
        // 자동 복귀시키고, 구 ReplicaSet 파드가 다시 Running 이 되므로 운영자는 배포가 성공한
        // 것으로 오해한다 — 실패가 성공처럼 보이는 조합이라 특히 나쁘다.
        //
        // 실제로 겪었다: 기본값 300초 vs startupProbe 60×5초=300초. 마이그레이션 4개를
        // 한꺼번에 도는 첫 배포에서 user-service 가 이렇게 롤백됐다.
        //
        // 이 가드레일이 지키는 것은 "매니페스트의 프로브 예산을 늘릴 때 스크립트도 같이
        // 늘린다"는 규칙이다. 한쪽만 바꾸면 조용히 예전 상태로 돌아간다.
        Path script = repoRoot().resolve("ops/28-deploy.sh");
        assertThat(script).as("배포 스크립트").exists();

        Matcher t = Pattern.compile("ROLLOUT_TIMEOUT:-(\\d+)s\\}")
                .matcher(Files.readString(script, StandardCharsets.UTF_8));
        assertThat(t.find()).as("ops/28 의 ROLLOUT_TIMEOUT 기본값 선언").isTrue();
        int rolloutSeconds = Integer.parseInt(t.group(1));

        int worstBudget = 0;
        String worstFile = "";
        try (Stream<Path> manifests = Files.list(repoRoot().resolve("k8s"))) {
            for (Path m : manifests.filter(p -> p.getFileName().toString().endsWith(".yaml")).toList()) {
                String body = Files.readString(m, StandardCharsets.UTF_8);
                int at = body.indexOf("startupProbe:");
                if (at < 0) {
                    continue;
                }
                // startupProbe 블록 안의 값만 본다. 뒤따르는 liveness/readiness 의
                // periodSeconds 를 집으면 예산이 엉뚱해진다.
                String block = body.substring(at, Math.min(body.length(), at + 700));
                Matcher ft = Pattern.compile("failureThreshold:\\s*(\\d+)").matcher(block);
                Matcher ps = Pattern.compile("periodSeconds:\\s*(\\d+)").matcher(block);
                if (!ft.find() || !ps.find()) {
                    continue;
                }
                int budget = Integer.parseInt(ft.group(1)) * Integer.parseInt(ps.group(1));
                if (budget > worstBudget) {
                    worstBudget = budget;
                    worstFile = m.getFileName().toString();
                }
            }
        }

        assertThat(worstBudget).as("k8s 매니페스트에서 startupProbe 예산을 읽지 못했다").isPositive();
        assertThat(rolloutSeconds)
                .as("ops/28 의 ROLLOUT_TIMEOUT 기본값(%ds)이 %s 의 startupProbe 예산(%ds)보다 "
                        + "길지 않다 — 정상 기동이 타임아웃에 져서 배포가 조용히 롤백된다",
                        rolloutSeconds, worstFile, worstBudget)
                .isGreaterThan(worstBudget);
    }

    @Test
    @DisplayName("B-44: config-server 매니페스트가 주입하는 CONFIG_* 는 yml 의 플레이스홀더가 받아야 한다")
    void configServerEnvNamesMustMatchTheirPlaceholders() throws IOException {
        // config-server 의 설정값은 application-local.yml 에서 `${CONFIG_GIT_URI:기본값}`
        // 형태로 받는다. 매니페스트의 env 이름과 그 플레이스홀더 이름이 어긋나면 주입이
        // 통째로 무시되고 **기본값(REPLACE_ME)이 조용히 이긴다** — 파드는 정상으로 뜨고,
        // 클라이언트는 SPRING_CONFIG_IMPORT 가 optional: 이라 실패를 로그로만 남긴다.
        // 즉 어디에도 증상이 없다.
        //
        // 플레이스홀더 방식을 쓰는 이유는 yml 주석 참조(환경변수 이름 매핑이 하이픈을 지운다).
        Path manifest = repoRoot().resolve("k8s/32-config-server.yaml");
        Path yml = repoRoot().resolve("config-server/src/main/resources/application-local.yml");
        if (!Files.exists(yml)) {
            return;   // 모듈이 없는 체크아웃에서는 검사할 것이 없다
        }
        assertThat(manifest).as("config-server 매니페스트").exists();

        String ymlBody = Files.readString(yml, StandardCharsets.UTF_8);

        // 주석 처리된 예시(`# - name: ...`)는 첫 글자가 '-' 가 아니라 걸리지 않는다.
        Matcher env = Pattern.compile("^\\s*-\\s*name:\\s*(CONFIG_[A-Z0-9_]+)\\s*$", Pattern.MULTILINE)
                .matcher(Files.readString(manifest, StandardCharsets.UTF_8));

        List<String> injected = new ArrayList<>();
        while (env.find()) {
            injected.add(env.group(1));
        }
        assertThat(injected)
                .as("매니페스트에서 CONFIG_* env 를 하나도 찾지 못했다 — 정규식이 형식과 어긋났을 수 있다")
                .isNotEmpty();

        List<String> unreceived = injected.stream()
                .filter(name -> !ymlBody.contains("${" + name))
                .sorted()
                .toList();

        assertThat(unreceived)
                .as("매니페스트가 주입하지만 application-local.yml 이 `${이름:...}` 으로 받지 않는 값 "
                        + "— 주입이 무시되고 기본값이 이긴다")
                .isEmpty();
    }

    @Test
    @DisplayName("B-45: 게이트웨이 TimeLimiter 는 timeout-duration 을 명시해야 한다 (기본값 1초는 너무 짧다)")
    void gatewayTimeLimiterMustDeclareTimeoutDuration() throws IOException {
        // Resilience4j TimeLimiter 는 timeout-duration 을 적지 않으면 **1초**로 동작한다.
        // 그 1초는 JVM 서비스 호출에 턱없이 짧다 — Eureka 로 인스턴스를 고르고 TLS 를 맺고
        // JPA 쿼리까지 도는 시간이다.
        //
        // 실제 사고: /api/content/home 이 파드 직접 호출로는 0.3초에 200 인데 게이트웨이로는
        // 전부 503 이 났다. 게이트웨이 로그에만 원인이 남았다 —
        // "Did not observe any item or terminal signal within 1000ms in 'circuitBreaker'".
        //
        // 값을 지우거나 블록을 통째로 옮기면 **아무 오류 없이** 1초로 돌아간다. 그래서 검사한다.
        Path yml = repoRoot().resolve("gateway-server/src/main/resources/application-local.yml");
        if (!Files.exists(yml)) {
            return;   // 모듈이 없는 체크아웃에서는 검사할 것이 없다
        }

        String body = Files.readString(yml, StandardCharsets.UTF_8);

        // 주석(`# timeout-duration: ...`)은 세지 않는다 — 주석은 설정이 아니다.
        Matcher declared = Pattern.compile("^\\s*timeout-duration:\\s*\\S+", Pattern.MULTILINE)
                .matcher(body);

        assertThat(declared.find())
                .as("gateway-server 의 resilience4j.timelimiter 에 timeout-duration 이 없다 "
                        + "— 적지 않으면 기본값 1초가 조용히 적용되고, 정상 응답까지 503 이 된다")
                .isTrue();
    }

    @Test
    @DisplayName("B-46: 배포 프로필에 DEBUG·TRACE 로그 레벨을 박아 두지 않는다")
    void deployedProfilesMustNotHardcodeDebugLogging() throws IOException {
        // 예전에는 12개 모듈 **전부** 이 블록이 DEBUG 였다 — 우리 패키지는 물론
        // org.hibernate.SQL 까지. 운영에서 모든 SQL 이 한 줄씩 찍혔다는 뜻이다.
        //
        // 비용은 두 군데서 드러났다:
        //   · 기동이 느려진다. catalog-service 가 뜨는 데 195초가 걸렸고, 단일 노드에서
        //     startupProbe 예산(300초)을 위협했다. 실제로 롤아웃이 멈췄다.
        //   · 정작 장애 때 원인 줄을 찾기 어렵다. DEBUG 로 덮여 있어서.
        //
        // 조사할 때는 환경변수로 올린다(LOG_LEVEL_APP / LOG_LEVEL_SQL / ...).
        // 기본값이 운영값이어야 하고, 그 반대가 아니다.
        Pattern hardcoded = Pattern.compile(
                "^[ \\t]*(?:org|com|io|net)\\.[A-Za-z0-9_.]*:[ \\t]*(?:DEBUG|debug|TRACE|trace)[ \\t]*$",
                Pattern.MULTILINE);

        // ⚠️ application-local.yml 만 보지 않는다. 프로파일이 하나 늘어나는 순간 이 가드가
        //    조용히 눈이 멀기 때문이다. resources 바로 아래의 application*.yml 을 전부 본다.
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path resources = module.resolve("src/main/resources");
                if (!Files.isDirectory(resources)) {
                    continue;
                }
                try (Stream<Path> ymls = Files.list(resources)) {
                    for (Path yml : ymls.filter(f -> f.getFileName().toString().startsWith("application")
                            && f.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                        Matcher m = hardcoded.matcher(Files.readString(yml, StandardCharsets.UTF_8));
                        while (m.find()) {
                            offenders.add(repoRoot().relativize(yml) + " : " + m.group().trim());
                        }
                    }
                }
            }
        }

        assertThat(offenders)
                .as("배포되는 프로필에 DEBUG/TRACE 가 박혀 있다 — `${LOG_LEVEL_...:info}` 로 바꾸고 "
                        + "조사할 때만 환경변수로 올릴 것")
                .isEmpty();
    }

    @Test
    @DisplayName("B-47: 따옴표 없는 heredoc 안의 백틱은 이스케이프해야 한다 — 안내문이 명령이 된다")
    void unquotedHeredocsMustEscapeBackticks() throws IOException {
        // ops/19 의 "다음 단계" 안내문은 $TAG 를 치환하려고 `cat <<EOF` 로(따옴표 없이) 열려
        // 있었다. 그 안의 한 줄이 이랬다:
        //
        //     ⚠️ ... `kubectl apply -f k8s/` 는 더 이상 쓸 수 없다.
        //
        // 따옴표 없는 heredoc 에서 백틱은 **명령 치환**이다. 그래서 이 안내문은 빌드할 때마다
        // 실제로 kubectl apply -f k8s/ 를 실행했다 — HPA 를 되살리고, 오버레이 없이 base
        // 매니페스트를 적용해 방금 한 배포를 1.0.0 으로 되돌렸다. 저널에 다섯 번 연속 fail 이
        // 찍힌 사고의 뿌리다. 그리고 그 명령의 stdout 이 안내문 한가운데 끼어 나와서, 로그를
        // 읽는 사람은 "누가 이걸 쳤지?" 하고 사람을 의심했다. (docs/39 §7.6)
        //
        // 안내문에 명령을 적을 일은 계속 있다. 그러니 규칙은 하나다: 따옴표 없는 heredoc
        // 안에서 백틱은 \` 로 쓴다. ops/22 는 이미 그렇게 하고 있었다.
        Pattern open = Pattern.compile("<<-?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*$");
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> scripts = Files.list(repoRoot().resolve("ops"))) {
            for (Path script : scripts.filter(p -> p.toString().endsWith(".sh")).sorted().toList()) {
                List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
                String terminator = null;
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (terminator == null) {
                        // 따옴표로 연 heredoc(<<'EOF', <<"EOF")은 치환이 없으므로 대상이 아니다.
                        Matcher m = open.matcher(line);
                        if (m.find() && !line.contains("<<'") && !line.contains("<<\"")
                                && !line.contains("<<-'") && !line.contains("<<-\"")) {
                            terminator = m.group(1);
                        }
                        continue;
                    }
                    if (line.strip().equals(terminator)) {
                        terminator = null;
                        continue;
                    }
                    // 이스케이프되지 않은 백틱 = 백슬래시가 앞에 없는 백틱
                    if (Pattern.compile("(?<!\\\\)`").matcher(line).find()) {
                        offenders.add(repoRoot().relativize(script) + ":" + (i + 1) + "  " + line.strip());
                    }
                }
            }
        }

        assertThat(offenders)
                .as("따옴표 없는 heredoc 안에 이스케이프되지 않은 백틱이 있다 — 그 안은 명령 치환이다. "
                        + "\\` 로 쓰거나 heredoc 을 <<'EOF' 로 열 것")
                .isEmpty();
    }

    @Test
    @DisplayName("B-48: 게이트웨이가 TLS 를 종단하면 스모크 대상도 https 여야 한다")
    void smokeTargetSchemeMustMatchGatewayTls() throws IOException {
        // gateway-server 는 스스로 TLS 를 종단한다(server.ssl.key-store, 포트 8000).
        // 그런데 ops/28 의 포트포워드 경로는 `http://localhost:18000` 을 스모크 대상으로
        // 삼고 있었다. 톰캣은 평문 요청에 400 "This combination of host and port requires
        // TLS" 를 돌려주므로 **스모크의 모든 항목이 실패**했다 — 무엇을 배포하든 게이트가
        // 통과할 수 없었고, 매번 자동 복귀가 돌았다. 저널에 ok 가 한 줄도 없던 이유다.
        //
        // 스킴이 어긋나도 오류 메시지는 "게이트웨이가 UP 이 아니다"로 나온다. 즉 증상이
        // 원인을 가린다. 그래서 검사한다.
        Path gatewayYml = repoRoot().resolve("gateway-server/src/main/resources/application-local.yml");
        Path deploy = repoRoot().resolve("ops/28-deploy.sh");
        if (!Files.exists(gatewayYml) || !Files.exists(deploy)) {
            return;   // 모듈이 없는 체크아웃에서는 검사할 것이 없다
        }

        String gatewayBody = Files.readString(gatewayYml, StandardCharsets.UTF_8);
        boolean gatewayTerminatesTls = Pattern
                .compile("^\\s*key-store:\\s*\\S+", Pattern.MULTILINE)
                .matcher(gatewayBody).find();
        if (!gatewayTerminatesTls) {
            return;   // 평문 게이트웨이라면 http 대상이 맞다
        }

        // 주석은 제외한다 — 주석 안의 http:// 는 설명이지 설정이 아니다.
        List<String> offenders = Files.readAllLines(deploy, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.strip().startsWith("#"))
                .filter(line -> line.contains("SMOKE_TARGET=") && line.contains("http://"))
                .map(String::strip)
                .toList();

        assertThat(offenders)
                .as("게이트웨이가 TLS 를 종단하는데 스모크 대상이 http:// 다 — 모든 요청이 400 으로 "
                        + "튕기고 스모크 게이트가 영원히 실패한다")
                .isEmpty();
    }

    @Test
    @DisplayName("B-49: 프론트가 부르는 경로는 게이트웨이 라우트에 걸려야 한다")
    void frontendPathsMustBeRoutableByGateway() throws IOException {
        // 실제 사고: 프론트는 `/user-service/api/sse` 를 부르는데 게이트웨이 술어는
        // `/user-service/sse` 였다. 어느 라우트에도 걸리지 않아 404 — 그리고 설령 걸렸어도
        // RewritePath 가 `/sse` 로 바꾸는데 컨트롤러는 `/api/sse` 라서 또 404 였다.
        //
        // 이런 어긋남은 **배포를 막지 않는다.** 증상이 "알림만 안 온다" 처럼 한 기능에만
        // 나타나고, 서버 로그에는 아무것도 남지 않는다(게이트웨이에서 끝나므로).
        // 그래서 사람이 그 화면을 열어 보기 전까지 아무도 모른다.
        Path yml = repoRoot().resolve("gateway-server/src/main/resources/application-local.yml");
        Path frontend = repoRoot().resolve("frontend/src");
        if (!Files.exists(yml) || !Files.exists(frontend)) {
            return;   // 모듈이 없는 체크아웃에서는 검사할 것이 없다
        }

        // 게이트웨이 술어 수집. Path= 는 `\` 로 여러 줄에 걸쳐 쓰이므로 먼저 펼친다.
        String body = Files.readString(yml, StandardCharsets.UTF_8)
                .replaceAll("\\\\\\s*\\n\\s*", " ");
        List<Pattern> routes = new ArrayList<>();
        Matcher route = Pattern.compile("^\\s*-\\s*Path=(.+)$", Pattern.MULTILINE).matcher(body);
        while (route.find()) {
            for (String raw : route.group(1).split(",")) {
                String pattern = raw.trim();
                if (pattern.startsWith("/")) {
                    routes.add(toRegex(pattern));
                }
            }
        }
        assertThat(routes).as("게이트웨이에서 Path= 술어를 하나도 찾지 못했다").isNotEmpty();

        // 프론트의 **정적** 경로만 본다. 경로 중간에 값이 끼어드는 것은 여기서 판정할 수 없다.
        //
        // ⚠️ 맨 앞의 `${API_BASE}` 같은 보간 하나는 허용해야 한다. 실제로 SSE 호출이
        //    `` `${API_BASE}/user-service/api/sse` `` 형태라, 이걸 제외하면 이 가드가 잡아야 할
        //    바로 그 버그를 놓친다(처음 작성했을 때 실제로 놓쳤다).
        Pattern literal = Pattern.compile(
                "[\"'`](?:\\$\\{[^}]*\\})?"
                        + "(/(?:(?:user|catalog|cart|order|pay|coupon|chat|notification|delivery)-service|saga-projection)"
                        + "/[A-Za-z0-9/_.\\-]*)[\"'`]");

        Map<String, String> unroutable = new TreeMap<>();
        try (Stream<Path> sources = Files.walk(frontend)) {
            for (Path source : sources.filter(Files::isRegularFile).toList()) {
                String name = source.getFileName().toString();
                if (!(name.endsWith(".js") || name.endsWith(".jsx")) || name.contains(".test.")) {
                    continue;
                }
                Matcher found = literal.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (found.find()) {
                    String path = found.group(1);
                    if (routes.stream().noneMatch(r -> r.matcher(path).matches())) {
                        unroutable.putIfAbsent(path, repoRoot().relativize(source).toString());
                    }
                }
            }
        }

        assertThat(unroutable)
                .as("프론트가 부르는데 게이트웨이가 라우팅하지 않는 경로 — 404 가 되고 서버 로그에는 "
                        + "아무것도 남지 않는다")
                .isEmpty();
    }

    @Test
    @DisplayName("B-50: 배포되는 프로파일이 바뀌면 위 가드들이 눈이 먼다 — 그 전제를 고정한다")
    void deployedProfileMustBeTheOneGuardsInspect() throws IOException {
        // B-45(게이트웨이 타임아웃) · B-48(스모크 스킴) · B-49(라우트)는 모두
        // `application-local.yml` 을 읽는다. 그게 **실제로 배포되는 프로파일**이라는 전제 위에
        // 서 있는데, 그 전제는 코드 어디에도 적혀 있지 않다.
        //
        // 누가 application-prod.yml 을 추가하거나 SPRING_PROFILES_ACTIVE 를 바꾸면 위 셋은
        // 여전히 초록불이면서 아무것도 지키지 않게 된다 — 가드가 있다는 사실이 오히려
        // 해롭다. 그래서 전제 자체를 검사한다.
        Path configmap = repoRoot().resolve("k8s/10-configmap.yaml");
        if (!Files.exists(configmap)) {
            return;
        }

        Matcher active = Pattern.compile("^\\s*SPRING_PROFILES_ACTIVE:\\s*\"?([A-Za-z0-9_,-]+)\"?",
                Pattern.MULTILINE).matcher(Files.readString(configmap, StandardCharsets.UTF_8));
        assertThat(active.find())
                .as("k8s/10-configmap.yaml 에서 SPRING_PROFILES_ACTIVE 를 찾지 못했다")
                .isTrue();

        assertThat(active.group(1))
                .as("배포 프로파일이 local 이 아니다 — B-45·B-48·B-49 가 읽는 application-local.yml 은 "
                        + "더 이상 배포되는 파일이 아니므로, 그 가드들의 대상 경로를 같이 옮겨야 한다")
                .isEqualTo("local");

        // 게이트웨이에 다른 프로파일 파일이 생겼다면 라우트가 그쪽에도 있을 수 있다.
        try (Stream<Path> ymls = Files.list(repoRoot().resolve("gateway-server/src/main/resources"))) {
            List<String> others = ymls.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("application-") && n.endsWith(".yml"))
                    .filter(n -> !n.equals("application-local.yml"))
                    .sorted()
                    .toList();
            assertThat(others)
                    .as("게이트웨이에 local 말고 다른 프로파일 파일이 생겼다 — 라우트·타임아웃이 "
                            + "그쪽에도 있을 수 있으니 B-45·B-48·B-49 가 함께 읽어야 한다")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("B-51: REACT_APP_* 는 매니페스트에 두면 안 된다 — 아무도 읽지 않는다")
    void reactBuildTimeVarsMustNotLiveInManifests() throws IOException {
        // CRA 는 `npm run build` 때 process.env.REACT_APP_* 를 **문자열 리터럴로 치환**해
        // 번들에 박는다. 프론트엔드 이미지는 nginx 가 정적 파일을 서빙할 뿐이라, 파드에
        // 환경변수를 넣어도 읽을 프로세스가 없다.
        //
        // 그래서 매니페스트에 있는 REACT_APP_* 는 **동작하지 않는 값**인데, 있으면 "채웠으니
        // 됐겠지" 하게 된다. 실제로 20-secret.example.yaml 에 REACT_APP_PAYPAL_CLIENT_ID 가
        // 있었고, 그걸 채워 배포한 뒤 결제 화면에서만 이렇게 터졌다:
        //
        //     SDK Validation error: 'Expected client-id to be passed'
        //
        // 값은 ops/19 에 넘긴다. 주석으로 설명하는 줄은 여기 걸리지 않는다(첫 글자가 #).
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> manifests = Files.list(repoRoot().resolve("k8s"))) {
            for (Path manifest : manifests.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.strip().startsWith("#")) {
                        continue;
                    }
                    if (Pattern.compile("REACT_APP_[A-Z0-9_]+").matcher(line).find()) {
                        offenders.add(repoRoot().relativize(manifest) + ":" + (i + 1) + "  " + line.strip());
                    }
                }
            }
        }

        assertThat(offenders)
                .as("매니페스트에 REACT_APP_* 가 있다 — 빌드 시점 값이라 런타임에는 아무도 읽지 않는다. "
                        + "ops/19-build-images.sh 에 넘길 것")
                .isEmpty();
    }

    @Test
    @DisplayName("B-52: 액세스 토큰을 로그에 찍으면 안 된다 — 로그를 읽는 사람이 곧 그 사용자가 된다")
    void bearerTokensMustNeverBeLogged() throws IOException {
        // NotificationController 에 이런 두 줄이 있었다:
        //
        //     log.info("Request Authorization Header: {}", accessToken);
        //     log.info("토큰에서 추출한 Session ID: {}", sessionId);   // 실은 토큰 전문이었다
        //
        // 둘 다 **유효한 액세스 토큰을 평문으로** 남긴다. 로그를 읽을 수 있는 사람은 누구나
        // 만료 전까지 그 사용자를 사칭할 수 있다. 게다가 SSE 는 접속마다 찍히고 토큰 갱신마다
        // 다시 찍히므로(TokenRefresher 가 만료 10초 전에 갱신한다) 금방 쌓인다. 로그 수집기로
        // 나가면 회수할 방법도 없다.
        //
        // 두 번째 줄은 이름까지 틀려서 더 위험했다 — "Session ID" 라고 적혀 있으니 리뷰에서
        // 아무도 토큰이라고 생각하지 않는다. 그래서 **이름이 아니라 값의 출처**로 막는다.
        //
        // 규칙: **자격증명이 담기는 이름**이 로그 인자로 넘어가면 실패다. 포맷 문자열에
        // 무엇이라 적혀 있는지는 보지 않는다 — 위 두 번째 줄이 보여 주듯 설명은 틀릴 수
        // 있고, 값의 출처는 틀리지 않는다.
        //
        // 안전한 형태는 통과시킨다:
        //   · `authorizationHeader != null ? "present" : "absent"` — 리터럴만 나간다
        //   · 주석 안의 예시 — 실행되지 않는다(이 주석 자체가 그렇다)
        Pattern logCall = Pattern.compile(
                "log\\.(?:trace|debug|info|warn|error)\\s*\\(([^;]*)\\)\\s*;", Pattern.DOTALL);
        Pattern credentialArg = Pattern.compile(
                "(?<![A-Za-z0-9_])(accessToken|refreshToken|authorizationHeader|authorization"
                        + "|authHeader|bearerToken|rawToken|jwtToken)(?![A-Za-z0-9_])");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(repoRoot())) {
            for (Path p : paths.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/build/"))
                    .sorted()
                    .toList()) {
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                Matcher call = logCall.matcher(src);
                while (call.find()) {
                    // ① 문자열 리터럴을 지운다 — 포맷 문자열은 검사 대상이 아니다.
                    String args = call.group(1).replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
                    // ② 양쪽 가지가 리터럴인 null 검사 삼항식은 값이 새지 않는다. 지운다.
                    args = args.replaceAll(
                            "[A-Za-z0-9_.]+\\s*[!=]=\\s*null\\s*\\?\\s*\"\"\\s*:\\s*\"\"", "\"\"");

                    if (credentialArg.matcher(args).find()) {
                        offenders.add(repoRoot().relativize(p) + ": " + oneLine(call.group()));
                    }
                }
            }
        }

        assertThat(offenders)
                .as("액세스 토큰(또는 Authorization 헤더 값)이 로그로 나간다 — 그 로그를 읽는 사람은 "
                        + "만료 전까지 해당 사용자로 행세할 수 있다. 찍어야 한다면 사용자 식별자나 "
                        + "세션 id 처럼 **재사용할 수 없는 값**을 찍을 것")
                .isEmpty();
    }

    @Test
    @DisplayName("B-53: 신원 헤더 제거를 default-filters 에 두면 안 된다 — JwtAuthorization 뒤에 실행된다")
    void identityHeaderScrubMustNotLiveInDefaultFilters() throws IOException {
        // 이 가드가 존재하는 이유는 **순서에 대한 잘못된 믿음** 하나다.
        //
        // default-filters 에 이렇게 적혀 있었다:
        //
        //     - RemoveRequestHeader=User-Id
        //     - RemoveRequestHeader=User-Email
        //     - RemoveRequestHeader=User-Role
        //
        // 옆 주석은 "default-filters 는 라우트 필터보다 먼저 실행되므로, 여기서 지운 뒤
        // JwtAuthorization 이 검증된 값을 다시 넣는 순서가 된다" 고 단언했다. 반대였다.
        // spring-cloud-gateway-server 4.2 의 RouteDefinitionRouteLocator 를 보면:
        //
        //   · loadGatewayFilters 는 각 필터를 **그 목록 안에서의 인덱스+1** 로 감싼다
        //     — new OrderedGatewayFilter(filter, i + 1)
        //   · getFilters 는 default-filters 를 먼저 담고(order 1,2,3,4,5),
        //     **라우트 필터를 다시 1부터** 담은 뒤 AnnotationAwareOrderComparator 로 정렬한다
        //
        // 그래서 JwtAuthorization(라우트 1번 → order 1)이 User-Id 를 넣은 직후
        // RemoveRequestHeader=User-Id(기본 2번 → order 2)가 그것을 지웠다.
        //
        // 이건 조용한 전면 장애였다. 검증된 신원이 하위 서비스에 **하나도** 도달하지 않아
        // JwtAuthorization 이 붙은 모든 라우트가 401 을 받았다. 그런데 게이트웨이 로그에는
        // 필터가 정상 호출된 것으로 찍히고, 라우트도 올바르게 매칭된 것으로 찍힌다 —
        // 어디를 봐도 정상으로 보인다. 관리자 화면에서는 배너 호출 하나가 401 을 두 번 받아
        // 앱 전체가 로그아웃됐고, 증상은 "관리자 페이지가 로그인 화면으로 튕긴다" 였다.
        //
        // 지우는 일 자체는 필요하다(외부에서 User-Role: ROLE_ADMIN 을 보내면 권한 상승이다).
        // 다만 **전역 필터**로 해야 한다 — GlobalFilter 는 자기 order 를 그대로 쓰므로
        // HIGHEST_PRECEDENCE 면 라우트 필터(order 1부터)보다 반드시 앞이고, 필터 개수나
        // 나열 순서에 기대지 않는다.
        Path gatewayYaml = repoRoot()
                .resolve("gateway-server/src/main/resources/application-local.yml");
        List<String> lines = Files.readAllLines(gatewayYaml, StandardCharsets.UTF_8);

        List<String> offenders = new ArrayList<>();
        boolean inDefaultFilters = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (stripped.startsWith("#")) {
                continue;
            }
            if (stripped.startsWith("default-filters:")) {
                inDefaultFilters = true;
                continue;
            }
            // 목록을 벗어나면(주석이 아닌, '-' 로 시작하지 않는 줄) 구간 종료.
            if (inDefaultFilters && !stripped.isEmpty() && !stripped.startsWith("-")) {
                inDefaultFilters = false;
            }
            if (inDefaultFilters
                    && Pattern.compile("RemoveRequestHeader\\s*=\\s*User-(Id|Email|Role)")
                            .matcher(stripped).find()) {
                offenders.add((i + 1) + ": " + stripped);
            }
        }

        assertThat(offenders)
                .as("default-filters 의 RemoveRequestHeader=User-* 는 JwtAuthorization **뒤에** "
                        + "실행되어 방금 검증된 신원을 지운다 — 하위 서비스가 전부 401 을 받는다. "
                        + "IdentityHeaderScrubFilter(GlobalFilter, HIGHEST_PRECEDENCE)가 대신 한다")
                .isEmpty();

        // 지우는 일이 **사라지는** 것도 막아야 한다. 위 줄만 지우고 전역 필터를 안 두면
        // 헤더 한 줄로 관리자를 사칭할 수 있게 된다 — 가드가 오히려 구멍을 만드는 셈이다.
        Path scrubber = repoRoot().resolve(
                "gateway-server/src/main/java/com/project/gatewayserver/filter/IdentityHeaderScrubFilter.java");
        assertThat(scrubber)
                .as("신원 헤더를 지우는 전역 필터가 없다 — 외부에서 User-Role: ROLE_ADMIN 을 "
                        + "보내면 하위 서비스가 그대로 믿는다(권한 상승)")
                .exists();

        String scrubberSrc = Files.readString(scrubber, StandardCharsets.UTF_8);
        assertThat(scrubberSrc)
                .as("전역 필터가 HIGHEST_PRECEDENCE 가 아니면 라우트 필터(order 1부터)보다 "
                        + "뒤로 갈 수 있고, 그러면 같은 사고가 그대로 재현된다")
                .contains("Ordered.HIGHEST_PRECEDENCE");
        for (String header : List.of("User-Id", "User-Email", "User-Role")) {
            assertThat(scrubberSrc)
                    .as("전역 필터가 " + header + " 를 지우지 않는다")
                    .contains(header);
        }
    }

    @Test
    @DisplayName("B-54: 되돌리기를 깨뜨리는 마이그레이션은 그렇다고 적어야 한다")
    void destructiveMigrationsMustDeclareThemselves() throws IOException {
        // `ops/28 --rollback` 은 **이미지만** 되돌린다. 스키마는 앞으로 간 채로 남는다.
        //
        // 그래서 어떤 배포가 컬럼을 지우거나 이름을 바꾸면, 되돌린 옛 이미지는 Hibernate
        // 의 `ddl-auto: validate` 에서 기동하지 못한다 — "Schema-validation: missing
        // column". **안전망이 있다고 믿는 순간에 없는 것이다.** 이 저장소는 그 부류의
        // 사고를 이미 겪었다(스모크 게이트가 http/https 때문에 영원히 실패해 모든 배포가
        // 자동 복귀하던 일, docs/39 §7).
        //
        // 그렇다고 지우는 것을 금지할 수는 없다. expand-contract 에서는 결국 지운다 —
        // 다만 **코드가 그것을 더 이상 안 읽는 버전이 배포되고 안정된 다음 릴리스**에.
        // 정적 검사는 릴리스를 모르므로 금지 대신 **선언**을 요구한다: 파일에
        //
        //     -- ROLLBACK-UNSAFE: <이유와 대비책>
        //
        // 한 줄을 적게 한다. 그러면 작성자가 한 번 멈춰 생각하게 되고, 나중에 되돌리기가
        // 막혔을 때 grep 한 번으로 원인을 찾을 수 있다.
        // ⚠️ **이미 적용된 마이그레이션 파일은 고칠 수 없다.** Flyway 는 파일 내용으로
        //    체크섬을 계산하므로 주석 한 줄만 더해도 다음 기동에서
        //    "Migration checksum mismatch" 로 그 서비스가 뜨지 못한다. 그래서 이 가드를
        //    쓰면서 기존 위반 넷에 설명을 달려다 그대로 장애를 만들 뻔했다.
        //
        //    그 넷은 **아웃박스 파티셔닝 전환**(V1)이고 이미 운영에 적용돼 있다. 파일을
        //    건드릴 수 없으므로 여기 예외로 적는다 — 예외의 이유는 아래 주석이 기록이다.
        //    새 마이그레이션은 파일에 표시를 달면 되므로 이 목록이 늘어날 일은 없어야 한다.
        Set<String> alreadyApplied = Set.of(
                // 평범한 테이블을 파티션 테이블로 바꾼다(기존 테이블 → _legacy, 새 파티션
                // 테이블이 그 이름을 물려받는다). 이름은 그대로라 옛 이미지의 validate 는
                // 통과하지만, 되돌려도 **데이터 배치**는 합쳐지지 않는다. 아웃박스는 발행이
                // 끝나면 버려도 되는 데이터라 이미지만 되돌리면 충분하다.
                "user-service/src/main/resources/db/migration/V1__partition_outbox_event.sql",
                "cart-service/src/main/resources/db/migration/V1__partition_outbox_event.sql",
                "order-service/src/main/resources/db/migration/V1__partition_payment_outbox_event.sql",
                "pay-service/src/main/resources/db/migration/V1__partition_order_outbox_event.sql");

        // `ALTER COLUMN … TYPE` 도 든다(order-service V7 에서 배웠다): integer → numeric 으로
        // 바꾸면 옛 이미지의 int 필드가 validate 에서 "wrong column type" 으로 죽는다.
        // 컬럼은 그대로 있는데 되돌리기가 막히는 경우라 눈에 덜 띈다.
        Pattern destructive = Pattern.compile(
                "(?i)^\\s*(alter\\s+table\\s+\\S+\\s+(drop\\s+column|drop\\s+constraint|rename)"
                        + "|drop\\s+(table|schema)\\b"
                        + "|alter\\s+table\\s+\\S+\\s+alter\\s+column\\s+\\S+\\s+(set\\s+not\\s+null|type\\b))");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(repoRoot())) {
            for (Path sql : paths.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".sql"))
                    .filter(f -> f.toString().contains("/db/migration/"))
                    .filter(f -> !f.toString().contains("/build/"))
                    .sorted()
                    .toList()) {
                if (alreadyApplied.contains(repoRoot().relativize(sql).toString())) {
                    continue;
                }
                String body = Files.readString(sql, StandardCharsets.UTF_8);
                if (body.contains("ROLLBACK-UNSAFE")) {
                    continue;   // 선언했다 — 통과
                }
                List<String> lines = body.lines().toList();
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    // SQL 주석은 검사하지 않는다. 마이그레이션 파일은 설명이 길고, 그 안에
                    // "나중에 DROP TABLE ... 로 정리" 같은 안내가 흔하다.
                    if (line.strip().startsWith("--")) {
                        continue;
                    }
                    // varchar 를 넓히기만 하는 것은 옛 이미지의 validate 가 통과한다(길이는 보지 않는다) — 되돌리기를 막을 이유가 없다.
                    // 다만 넓히기인지는 정적으로 알 수 없으니 파일에 `-- ROLLBACK-SAFE: <이유>` 로 선언하게 한다(user V14).
                    if (body.contains("ROLLBACK-SAFE:")
                            && line.matches("(?i)^\\s*alter\\s+table\\s+\\S+\\s+alter\\s+column\\s+\\S+\\s+type\\s+varchar\\s*\\(\\d+\\)\\s*;?\\s*$")) {
                        continue;
                    }
                    if (destructive.matcher(line).find()) {
                        offenders.add(repoRoot().relativize(sql) + ":" + (i + 1) + "  " + line.strip());
                        break;
                    }
                }
            }
        }

        assertThat(offenders)
                .as("되돌리기를 깨뜨릴 수 있는 문장이다. 이 배포를 되돌리면 옛 이미지가 "
                        + "Hibernate validate 에서 기동 실패한다. 꼭 필요하면 파일에 "
                        + "`-- ROLLBACK-UNSAFE: <이유와 대비책>` 한 줄을 적을 것 "
                        + "(ops/32-db-migration.sh 의 expand-contract 설명 참조)")
                .isEmpty();
    }

    @Test
    @DisplayName("B-75: 운영 배포 워크플로는 수동 실행(workflow_dispatch)으로만 시작된다 — 실행이 곧 승인")
    void deployWorkflowIsManualOnly() throws IOException {
        // 🐛 deploy-oci.yml 은 파이프라인이 초록이 되면(workflow_run) 자동으로 돌고, Environment `production` 의 Required
        //    reviewers 가 승인 게이트라고 적혀 있었다. 그러나 비공개 저장소는 Free · Pro · Team 요금제에서 Required reviewers 를
        //    쓸 수 없다 — `production` 은 첫 실행이 만든 빈 Environment 였고, 러너가 온라인이었다면 사전 점검 · 백업 없이
        //    되돌릴 수 없는 마이그레이션이 나갈 뻔했다(2026-10-05). 그래서 트리거를 수동 실행 하나로 묶는다.
        // [JA] 非公開リポジトリでは承認ゲートが無いため、デプロイは手動実行だけで始まるようにする。
        String wf = Files.readString(repoRoot().resolve(".github/workflows/deploy-oci.yml"), StandardCharsets.UTF_8);

        // 최상위 `on:` 블록의 트리거 이름(두 칸 들여쓴 키)만 모은다
        Set<String> triggers = new java.util.LinkedHashSet<>();
        boolean inOn = false;
        for (String line : wf.lines().toList()) {
            if (line.matches("^on:\\s*(#.*)?$")) {
                inOn = true;
                continue;
            }
            if (inOn && line.matches("^\\S.*")) {
                break;   // 다음 최상위 키
            }
            Matcher key = Pattern.compile("^  ([A-Za-z_]+):").matcher(line);
            if (inOn && key.find()) {
                triggers.add(key.group(1));
            }
        }

        assertThat(triggers)
                .as("deploy-oci.yml 의 트리거는 workflow_dispatch 하나여야 한다 — 자동 트리거(workflow_run · push · schedule …)를 "
                        + "더하면 비공개 저장소에서는 승인 없이 운영에 배포된다(docs/36 §6)")
                .containsExactly("workflow_dispatch");
    }

    /**
     * B-92: 컨슈머 그룹 id 를 두 모듈이 함께 쓰지 않는다 — {@code GROUP_ID_CONFIG} · {@code groupId} · 리스너 {@code id}(그룹이 된다).
     *
     * <p>같은 토픽을 같은 그룹으로 읽으면 두 서비스가 파티션을 나눠 가져 <b>서로의 메시지를 조용히 잃는다</b>. 토픽이 달라도 한쪽 배포가
     * 다른 쪽 컨슈머를 재조정한다. chat · user-service 에 쓰는 곳 없는 팩토리가 notification-service 의 그룹 {@code notification} 으로
     * 있었다(2026-10-16 삭제). {@code coupon} 은 이미 운영에 있는 두 그룹이라(cart {@code coupon-use} · coupon {@code coupon-rollback} —
     * 토픽이 달라 재조정만 엮인다) 남긴다 — 이름을 바꾸면 오프셋이 처음부터라 따로 배포한다.
     *
     * <p>[JA] コンシューマーグループ id を二つのモジュールで共有しない。同じトピックなら互いのメッセージを黙って失い、違っても片方の
     * デプロイがもう片方を再調整する。
     */
    @Test
    @DisplayName("B-92: 컨슈머 그룹 id 는 모듈마다 따로다(알려진 coupon 하나 빼고)")
    void consumerGroupsAreNotShared() throws IOException {
        Map<String, String> knownShared = Map.of(
                "coupon", "cart coupon-use · coupon coupon-rollback — 다른 토픽(재조정만), 이름 변경은 오프셋 이전과 함께");
        Pattern group = Pattern.compile("GROUP_ID_CONFIG\\s*,\\s*\"([^\"]+)\"|groupId\\s*=\\s*\"([^\"]+)\"");
        Pattern listenerId = Pattern.compile("@KafkaListener\\([^)]*?\\bid\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);
        Map<String, Set<String>> modulesByGroup = new TreeMap<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(m -> Files.isDirectory(m.resolve("src/main/java"))).toList()) {
                try (Stream<Path> files = Files.walk(module.resolve("src/main/java"))) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String src = stripJavaComments(Files.readString(f, StandardCharsets.UTF_8));
                        for (Pattern p : List.of(group, listenerId)) {
                            Matcher m = p.matcher(src);
                            while (m.find()) {
                                String g = m.group(1) != null ? m.group(1) : m.group(2);
                                modulesByGroup.computeIfAbsent(g, k -> new TreeSet<>()).add(module.getFileName().toString());
                            }
                        }
                    }
                }
            }
        }
        assertThat(modulesByGroup).as("그룹 id 를 하나도 못 찾았다면 이 검사가 낡은 것이다").hasSizeGreaterThan(10);
        List<String> shared = modulesByGroup.entrySet().stream()
                .filter(e -> e.getValue().size() > 1 && !knownShared.containsKey(e.getKey()))
                .map(e -> e.getKey() + " ← " + e.getValue()).toList();
        assertThat(shared).as("같은 그룹 id 를 두 모듈이 쓴다(B-92)").isEmpty();
    }

    /**
     * B-91: config-server 는 TLS 로 뜬다 — 공용 ConfigMap 이 클라이언트에게 {@code https://config-server…:8888} 로 오라고 한다.
     *
     * <p>config-server 의 yml 에는 {@code server.ssl} 블록이 없고 k8s/32 의 {@code SERVER_SSL_*} 환경 변수 넷이 TLS 를 만든다(relaxed
     * binding). 그 하나라도 빠지면 config-server 는 평문으로 떠 모든 클라이언트의 설정 가져오기가 핸드셰이크에서 실패하는데, 가져오기가
     * {@code optional:} 이라 로그 한 줄뿐 — 어디에도 증상이 없다. yml 에 블록을 두면 그쪽이 맞는다.
     *
     * <p>[JA] config-server は TLS で起動する — 共用 ConfigMap がクライアントに https で来るよう伝える。yml に server.ssl がなく、
     * k8s/32 の SERVER_SSL_* 四つが TLS を作る。一つでも抜けると平文で起動し、設定取得が optional: のため症状がどこにも出ない。
     */
    @Test
    @DisplayName("B-91: config-server 는 TLS 로 뜬다 — yml 의 server.ssl 이 없으면 k8s/32 에 SERVER_SSL_* 넷")
    void configServerServesTls() throws IOException {
        Path yml = repoRoot().resolve("config-server/src/main/resources/application-local.yml");
        if (!Files.exists(yml)) {
            return;   // 모듈이 없는 체크아웃
        }
        String configMap = Files.readString(repoRoot().resolve("k8s/10-configmap.yaml"), StandardCharsets.UTF_8);
        assertThat(configMap).as("클라이언트가 https 로 오지 않는다면 이 검사가 낡은 것이다")
                .containsPattern("SPRING_CONFIG_IMPORT: \"optional:configserver:https://config-server[.:]");

        Object ssl = null;
        for (String name : List.of("application.yml", "application-local.yml")) {
            Path file = repoRoot().resolve("config-server/src/main/resources/" + name);
            for (Object doc : new org.yaml.snakeyaml.Yaml().loadAll(Files.readString(file, StandardCharsets.UTF_8))) {
                if (doc instanceof Map<?, ?> m && m.get("server") instanceof Map<?, ?> server && server.get("ssl") != null) {
                    ssl = server.get("ssl");
                }
            }
        }
        if (ssl instanceof Map<?, ?> block && block.get("key-store") != null) {
            return;   // yml 이 TLS 를 켠다
        }
        String manifest = Files.readString(repoRoot().resolve("k8s/32-config-server.yaml"), StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        for (String env : List.of("SERVER_SSL_KEY_STORE", "SERVER_SSL_KEY_STORE_TYPE", "SERVER_SSL_KEY_STORE_PASSWORD",
                "SERVER_SSL_KEY_PASSWORD")) {
            if (!Pattern.compile("(?m)^\\s*- name: " + env + "\\s*\\n\\s*value(From)?:").matcher(manifest).find()) {
                missing.add(env);
            }
        }
        assertThat(missing).as("config-server 가 평문으로 뜬다 — 클라이언트의 https 설정 가져오기가 조용히 실패한다(B-91)").isEmpty();
    }

    /**
     * B-90: 자바 모듈에 {@code Dockerfile} 을 두지 않는다 — 백엔드 이미지는 {@code ops/Dockerfile.runtime} 한 벌(ops/19 · CI).
     *
     * <p>2026-10-15 에 지운 모듈별 Dockerfile 13벌은 아무도 쓰지 않았다. 빌더에서 gradle 을 다시 돌리고, 가짜 스토어 비밀번호
     * ({@code keystore_password})와 없는 경로가 든 JMX 실행 스크립트를 담고 있었다 — 손으로 그 파일로 빌드하면 운영과 다른 이미지가
     * 됐다. frontend 의 Dockerfile 은 손 빌드 경로로 문서에 있어 남긴다.
     *
     * <p>[JA] Java モジュールに Dockerfile を置かない — バックエンドのイメージは ops/Dockerfile.runtime 一つ。削除した 13 個は誰も
     * 使っておらず、偽のストアパスワード入りの JMX 起動スクリプトを含んでいた。
     */
    @Test
    @DisplayName("B-90: 자바 모듈(build.gradle 이 있는 디렉터리)에 Dockerfile 이 없다 — 이미지는 ops/Dockerfile.runtime")
    void noPerModuleDockerfiles() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                if (!Files.exists(module.resolve("build.gradle")) && !Files.exists(module.resolve("build.gradle.kts"))) {
                    continue;
                }
                try (Stream<Path> files = Files.list(module)) {
                    files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("Dockerfile"))
                            .forEach(n -> offenders.add(module.getFileName() + "/" + n));
                }
            }
        }
        assertThat(Files.exists(repoRoot().resolve("ops/Dockerfile.runtime"))).isTrue();
        assertThat(offenders).as("백엔드 이미지는 ops/19 가 ops/Dockerfile.runtime 으로 만든다(B-90)").isEmpty();
    }

    /**
     * B-89: 읽히지 않는 {@code bootstrap.yml} 을 두지 않는다.
     *
     * <p>Boot 2.4 이후 {@code bootstrap.yml} 은 {@code spring-cloud-starter-bootstrap} 이 있을 때만 읽힌다. 모듈 루트의 사본은 아예
     * 클래스패스 밖이다. 그런데 파일이 있으면 읽는 사람은 그 설정({@code spring.cloud.config.uri} 등)이 살아 있다고 믿는다 — eureka ·
     * gateway 의 {@code src/main/resources/bootstrap.yml} 과 모듈 9곳의 루트 사본이 그랬다(pay 의 사본은 이름까지 order-service 였다,
     * 2026-10-15 삭제). 설정은 {@code application*.yml} 에 둔다.
     *
     * <p>[JA] 読まれない bootstrap.yml を置かない。spring-cloud-starter-bootstrap がなければ読まれず、モジュール直下の写しはクラスパスの
     * 外。あると設定が生きていると誤解される。
     */
    @Test
    @DisplayName("B-89: bootstrap.yml 은 spring-cloud-starter-bootstrap 이 있는 모듈의 src/main/resources 에만")
    void noUnreadBootstrapFiles() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path build = Files.exists(module.resolve("build.gradle")) ? module.resolve("build.gradle")
                        : module.resolve("build.gradle.kts");
                if (!Files.exists(build)) {
                    continue;
                }
                boolean starter = stripComments(Files.readString(build, StandardCharsets.UTF_8))
                        .contains("spring-cloud-starter-bootstrap");
                for (String name : List.of("bootstrap.yml", "bootstrap.yaml", "bootstrap.properties")) {
                    if (Files.exists(module.resolve(name))) {
                        offenders.add(module.getFileName() + "/" + name + " — 모듈 루트는 클래스패스가 아니다");
                    }
                    if (!starter && Files.exists(module.resolve("src/main/resources/" + name))) {
                        offenders.add(module.getFileName() + "/src/main/resources/" + name
                                + " — spring-cloud-starter-bootstrap 이 없어 읽히지 않는다");
                    }
                }
            }
        }
        assertThat(offenders).as("읽히지 않는 bootstrap 설정은 지우고 application*.yml 에 둔다(B-89)").isEmpty();
    }

    /**
     * B-88: Kafka 리스너 팩토리에 레코드 어댑터({@code setBatchToRecordAdapter})를 두지 않는다.
     *
     * <p>{@code DefaultBatchToRecordAdapter} 는 배치를 레코드 하나씩 리스너에 넘기면서 리스너의 예외를 <b>잡아</b> 생성자의 recoverer 에
     * 넘기기만 한다 — 다시 던지지 않는다. 그래서 리스너가 던진 {@code BatchListenerFailedException} 이 에러 핸들러(재시도 · DLT · 경보
     * KafkaDeadLetterPublished)에 닿지 않고 오프셋이 커밋된다. pay(결제 요청) · order(결제 응답)가 이 모양이라, DB 가 잠깐 흔들리면
     * 손님은 돈을 냈는데 주문은 결제 전으로 남았고 아무 경보도 없었다(2026-10-15). 배치 그대로 받고 실패 위치를 던진다.
     *
     * <p>[JA] Kafka リスナーファクトリにレコードアダプターを置かない。DefaultBatchToRecordAdapter はリスナーの例外を捕まえて
     * recoverer に渡すだけで再送出しない — 再試行 · DLT · 警報に届かずオフセットがコミットされる。
     */
    @Test
    @DisplayName("B-88: setBatchToRecordAdapter 를 쓰지 않는다 — 리스너의 예외를 삼켜 재시도 · DLT 가 없어진다")
    void noBatchToRecordAdapter() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path java : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                        String code = stripComments(Files.readString(java, StandardCharsets.UTF_8));
                        if (code.contains("setBatchToRecordAdapter(") || code.contains("new DefaultBatchToRecordAdapter")) {
                            offenders.add(repoRoot().relativize(java).toString());
                        }
                    }
                }
            }
        }
        assertThat(offenders)
                .as("레코드 어댑터는 리스너의 예외를 삼킨다 — 배치 리스너는 BatchListenerFailedException(index) 를 던지고 에러 핸들러가 받게 한다(B-88)")
                .isEmpty();
    }

    /**
     * B-87: order · cart · coupon 은 open-in-view 를 끈다(coupon 은 2026-10-15 CP2).
     *
     * <p>스프링 기본값(켜짐)이면 요청 내내 EntityManager 가 살아 있고, 첫 조회에서 잡은 DB 커넥션을 요청이 끝날 때까지 놓지 않는다 —
     * 트랜잭션 밖으로 뺀 원격 호출(catalog · coupon · 결제 제공자)도 커넥션을 쥔 채 기다린다. 풀이 5개라 느린 catalog 하나가 다른 요청까지
     * 막았다. 끈 뒤에는 트랜잭션 밖에서 쓰는 엔티티를 조회가 연관과 함께 읽는다(@EntityGraph — OrderRepository.findByTrackingId ·
     * CartRepository.findByUserId).
     *
     * <p>[JA] order · cart は open-in-view を切る。既定(オン)ではリクエスト中ずっと接続を持ち、トランザクション外の遠隔呼び出しも
     * 接続を持ったまま待つ。
     */
    @Test
    @DisplayName("B-87: order · cart · coupon 의 spring.jpa.open-in-view 는 false")
    void openInViewIsOffWhereRemoteCallsLeaveTransactions() throws IOException {
        for (String module : List.of("order-service", "cart-service", "coupon-service")) {
            Path yml = repoRoot().resolve(module + "/src/main/resources/application-local.yml");
            if (!Files.exists(yml)) {
                continue;
            }
            String text = Files.readString(yml, StandardCharsets.UTF_8);
            assertThat(text)
                    .as("%s: spring.jpa.open-in-view: false — 켜져 있으면 트랜잭션 밖의 원격 호출도 DB 커넥션을 쥔 채 기다린다(B-87)", module)
                    .containsPattern("(?m)^\\s+jpa:\\s*\\n(?:\\s+#.*\\n)*\\s+open-in-view:\\s*false\\b");
        }
    }

    /**
     * B-78: 주문 상태는 {@code OrderStatusChanger} 만 바꾼다(docs/76).
     *
     * <p>상태 변경은 같은 트랜잭션의 아웃박스 행({@code order-status})이 되어 saga-projection 의 읽기 모델(타임라인)로
     * 접힌다. 누군가 {@code order.setOrderStatus(...)} 를 직접 부르면 DB 는 바뀌는데 이벤트가 없다 — 읽기 모델이
     * <b>조용히</b> 틀린다(테스트는 모두 초록). 그래서 호출 자리를 한 곳으로 묶고 여기서 지킨다.
     *
     * <p>[JA] 注文状態は OrderStatusChanger だけが変える(docs/76)。直接 setOrderStatus を呼ぶと DB は変わるのにイベントが
     * なく、読み取りモデルが<b>静かに</b>ずれる。
     */
    @Test
    @DisplayName("B-78: order.setOrderStatus · markPaid 는 OrderStatusChanger 밖에서 부르지 않는다")
    void orderStatusChangesGoThroughTheChanger() throws IOException {
        Path src = repoRoot().resolve("order-service/src/main/java");
        if (!Files.isDirectory(src)) {
            return;
        }
        // statusChanger.markPaid(...) 는 바로 그 변경기 호출이다 — 제외
        Pattern call = Pattern.compile("(?<!statusChanger)\\.(setOrderStatus|markPaid)\\(");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path java : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String name = java.getFileName().toString();
                if (name.equals("OrderStatusChanger.java")) {
                    continue;
                }
                String code = stripComments(Files.readString(java, StandardCharsets.UTF_8));
                Matcher m = call.matcher(code);
                while (m.find()) {
                    int line = code.substring(0, m.start()).split("\n", -1).length;
                    offenders.add(repoRoot().relativize(java) + ":" + line + " → " + m.group());
                }
            }
        }
        assertThat(offenders)
                .as("주문 상태는 OrderStatusChanger.change/markPaid 로 바꾼다 — 직접 바꾸면 order-status 이벤트가 빠진다(docs/76)")
                .isEmpty();
    }

    /**
     * B-76: 기능 플래그(docs/74) — 정의 파일 · 코드 · 매니페스트가 맞물리고, 다 쓴 플래그가 쌓이지 않는다.
     *
     * <ol>
     *   <li>{@code k8s/15-feature-flags.yaml} 의 {@code flags.json} 이 서비스가 읽는 형식 그대로 읽힌다 — 틀리면 서비스는
     *       경고 한 줄만 남기고 <b>직전 정의</b>를 쓰므로, 고친 것이 운영에 조용히 반영되지 않는다</li>
     *   <li>플래그마다 {@code metadata.owner} · {@code metadata.expires}, 그리고 만료가 지나지 않았다. 플래그는 빚이다 —
     *       날짜가 없으면 아무도 지우지 않는다. 만료되면 이 가드가 빨갛다: 코드와 함께 지우거나 날짜를 미룬다</li>
     *   <li>OpenFeature 를 쓰는 코드의 {@code static final String …FLAG… = "키"} 가 정의 파일에 있다 — 없으면 모든 평가가 FLAG_NOT_FOUND 로
     *       코드의 기본값이 되어, 비중을 올려도 아무 일도 일어나지 않는다</li>
     *   <li>정의 파일의 플래그를 코드가 쓴다 — 아무도 읽지 않는 플래그는 지운 코드의 흔적이다</li>
     *   <li>catalog 는 ConfigMap 을 <b>디렉터리로</b> 마운트하고 {@code FEATURE_FLAGS_FILE} 이 그 안을 가리킨다 —
     *       subPath 마운트는 ConfigMap 이 바뀌어도 갱신되지 않는다(재시작해야 반영 = 플래그의 의미가 없다)</li>
     *   <li>베이스 kustomization 에 들어 있다</li>
     * </ol>
     *
     * <p>[JA] 機能フラグ(docs/74) — 定義ファイル・コード・マニフェストが噛み合い、使い終わったフラグが溜まらない。フラグごとに
     * owner ・ expires が必要で、期限切れはこのガードが赤くなる。コードが参照するキーは定義ファイルに、定義ファイルの
     * フラグはコードにある。catalog は ConfigMap を<b>ディレクトリとして</b>マウントする(subPath は更新されない)。
     */
    @Test
    @DisplayName("B-76: 기능 플래그 — 정의 파일이 읽히고, owner · 만료일이 있고, 코드 · 마운트와 맞는다")
    void featureFlagsAreConsistent() throws IOException {
        Path root = repoRoot();
        List<String> offenders = new ArrayList<>();

        String manifest = Files.readString(root.resolve("k8s/15-feature-flags.yaml"), StandardCharsets.UTF_8);
        Map<String, Object> cm = new org.yaml.snakeyaml.Yaml().load(manifest);
        @SuppressWarnings("unchecked")
        String json = (String) ((Map<String, Object>) cm.get("data")).get("flags.json");
        com.project.common.featureflag.FlagDefinitions defs = com.project.common.featureflag.FlagDefinitions.parse(json);

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        defs.all().values().forEach(f -> {
            if (f.owner() == null || f.owner().isBlank()) {
                offenders.add(f.key() + " → metadata.owner 없음");
            }
            if (f.expires() == null) {
                offenders.add(f.key() + " → metadata.expires 없음");
            } else if (f.expires().isBefore(today)) {
                offenders.add(f.key() + " → 만료(" + f.expires() + ") — 코드와 함께 지우거나 날짜를 미룬다");
            }
        });

        Pattern constant = Pattern.compile("static\\s+final\\s+String\\s+\\w*FLAG\\w*\\s*=\\s*\"([^\"]+)\"");
        Set<String> referenced = new TreeSet<>();
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path src : modules.map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.walk(src)) {
                    sources.addAll(files.filter(x -> x.toString().endsWith(".java")).toList());
                }
            }
        }
        {
            for (Path java : sources) {
                String code = stripComments(Files.readString(java, StandardCharsets.UTF_8));
                if (!code.contains("import dev.openfeature.sdk.")) {
                    continue;   // OpenFeature 를 쓰는 파일만 — 다른 …FLAG 상수(예: 마이그레이션 표식)는 기능 플래그가 아니다
                }
                Matcher m = constant.matcher(code);
                while (m.find()) {
                    referenced.add(m.group(1));
                    if (defs.get(m.group(1)) == null) {
                        offenders.add(root.relativize(java) + " → 플래그 '" + m.group(1) + "' 가 k8s/15-feature-flags.yaml 에 없다");
                    }
                }
            }
        }
        defs.all().keySet().stream().filter(k -> !referenced.contains(k))
                .forEach(k -> offenders.add("k8s/15-feature-flags.yaml → '" + k + "' 를 읽는 코드가 없다(…FLAG 상수)"));

        String catalog = Files.readString(root.resolve("k8s/41-catalog-service.yaml"), StandardCharsets.UTF_8);
        // volumeMounts 의 항목(매니페스트에서 volumes 보다 앞에 온다) — 다음 "- " 항목 전까지가 그 항목의 키들이다.
        Matcher mount = Pattern.compile("- name: feature-flags\\n((?: +(?!- )\\S.*\\n)*)").matcher(catalog);
        Matcher mountPath = mount.find() ? Pattern.compile("mountPath: (\\S+)").matcher(mount.group(1)) : null;
        if (mountPath == null || !mountPath.find()) {
            offenders.add("k8s/41-catalog-service.yaml → feature-flags 볼륨 마운트 없음");
        } else {
            if (mount.group(1).contains("subPath:")) {
                offenders.add("k8s/41-catalog-service.yaml → feature-flags 를 subPath 로 마운트했다 — 갱신되지 않는다");
            }
            Matcher env = Pattern.compile("- name: FEATURE_FLAGS_FILE\\n\\s+value: \"([^\"]+)\"").matcher(catalog);
            if (!env.find() || !env.group(1).startsWith(mountPath.group(1) + "/")) {
                offenders.add("k8s/41-catalog-service.yaml → FEATURE_FLAGS_FILE 이 마운트 경로(" + mountPath.group(1) + ") 안을 가리키지 않는다");
            }
        }
        if (!catalog.contains("configMap:\n            name: feature-flags")) {
            offenders.add("k8s/41-catalog-service.yaml → feature-flags 볼륨이 ConfigMap feature-flags 가 아니다");
        }
        if (!Files.readString(root.resolve("k8s/kustomization.yaml"), StandardCharsets.UTF_8).contains("- 15-feature-flags.yaml")) {
            offenders.add("k8s/kustomization.yaml → 15-feature-flags.yaml 이 resources 에 없다");
        }

        assertThat(offenders).as("기능 플래그 정의 · 코드 · 매니페스트가 어긋났다(docs/74)").isEmpty();
    }

    /**
     * 주석을 지운다 — 주석 안의 예시 코드까지 위반으로 잡으면, 사고를 기록해 둔 주석이
     * 그 자체로 가드를 깨뜨린다(이 가드를 쓰면서 실제로 겪었다).
     *
     * <p>문자열 리터럴 안의 {@code //} 를 주석으로 착각하지 않도록 리터럴을 먼저 건너뛴다.
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < src.length() && src.charAt(j) != c) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(src, i, Math.min(j + 1, src.length()));
                i = j;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 1;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 로그 한 줄로 줄여 실패 메시지를 읽을 만하게 만든다. */
    private static String oneLine(String snippet) {
        String flat = snippet.replaceAll("\\s+", " ").strip();
        return flat.length() > 120 ? flat.substring(0, 117) + "…" : flat;
    }

    /**
     * Spring 의 {@code PathPattern} 술어를 정규식으로 옮긴다.
     *
     * <p>⚠️ 끝이 {@code /**} 인 패턴은 <b>부모 경로 자체도 매칭한다</b> —
     * {@code /chat-service/chat/**} 는 {@code /chat-service/chat} 에도 걸린다.
     * PathPatternParser 로 실측해 확인했다. 이걸 모르고 {@code **} → {@code .*} 로만 옮기면
     * 멀쩡한 경로를 오탐으로 잡는다(STOMP 브로커 URL 이 정확히 그 모양이다).
     */
    private static Pattern toRegex(String pathPattern) {
        String pattern = pathPattern;
        String tail = "";
        if (pattern.endsWith("/**")) {
            pattern = pattern.substring(0, pattern.length() - 3);
            tail = "(?:/.*)?";
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.startsWith("**", i)) {
                regex.append(".*");
                i++;
            } else if (pattern.charAt(i) == '*') {
                regex.append("[^/]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(pattern.charAt(i))));
            }
        }
        return Pattern.compile(regex.append(tail).append("$").toString());
    }

    /* ── B-55: enum 컬럼의 CHECK 제약 ─────────────────────────────────────── */

    /**
     * 🐛 <b>Stripe 결제가 영원히 NOT_PROCESSED 였다.</b>
     *
     * <p>베이스라인은 hbm2ddl 산출물이라 {@code @Enumerated(STRING)} 컬럼마다 그 시점의 enum 값으로
     * {@code check (payment_type in ('PAYPAL','CREDIT_CARD','CASH'))} 가 박혀 있다. 그 뒤 enum 에
     * STRIPE·PORTONE 을 더했는데 제약은 그대로였다. Hibernate {@code validate} 는 CHECK 를 보지
     * 않으므로 기동은 멀쩡했고, 그 값이 <b>처음 저장되는 순간</b> pay-service 가
     * {@code violates check constraint "payment_payment_type_check"} 로 죽었다 — 사가 응답이
     * 끊겨 주문이 확정되지 않았다(docs/49 §8). {@code orders.order_status} 의 REFUNDED 도 같은
     * 상태라 첫 전액 환불에서 터질 참이었다.
     *
     * <p>컴파일도, 단위 테스트도, 컨테이너 기동 테스트도 이걸 못 잡는다 — 값이 실제로 저장돼야
     * 드러난다. 그래서 소스를 대조한다: 엔티티의 문자열 enum 컬럼마다, 마이그레이션이 마지막으로
     * 정의한 CHECK 목록이 자바 enum 의 모든 상수를 담고 있어야 한다.
     */
    /**
     * 일부러 enum 보다 좁게 건 CHECK. 여기 적힌 값은 그 컬럼에 <b>저장될 수 없는 것이 설계</b>다 —
     * 코드가 그 값을 쓰지 않는다는 뜻이지, 제약이 낡았다는 뜻이 아니다. 새로 더할 때는 이유를 적을 것.
     */
    private static final Map<String, Set<String>> NARROWER_BY_DESIGN = Map.of(
            // 쿠폰 대상 행은 카테고리·상품에만 있다. 범위 ALL 은 대상 행 자체가 없다(CouponTarget 주석).
            "coupon-service:coupon_target.target_type", Set.of("ALL"));

    @Test
    @DisplayName("B-55: enum 컬럼의 CHECK 제약은 자바 enum 의 모든 값을 허용해야 한다 — 값을 더했으면 마이그레이션도")
    void enumCheckConstraintsMustCoverJavaEnums() throws IOException {
        Path root = repoRoot();
        Path common = root.resolve("common/src/main/java");
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path java = module.resolve("src/main/java");
                Path migrations = module.resolve("src/main/resources/db/migration");
                if (!Files.isDirectory(java) || !Files.isDirectory(migrations)) {
                    continue;
                }
                Map<String, Set<String>> allowed = checkConstraintsIn(migrations);
                for (EnumColumn col : enumColumnsIn(java)) {
                    Set<String> values = allowed.get(col.table() + "." + col.column());
                    if (values == null) {
                        continue;   // 그 컬럼에는 CHECK 가 없다 — 막을 것이 없다
                    }
                    Set<String> constants = enumConstants(col.enumType(), java, common);
                    if (constants == null) {
                        continue;
                    }
                    checked++;
                    Set<String> missing = new TreeSet<>(constants);
                    missing.removeAll(values);
                    missing.removeAll(NARROWER_BY_DESIGN.getOrDefault(
                            module.getFileName() + ":" + col.table() + "." + col.column(), Set.of()));
                    if (!missing.isEmpty()) {
                        offenders.add(module.getFileName() + ": " + col.table() + "." + col.column()
                                + " 의 check 가 " + missing + " 를 허용하지 않는다 (enum "
                                + col.enumType() + ", 허용 " + values + ")");
                    }
                }
            }
        }

        assertThat(checked).as("대조할 enum 컬럼을 하나도 못 찾았다 — 파서가 깨졌다").isPositive();
        assertThat(offenders)
                .as("enum 에 값을 더했으면 그 컬럼의 CHECK 도 새 마이그레이션으로 다시 만들어야 한다. "
                        + "예: pay-service V3__payment_type_check_all_providers.sql — "
                        + "DROP CONSTRAINT IF EXISTS <table>_<column>_check 뒤에 "
                        + "ADD CONSTRAINT <같은 이름> CHECK (<column> IN (<enum 전부>)). "
                        + "베이스라인은 고치지 않는다(적용된 파일).")
                .isEmpty();
    }

    private record EnumColumn(String table, String column, String enumType) { }

    private static String stripJavaComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static String snakeCase(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    /** 엔티티의 {@code @Enumerated(EnumType.STRING)} 컬럼 — (테이블, 컬럼, enum 형). */
    private static List<EnumColumn> enumColumnsIn(Path javaRoot) throws IOException {
        Pattern tableName = Pattern.compile("@Table\\s*\\([^)]*?name\\s*=\\s*\"([^\"]+)\"");
        Pattern enumerated = Pattern.compile("@Enumerated\\s*\\(\\s*(?:value\\s*=\\s*)?EnumType\\.STRING\\s*\\)");
        Pattern columnName = Pattern.compile("@Column\\s*\\([^)]*?name\\s*=\\s*\"([^\"]+)\"");
        Pattern field = Pattern.compile("private\\s+(?:final\\s+)?([\\w.]+)(?:<[^>]*>)?\\s+(\\w+)\\s*[;=]");
        List<EnumColumn> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(javaRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String src = stripJavaComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!src.contains("@Entity")) {
                    continue;
                }
                Matcher t = tableName.matcher(src);
                if (!t.find()) {
                    continue;
                }
                String table = t.group(1).toLowerCase();
                String[] lines = src.split("\\n");
                for (int i = 0; i < lines.length; i++) {
                    if (!enumerated.matcher(lines[i]).find()) {
                        continue;
                    }
                    String column = null;
                    // @Column 이 @Enumerated 앞에 올 수도 있다 — 직전 필드 선언(;)을 넘지 않는 범위에서.
                    for (int j = i - 1; j >= Math.max(0, i - 3) && !lines[j].contains(";"); j--) {
                        Matcher c = columnName.matcher(lines[j]);
                        if (c.find()) {
                            column = c.group(1);
                        }
                    }
                    for (int j = i; j < Math.min(lines.length, i + 8); j++) {
                        Matcher c = columnName.matcher(lines[j]);
                        if (c.find()) {
                            column = c.group(1);
                        }
                        Matcher f = field.matcher(lines[j]);
                        if (f.find()) {
                            String type = f.group(1);
                            type = type.substring(type.lastIndexOf('.') + 1);
                            String col = column != null ? column : snakeCase(f.group(2));
                            result.add(new EnumColumn(table, col.toLowerCase(), type));
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    /** 자바 enum 의 상수 이름들. 소스에서 읽는다 — 서비스 모듈은 테스트 클래스패스에 없다. */
    private static Set<String> enumConstants(String simpleName, Path... roots) throws IOException {
        for (Path rootDir : roots) {
            Path source;
            try (Stream<Path> files = Files.walk(rootDir)) {
                source = files.filter(p -> p.getFileName().toString().equals(simpleName + ".java"))
                        .findFirst().orElse(null);
            }
            if (source == null) {
                continue;
            }
            String src = stripJavaComments(Files.readString(source, StandardCharsets.UTF_8));
            Matcher m = Pattern.compile("enum\\s+" + Pattern.quote(simpleName) + "\\b[^{]*\\{").matcher(src);
            if (!m.find()) {
                continue;
            }
            String body = src.substring(m.end());
            int semicolon = indexAtDepthZero(body, ';');
            int close = indexAtDepthZero(body, '}');
            int cut = semicolon >= 0 && (close < 0 || semicolon < close) ? semicolon : close;
            String constants = cut < 0 ? body : body.substring(0, cut);
            Set<String> names = new TreeSet<>();
            for (String part : splitAtDepthZero(constants, ',')) {
                Matcher c = Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)").matcher(part);
                if (c.find()) {
                    names.add(c.group(1));
                }
            }
            return names;
        }
        return null;
    }

    /** 괄호·중괄호·문자열 밖에서 처음 나오는 {@code target} 의 위치. 없으면 -1. */
    private static int indexAtDepthZero(String text, char target) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (ch == '\\') { i++; } else if (ch == '"') { inString = false; }
                continue;
            }
            if (ch == '"') { inString = true; }
            else if (ch == '(' || ch == '{') { depth++; }
            else if (ch == ')' || ch == '}') { depth--; }
            else if (ch == target && depth == 0) { return i; }
        }
        return -1;
    }

    private static List<String> splitAtDepthZero(String text, char separator) {
        List<String> parts = new ArrayList<>();
        String rest = text;
        while (true) {
            int at = indexAtDepthZero(rest, separator);
            if (at < 0) {
                parts.add(rest);
                return parts;
            }
            parts.add(rest.substring(0, at));
            rest = rest.substring(at + 1);
        }
    }

    /**
     * 마이그레이션이 정의한 {@code check (<col> in (...))} — 버전 순으로 읽어 <b>마지막 정의</b>가
     * 남는다. 키는 {@code table.column}(스키마 없이, 소문자).
     */
    private static Map<String, Set<String>> checkConstraintsIn(Path migrations) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(migrations)) {
            files = s.filter(p -> p.getFileName().toString().matches("V.*__.*\\.sql"))
                    .sorted(Comparator.comparing(ConfigurationGuardrailTest::migrationVersion,
                            ConfigurationGuardrailTest::compareVersions))
                    .toList();
        }
        Pattern createTable = Pattern.compile(
                "^create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(?:\"?\\w+\"?\\.)?\"?(\\w+)\"?", Pattern.CASE_INSENSITIVE);
        Pattern alterTable = Pattern.compile(
                "^alter\\s+table\\s+(?:only\\s+)?(?:if\\s+exists\\s+)?(?:\"?\\w+\"?\\.)?\"?(\\w+)\"?", Pattern.CASE_INSENSITIVE);
        Pattern check = Pattern.compile(
                "check\\s*\\(\\s*(?:\"?\\w+\"?\\s+is\\s+null\\s+or\\s+)?\"?(\\w+)\"?\\s+in\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
        Pattern quoted = Pattern.compile("'([^']*)'");

        Map<String, Set<String>> result = new TreeMap<>();
        for (Path file : files) {
            String sql = Files.readString(file, StandardCharsets.UTF_8)
                    .replaceAll("--[^\\n]*", "")
                    .replaceAll("\\s+", " ");
            for (String statement : sql.split(";")) {
                String s = statement.strip();
                Matcher t = createTable.matcher(s);
                if (!t.find()) {
                    t = alterTable.matcher(s);
                    if (!t.find()) {
                        continue;
                    }
                }
                String table = t.group(1).toLowerCase();
                Matcher c = check.matcher(s);
                while (c.find()) {
                    Set<String> values = new TreeSet<>();
                    Matcher v = quoted.matcher(c.group(2));
                    while (v.find()) {
                        values.add(v.group(1));
                    }
                    result.put(table + "." + c.group(1).toLowerCase(), values);
                }
            }
        }
        return result;
    }

    /** {@code V2_1__x.sql} → [2, 1]. Flyway 와 같은 순서로 비교한다. */
    private static List<Integer> migrationVersion(Path file) {
        String name = file.getFileName().toString();
        String version = name.substring(1, name.indexOf("__"));
        List<Integer> parts = new ArrayList<>();
        for (String part : version.split("[._]")) {
            parts.add(Integer.parseInt(part));
        }
        return parts;
    }

    /* ── B-56: 오버레이 직접 적용 사고 ───────────────────────────────────── */

    /**
     * 🐛 <b>프론트엔드가 몇 주 전 버전으로 돌아갔다.</b>
     *
     * <p>설정 한 줄을 반영하려고 {@code kubectl apply -k k8s-overlays/local} 을 쳤다. 그런데 그
     * 오버레이의 {@code images:} 에는 손으로 적어 둔 {@code newTag} 가 박혀 있어서, apply 는
     * <b>전 서비스를 그 옛 태그로 되돌렸다.</b> 배포가 실패한 것도 롤백이 돈 것도 아니다 —
     * 파일에 적힌 대로 적용됐을 뿐인데, 증상은 "배포가 이상하다"로 나타나 원인을 찾는 데
     * 오래 걸렸다.
     *
     * <p>{@code ops/28-deploy.sh} 는 배포할 버전(또는 {@code --config-only} 일 때는 클러스터에서
     * 읽은 현재 버전)으로 태그를 덮어쓴 임시 오버레이를 만들어 적용하므로 이 사고가 없다.
     * 그래서 <b>태그를 고정한 오버레이에는 그 경고가 붙어 있어야 한다.</b> 경고가 없으면
     * 다음 사람이 같은 명령을 친다 — 실제로 문서가 그렇게 안내하고 있었다.
     *
     * <p>태그를 고정하지 않는 오버레이({@code external}·{@code oci})는 대상이 아니다. 그쪽은
     * 부모의 태그를 그대로 물려받으므로 직접 apply 해도 되돌릴 태그가 없다.
     */
    @Test
    @DisplayName("B-56: 태그를 고정한 오버레이는 직접 apply 하지 말라고 적어야 한다 — 옛 버전으로 되돌린다")
    void pinnedOverlaysWarnAgainstDirectApply() throws IOException {
        Path overlays = repoRoot().resolve("k8s-overlays");
        if (!Files.exists(overlays)) {
            return;
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(overlays)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path kustomization = dir.resolve("kustomization.yaml");
                // local/ 은 각자의 것이라 커밋되지 않는다 — 검사 대상은 저장소가 소유한 파일뿐이다.
                if (!Files.exists(kustomization) || dir.getFileName().toString().equals("local")) {
                    continue;
                }
                String text = Files.readString(kustomization, StandardCharsets.UTF_8);
                if (!text.contains("newTag:")) {
                    continue;
                }
                if (!text.contains("ops/28-deploy.sh")) {
                    offenders.add(overlays.getFileName() + "/" + dir.getFileName()
                            + "/kustomization.yaml — newTag 를 고정하는데 ops/28 안내가 없다");
                }
            }
        }
        assertThat(offenders)
                .as("이 오버레이를 직접 apply 하면 전 서비스가 적힌 태그로 되돌아간다. "
                        + "그 경고와 ops/28-deploy.sh 로 가는 길을 파일에 적어 둘 것")
                .isEmpty();
    }

    /**
     * 설정만 반영하는 길이 실제로 존재해야 한다. 없으면 사람은 결국 {@code apply -k} 로 돌아간다 —
     * B-56 의 경고문은 "그럼 어떻게 하라는 것이냐"에 답할 수 있을 때만 지켜진다.
     */
    @Test
    @DisplayName("B-56b: ops/28 은 이미지를 건드리지 않고 설정만 반영하는 경로를 제공해야 한다")
    void deployScriptOffersConfigOnlyPath() throws IOException {
        Path script = repoRoot().resolve("ops/28-deploy.sh");
        assertThat(script).exists();
        String text = Files.readString(script, StandardCharsets.UTF_8);

        assertThat(text).as("--config-only 옵션 파싱").contains("--config-only)");
        assertThat(text).as("--restart 옵션 파싱").contains("--restart)");
        // 버전을 사람이 적게 하면 그 순간 옛 태그가 다시 들어온다. 클러스터에서 읽어야 한다.
        assertThat(text).as("--config-only 는 현재 버전을 클러스터에서 읽는다")
                .contains("VERSION=\"$CURRENT_VERSION\"");
    }

    /* ── B-57: 공통 로그 설정과 마스킹 (docs/51) ─────────────────────────── */

    /** common 모듈을 의존하는 모듈 — 공통 logback-spring.xml 이 적용되는 곳. */
    private static List<Path> modulesUsingCommon() throws IOException {
        List<Path> modules = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(repoRoot())) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                for (String build : new String[] {"build.gradle", "build.gradle.kts"}) {
                    Path file = dir.resolve(build);
                    if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8)
                            .matches("(?s).*project\\(\\s*['\"]:common['\"]\\s*\\).*")) {
                        modules.add(dir);
                    }
                }
            }
        }
        return modules;
    }

    /**
     * 🔒 서비스가 자기 logback 설정을 두면 <b>그쪽이 이긴다</b> — 공통 설정의 마스킹이 통째로
     * 빠지고, 이메일·전화·토큰이 다시 평문으로 찍힌다. eureka-server 가 실제로 그 상태였다
     * (자기 logback-spring.xml 이 Netflix 로거를 DEBUG 로 고정했고, 운영용이라던 파일은 이름이
     * {@code logback-psring-prd.xml} 로 오타라 아무도 읽지 않았다).
     *
     * <p>공통 설정이 패턴을 정하므로 서비스 yml 의 {@code logging.pattern.*} 도 막는다. 두면 쓰이지
     * 않는데 고친 사람은 바뀌었다고 믿는다. 레벨({@code logging.level})과 파일 이름
     * ({@code logging.file.name})은 그대로 서비스가 정한다.
     */
    @Test
    @DisplayName("B-57: common 을 쓰는 서비스는 자기 logback 설정도 logging.pattern 도 두지 않는다 — 마스킹이 빠진다")
    void servicesUseTheCommonLogbackConfig() throws IOException {
        List<Path> modules = modulesUsingCommon();
        assertThat(modules).as("common 을 의존하는 모듈").hasSizeGreaterThan(5);

        List<String> offenders = new ArrayList<>();
        for (Path module : modules) {
            Path resources = module.resolve("src/main/resources");
            if (!Files.isDirectory(resources)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(resources)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String name = f.getFileName().toString();
                    String rel = repoRoot().relativize(f).toString();
                    if (name.matches("(?i)(logback|log4j2).*\\.(xml|groovy)")) {
                        offenders.add(rel + " — 자기 로그 설정. 공통 설정(마스킹)을 덮는다");
                    }
                    if (name.matches("application.*\\.ya?ml")) {
                        String yml = Files.readString(f, StandardCharsets.UTF_8);
                        if (Pattern.compile("(?m)^logging:\\s*\\n(?:(?:[ \\t].*|\\s*)\\n)*?[ \\t]+pattern:")
                                .matcher(yml).find()) {
                            offenders.add(rel + " — logging.pattern 은 쓰이지 않는다(common/logback-spring.xml 이 정한다)");
                        }
                        // 공통 설정은 logging.file.name 에 JSON 을 쓴다. .log 로 두면 이름과 내용이 다르고
                        // 수집기(Alloy, */logs/*.json)가 읽지 않는다.
                        Matcher file = Pattern.compile("(?m)^[ \\t]+name:\\s*(\\$\\{logging\\.file\\.path}[^\\s#]*)").matcher(yml);
                        while (file.find()) {
                            if (!file.group(1).endsWith(".json")) {
                                offenders.add(rel + " — logging.file.name 은 .json 이어야 한다: " + file.group(1));
                            }
                        }
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /**
     * 공통 설정의 모든 텍스트 패턴은 메시지와 예외를 {@code %mask} 로 감싸야 한다. 새 appender 를
     * 더하면서 {@code %msg} 를 그대로 쓰면 그 출력으로만 평문이 샌다.
     */
    @Test
    @DisplayName("B-57b: 공통 logback 설정의 모든 출력은 마스킹을 지난다 — %mask(%msg)·%mask(%wEx)")
    void commonLogbackConfigMasksEveryOutput() throws IOException {
        Path config = repoRoot().resolve("common/src/main/resources/logback-spring.xml");
        assertThat(config).exists();
        String xml = Files.readString(config, StandardCharsets.UTF_8);

        assertThat(xml).as("마스킹 변환 규칙 등록")
                .contains("com.project.common.logging.MaskingPatternConverter");

        // 패턴 값(<pattern>…</pattern> 와 property value="…")을 모두 모은다.
        List<String> patterns = new ArrayList<>();
        Matcher m = Pattern.compile("<pattern>(.*?)</pattern>|value=\"([^\"]*%(?:msg|m|message)\\b[^\"]*)\"", Pattern.DOTALL)
                .matcher(xml);
        while (m.find()) {
            patterns.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        assertThat(patterns).as("텍스트 패턴").isNotEmpty();
        for (String p : patterns) {
            if (p.startsWith("${")) {
                continue;   // 위에서 검사한 property 를 가리킨다
            }
            // 복합 변환자 바로 뒤의 % 는 글자로 찍힌다 — "%mask(%msg)%n" 이면 줄바꿈 대신 "%n" 이 나오고
            // 모든 줄이 한 줄로 붙는다(운영에서 실제로 그랬다). 빈 옵션 {} 로 닫아야 한다.
            assertThat(p).as("%mask(...) 바로 뒤에 % 가 오면 글자로 찍힌다 — {} 로 닫을 것: " + p)
                    .doesNotContainPattern("%mask\\([^)]*\\)%");
            String bare = p.replaceAll("%mask\\(%(?:msg|wEx|ex)\\)(?:\\{\\})?", "");
            assertThat(bare).as("가리지 않은 메시지·예외가 남았다: " + p)
                    .doesNotContainPattern("%(?:msg|m|message)\\b")
                    .doesNotContainPattern("%(?:wEx|ex|exception|throwable)\\b");
            assertThat(p).as("예외도 감싸야 한다(예외 메시지에 이메일이 섞인다): " + p).contains("%mask(%wEx)");
        }

        // JSON 인코더가 있다면 전부 마스커를 달아야 한다(docs/51 3단계).
        int encoders = xml.split("LogstashEncoder", -1).length - 1;
        int maskers = xml.split("com.project.common.logging.SensitiveValueMasker", -1).length - 1;
        assertThat(maskers).as("LogstashEncoder 마다 SensitiveValueMasker").isEqualTo(encoders);
    }

    /**
     * 로그 수집기(Alloy)는 저장소 전체가 아니라 모듈마다 {@code logs} 디렉터리만 읽기 전용으로 붙인다
     * (루트를 통째로 붙이면 gitignore 된 비밀 파일까지 컨테이너에 보인다). 그래서 서비스를 하나 더하면
     * compose 에도 한 줄을 더해야 한다 — 잊으면 그 서비스의 로그만 Grafana 에 <b>조용히</b> 없다.
     */
    @Test
    @DisplayName("B-58: 로그를 쓰는 모든 모듈의 logs 디렉터리가 Alloy 에 읽기 전용으로 붙어 있다")
    void everyModuleLogDirIsCollected() throws IOException {
        Path compose = repoRoot().resolve("docker-compose-observability.yml");
        assertThat(compose).exists();
        String text = Files.readString(compose, StandardCharsets.UTF_8);

        assertThat(text).as("저장소 루트를 통째로 붙이지 않는다 — 비밀 파일이 보인다")
                .doesNotContainPattern("(?m)^\\s+-\\s+\\./?:/var/log/ecommerce");

        List<String> missing = new ArrayList<>();
        for (Path module : modulesUsingCommon()) {
            String name = module.getFileName().toString();
            if (!Files.exists(module.resolve("src/main/resources"))) {
                continue;
            }
            String mount = "./" + name + "/logs:/var/log/ecommerce/" + name + "/logs:ro";
            if (!text.contains(mount)) {
                missing.add(name + " — docker-compose-observability.yml 의 alloy volumes 에: - " + mount);
            }
        }
        assertThat(missing).isEmpty();

        String alloy = Files.readString(repoRoot().resolve("observability/alloy-config.alloy"), StandardCharsets.UTF_8);
        assertThat(alloy).as("Alloy 가 읽는 경로").contains("/var/log/ecommerce/*/logs/*.json");
    }

    /**
     * k3s 파드의 추적이 노드의 OTel Collector 에 가야 한다 (docs/51 §4d, docs/63).
     *
     * <p>{@code management.otlp.tracing.endpoint} 의 기본값 {@code localhost:4318} 은 파드 안에서는
     * <b>파드 자신</b>이라, 설정을 안 넣으면 추적은 조용히 버려진다 — 오류도 없다(Zipkin 시절 :9411 에서 실제로 겪었다).
     * 그래서 공통 설정을 쓰는 서비스의 Deployment 는 노드 IP 를 downward API 로 받아({@code status.hostIP}) 그 노드의
     * Collector 로 보내야 한다. {@code $(HOST_IP)} 는 <b>앞에 정의된</b> 변수만 풀리므로 순서도 본다.
     */
    @Test
    @DisplayName("B-59: 서비스 Deployment 는 OTEL_EXPORTER_OTLP_TRACES_ENDPOINT 를 노드 IP 로 받는다 — 기본값 localhost 는 파드 자신이다")
    void servicesSendTracesToTheNodeCollector() throws IOException {
        // 공통 설정(application-common.yml)을 쓰지 않는 것 — 추적을 보낼 이유가 없다(하트비트 소음).
        Set<String> exempt = Set.of("discovery-service", "config-server");
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(repoRoot().resolve("k8s"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("\\d+-.*\\.yaml")).sorted().toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Matcher app = Pattern.compile("name: SPRING_APPLICATION_NAME\\s*\\n\\s*value: \"([^\"]+)\"").matcher(text);
                if (!app.find() || exempt.contains(app.group(1))) {
                    continue;
                }
                checked++;
                String rel = repoRoot().relativize(f).toString();
                int hostIp = text.indexOf("fieldPath: status.hostIP");
                int otlp = text.indexOf("name: OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
                if (hostIp < 0 || otlp < 0) {
                    offenders.add(rel + " — HOST_IP(status.hostIP)·OTEL_EXPORTER_OTLP_TRACES_ENDPOINT 가 없다");
                } else if (hostIp > otlp) {
                    offenders.add(rel + " — HOST_IP 가 OTEL_EXPORTER_OTLP_TRACES_ENDPOINT 보다 뒤에 있다($(HOST_IP) 가 풀리지 않는다)");
                } else if (!text.contains("value: \"http://$(HOST_IP):4318/v1/traces\"")) {
                    offenders.add(rel + " — OTEL_EXPORTER_OTLP_TRACES_ENDPOINT 가 노드의 Collector(:4318/v1/traces)를 가리키지 않는다");
                }
                if (text.contains("ZIPKIN_ENDPOINT")) {
                    offenders.add(rel + " — ZIPKIN_ENDPOINT 가 남아 있다(Zipkin 은 없다 — docs/63)");
                }
            }
        }
        assertThat(checked).as("검사한 서비스 Deployment").isGreaterThanOrEqualTo(11);
        assertThat(offenders).isEmpty();
    }

    /**
     * 추적은 OTLP → Collector 한 길뿐이다 (docs/63).
     *
     * <p>Brave · Zipkin 에서 OpenTelemetry 로 옮겼다. 반쯤 되돌아가면(한 서비스만 Brave 의존성 · zipkin 설정) 그 서비스의
     * 추적만 조용히 사라지고, 전파 형식이 섞이면 서비스 사이에서 추적이 끊긴다. 또 공유 설정의 자리표시자 이름과
     * 매니페스트의 환경 변수 이름이 어긋나면 파드는 기본값(localhost)으로 보낸다. 관측 스택 쪽은 Collector 의 OTLP
     * 포트가 파드에서 닿아야 하고(루프백이면 안 된다), Jaeger UI 는 루프백에만 있어야 한다(공개 금지).
     */
    @Test
    @DisplayName("B-71: 추적은 OTLP → Collector 한 길 — Brave·Zipkin 잔재 없음, 자리표시자 = 매니페스트 변수, 포트 노출 범위")
    void tracingGoesThroughOtlpCollectorOnly() throws IOException {
        List<String> offenders = new ArrayList<>();

        String common = Files.readString(repoRoot().resolve("common/src/main/resources/application-common.yml"),
                StandardCharsets.UTF_8);
        if (!common.contains("${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:")) {
            offenders.add("application-common.yml — management.otlp.tracing.endpoint 가 OTEL_EXPORTER_OTLP_TRACES_ENDPOINT 를 읽지 않는다");
        }

        try (Stream<Path> files = Files.walk(repoRoot(), 5)) {
            for (Path f : files.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/node_modules/")
                            && !p.toString().contains("/.gradle/"))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.equals("build.gradle") || (n.startsWith("application") && n.endsWith(".yml"));
                    }).sorted().toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                String rel = repoRoot().relativize(f).toString();
                if (text.contains("micrometer-tracing-bridge-brave") || text.contains("zipkin-reporter")) {
                    offenders.add(rel + " — Brave/Zipkin 의존성");
                }
                if (Pattern.compile("(?m)^\\s*zipkin:\\s*$").matcher(text).find() || text.contains("ZIPKIN_ENDPOINT")) {
                    offenders.add(rel + " — zipkin 설정");
                }
            }
        }

        String compose = Files.readString(repoRoot().resolve("docker-compose-observability.yml"), StandardCharsets.UTF_8);
        if (!compose.contains("- \"4318:4318\"")) {
            offenders.add("docker-compose-observability.yml — otel-collector 의 4318 이 모든 인터페이스에 열려 있지 않다(파드가 노드 IP 로 보낸다)");
        }
        if (!compose.contains("- \"127.0.0.1:16686:16686\"")) {
            offenders.add("docker-compose-observability.yml — Jaeger UI 는 루프백에만 연다(ssh -L 로 본다)");
        }
        if (compose.contains("openzipkin/zipkin")) {
            offenders.add("docker-compose-observability.yml — Zipkin 서비스가 남아 있다");
        }
        assertThat(offenders).isEmpty();
    }

    /**
     * 꼬리 샘플링과 RED 지표의 순서, 그리고 추적 보관 (docs/63 §5).
     *
     * <ul>
     *   <li>span_metrics 는 샘플링 <b>앞</b>의 스팬 전부로 센다. 샘플링 뒤로 옮기면 정상 요청은 10% 만, 오류는 전부 남아
     *       호출 수는 10% 로 줄고 오류율은 몇 배로 부풀려진다 — 대시보드도 경보도 거짓말을 한다.</li>
     *   <li>서비스의 머리 샘플링은 1.0 이어야 한다. 낮추면 Collector 가 보기도 전에 버려져 오류 추적이 사라진다.</li>
     *   <li>Jaeger 저장은 볼륨에 남는 badger 다 — 메모리로 되돌리면 재시작마다 추적이 사라진다.</li>
     * </ul>
     */
    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("B-72: RED 는 꼬리 샘플링 앞에서 세고, 서비스는 전부 보내며(1.0), Jaeger 는 볼륨에 저장한다")
    void tailSamplingAfterSpanMetricsAndPersistentTraces() throws IOException {
        List<String> offenders = new ArrayList<>();
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();

        Map<String, Object> collector = yaml.load(Files.readString(
                repoRoot().resolve("observability/otel-collector.yml"), StandardCharsets.UTF_8));
        Map<String, Map<String, List<String>>> pipelines = (Map<String, Map<String, List<String>>>)
                ((Map<String, Object>) collector.get("service")).get("pipelines");
        boolean sampled = false;
        boolean metered = false;
        for (Map.Entry<String, Map<String, List<String>>> p : pipelines.entrySet()) {
            if (!p.getKey().startsWith("traces")) {
                continue;
            }
            List<String> processors = p.getValue().getOrDefault("processors", List.of());
            List<String> exporters = p.getValue().getOrDefault("exporters", List.of());
            boolean samples = processors.stream().anyMatch(x -> x.startsWith("tail_sampling"));
            boolean meters = exporters.stream().anyMatch(x -> x.startsWith("span_metrics"));
            sampled |= samples;
            metered |= meters;
            if (samples && meters) {
                offenders.add("otel-collector.yml " + p.getKey() + " — 샘플링한 스팬으로 RED 를 센다(span_metrics 는 샘플링 앞 파이프라인에)");
            }
            if (samples && exporters.stream().noneMatch(x -> x.contains("jaeger"))) {
                offenders.add("otel-collector.yml " + p.getKey() + " — 샘플링한 추적이 Jaeger 로 가지 않는다");
            }
            if (meters && processors.stream().anyMatch(x -> x.startsWith("tail_sampling") || x.startsWith("probabilistic"))) {
                offenders.add("otel-collector.yml " + p.getKey() + " — span_metrics 앞에 샘플링이 있다");
            }
        }
        if (!sampled) {
            offenders.add("otel-collector.yml — 꼬리 샘플링(tail_sampling) 파이프라인이 없다");
        }
        if (!metered) {
            offenders.add("otel-collector.yml — span_metrics 로 가는 traces 파이프라인이 없다");
        }

        String configmap = Files.readString(repoRoot().resolve("k8s/10-configmap.yaml"), StandardCharsets.UTF_8);
        if (!configmap.contains("TRACING_SAMPLING_PROBABILITY: \"1.0\"")) {
            offenders.add("k8s/10-configmap.yaml — TRACING_SAMPLING_PROBABILITY 는 1.0(샘플링은 Collector 가 한다)");
        }

        Map<String, Object> jaeger = yaml.load(Files.readString(
                repoRoot().resolve("observability/jaeger.yml"), StandardCharsets.UTF_8));
        Map<String, Object> ext = (Map<String, Object>) jaeger.get("extensions");
        String traceStore = (String) ((Map<String, Object>) ((Map<String, Object>) ext.get("jaeger_query")).get("storage")).get("traces");
        Map<String, Object> backends = (Map<String, Object>) ((Map<String, Object>) ext.get("jaeger_storage")).get("backends");
        Map<String, Object> backend = (Map<String, Object>) backends.get(traceStore);
        if (backend == null || !backend.containsKey("badger")
                || Boolean.TRUE.equals(((Map<String, Object>) backend.get("badger")).get("ephemeral"))) {
            offenders.add("observability/jaeger.yml — 추적 저장이 영구(badger, ephemeral: false)가 아니다");
        }
        String compose = Files.readString(repoRoot().resolve("docker-compose-observability.yml"), StandardCharsets.UTF_8);
        if (!compose.contains("- jaeger-data:/badger")) {
            offenders.add("docker-compose-observability.yml — Jaeger 의 /badger 가 볼륨에 붙어 있지 않다(재생성하면 사라진다)");
        }
        assertThat(offenders).isEmpty();
    }

    /**
     * B-73: gRPC 는 네 끝이 맞아야 붙는다(docs/66) — 하나라도 어긋나면 <b>테스트는 모두 초록인 채로</b> 운영에서만 끊긴다
     * (단위 테스트는 in-process · 임의 포트로 돈다).
     *
     * <ol>
     *   <li>proto 의 서비스마다 {@code INTERNAL_AUTH_PATH_RULES} 에 "/패키지.서비스/" 규칙이 있다 — 없으면 규칙이 enforce 되는 순간
     *       그 서비스를 아무 내부 서비스나 부를 수 있다(가장 긴 앞부분 규칙이 없으면 허용 주체만 본다).</li>
     *   <li>catalog 가 여는 gRPC 포트(StockGrpcServerConfig 의 기본값)가 Deployment 의 containerPort 와 Service 포트에 있다.</li>
     *   <li>order 가 부르는 대상(STOCK_GRPC_TARGET)이 그 Service 와 포트를 가리킨다.</li>
     *   <li>전송 스위치(STOCK_RPC_TRANSPORT)가 http · grpc 중 하나다 — 그 밖의 값이면 order 가 뜨지 않는다.</li>
     * </ol>
     */
    @Test
    @DisplayName("B-73: gRPC — proto 서비스마다 경로 규칙, 서버 포트 = 매니페스트 포트 = 호출 대상, 전송 스위치 값")
    void grpcEndsMeet() throws IOException {
        String cm = Files.readString(repoRoot().resolve("k8s/10-configmap.yaml"), StandardCharsets.UTF_8);
        List<String> offenders = new ArrayList<>();

        // ① proto 서비스 ↔ 경로 규칙
        Matcher rules = Pattern.compile("INTERNAL_AUTH_PATH_RULES:\\s*\"([^\"]*)\"").matcher(cm);
        assertThat(rules.find()).isTrue();
        Set<String> rulePrefixes = new java.util.HashSet<>();
        for (String part : rules.group(1).split(";")) {
            rulePrefixes.add(part.substring(0, part.indexOf('=')).trim());
        }
        int services = 0;
        try (Stream<Path> protos = Files.walk(repoRoot().resolve("common/src/main/proto"))) {
            for (Path proto : protos.filter(p -> p.toString().endsWith(".proto")).toList()) {
                String text = Files.readString(proto, StandardCharsets.UTF_8);
                Matcher pkg = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE).matcher(text);
                assertThat(pkg.find()).as(proto + " 에 package 가 없다").isTrue();
                Matcher svc = Pattern.compile("^service\\s+(\\w+)", Pattern.MULTILINE).matcher(text);
                while (svc.find()) {
                    services++;
                    String prefix = "/" + pkg.group(1) + "." + svc.group(1) + "/";
                    if (!rulePrefixes.contains(prefix)) {
                        offenders.add("INTERNAL_AUTH_PATH_RULES 에 " + prefix + " 규칙이 없다 (" + repoRoot().relativize(proto) + ")");
                    }
                }
            }
        }
        assertThat(services).as("proto 서비스를 하나도 못 찾았다면 이 검사가 낡은 것이다").isPositive();

        // ② 서버 포트 ↔ 매니페스트
        String config = Files.readString(repoRoot().resolve(
                "catalog-service/src/main/java/com/project/catalog_service/grpc/StockGrpcServerConfig.java"), StandardCharsets.UTF_8);
        Matcher portDefault = Pattern.compile("stock\\.grpc\\.server\\.port:(\\d+)").matcher(config);
        assertThat(portDefault.find()).as("StockGrpcServerConfig 의 포트 기본값을 못 찾았다").isTrue();
        String port = portDefault.group(1);
        String manifest = Files.readString(repoRoot().resolve("k8s/41-catalog-service.yaml"), StandardCharsets.UTF_8);
        if (!Pattern.compile("containerPort:\\s*" + port + "\\b").matcher(manifest).find()) {
            offenders.add("k8s/41 Deployment 에 containerPort " + port + " 가 없다");
        }
        if (!Pattern.compile("-\\s*name:\\s*grpc\\s+port:\\s*" + port + "\\s+targetPort:\\s*" + port).matcher(manifest).find()) {
            offenders.add("k8s/41 Service 에 grpc 포트 " + port + " 가 없다");
        }

        // ③ 호출 대상
        Matcher target = Pattern.compile("STOCK_GRPC_TARGET:\\s*\"([^\"]*)\"").matcher(cm);
        if (!target.find() || !target.group(1).equals("dns:///catalog-service.ecommerce.svc.cluster.local:" + port)) {
            offenders.add("STOCK_GRPC_TARGET 이 catalog-service Service 의 " + port + " 를 가리키지 않는다"
                    + " — 서버 인증서의 dnsNames(k8s/96)와 호스트 이름도 맞아야 한다");
        }

        // ④ 전송 스위치
        Matcher transport = Pattern.compile("STOCK_RPC_TRANSPORT:\\s*\"([^\"]*)\"").matcher(cm);
        if (!transport.find() || !Set.of("http", "grpc").contains(transport.group(1))) {
            offenders.add("STOCK_RPC_TRANSPORT 는 http · grpc 중 하나여야 한다(그 밖이면 order 가 뜨지 않는다)");
        }

        assertThat(offenders).isEmpty();
    }

    /**
     * 아웃박스를 건너는 추적 (docs/51 §4e). 세 아웃박스 모두 <b>네 끝</b>이 있어야 이어진다 —
     * 컬럼(마이그레이션), 엔티티 필드, 쓰는 쪽의 {@code capture()}, 릴레이의 {@code resume(...)}.
     * 하나라도 빠지면 오류 없이 추적만 조용히 끊긴다.
     */
    @Test
    @DisplayName("B-60: 아웃박스는 행에 추적 문맥을 남기고, 릴레이는 그 문맥 아래에서 보낸다")
    void outboxesCarryTraceContext() throws IOException {
        record Outbox(String service, String table, String entity, String writer, String relay) { }
        List<Outbox> outboxes = List.of(
                new Outbox("order-service", "orders.payment_outbox_event",
                        "outbox/model/PaymentOutboxEvent.java", "outbox/PaymentOutboxHelper.java",
                        "outbox/scheduler/PaymentOutboxScheduler.java"),
                new Outbox("pay-service", "pay.order_outbox_event",
                        "outbox/model/OrderOutboxEvent.java", "outbox/OrderOutboxHelper.java",
                        "outbox/scheduler/OrderOutboxScheduler.java"),
                new Outbox("user-service", "users.outbox_event",
                        "message/model/OutboxEvent.java", "message/UserOutboxHelper.java",
                        "message/UserOutboxScheduler.java"));

        List<String> missing = new ArrayList<>();
        for (Outbox o : outboxes) {
            Path src;
            try (Stream<Path> dirs = Files.walk(repoRoot().resolve(o.service() + "/src/main/java"), 3)) {
                src = dirs.filter(d -> Files.exists(d.resolve(o.entity()))).findFirst().orElse(null);
            }
            if (src == null) {
                missing.add(o.service() + " — 아웃박스 엔티티를 찾지 못했다: " + o.entity());
                continue;
            }
            String migrations;
            try (Stream<Path> files = Files.list(repoRoot().resolve(o.service() + "/src/main/resources/db/migration"))) {
                StringBuilder sb = new StringBuilder();
                for (Path f : files.toList()) {
                    sb.append(Files.readString(f, StandardCharsets.UTF_8));
                }
                migrations = sb.toString();
            }
            if (!Pattern.compile("ALTER TABLE " + Pattern.quote(o.table()) + " ADD COLUMN IF NOT EXISTS trace_context")
                    .matcher(migrations).find()) {
                missing.add(o.service() + " — " + o.table() + " 에 trace_context 를 더하는 마이그레이션");
            }
            if (!Files.readString(src.resolve(o.entity()), StandardCharsets.UTF_8).contains("name = \"trace_context\"")) {
                missing.add(o.service() + " — 엔티티의 trace_context 필드: " + o.entity());
            }
            if (!Files.readString(src.resolve(o.writer()), StandardCharsets.UTF_8).contains("outboxTraceContext.capture()")) {
                missing.add(o.service() + " — 행을 쓸 때 capture(): " + o.writer());
            }
            if (!Files.readString(src.resolve(o.relay()), StandardCharsets.UTF_8).contains("outboxTraceContext.resume(")) {
                missing.add(o.service() + " — 릴레이의 resume(...): " + o.relay());
            }
        }

        // 사가 경로의 배치 리스너는 레코드마다 헤더로 추적을 이어받아야 한다 — 배치 리스너에는 컨슈머 추적이
        // 걸리지 않아, 이어받지 않으면 그 안에서 쓴 아웃박스 행이 빈 문맥을 남긴다(docs/51 §4f).
        for (String listener : List.of(
                "pay-service/src/main/java/com/project/pay_service/ports/input/message/listener/PaymentRequestKafkaListener.java",
                "order-service/src/main/java/com/project/order_service/ports/input/message/listener/PaymentResponseKafkaListener.java")) {
            String code = Files.readString(repoRoot().resolve(listener), StandardCharsets.UTF_8);
            if (!code.contains("outboxTraceContext.continueFrom(")) {
                missing.add(listener + " — 배치 리스너가 레코드 헤더로 추적을 이어받지 않는다(continueFrom)");
            }
            // 🐛 배치 변환 헤더만 찾으면 레코드 어댑터(setBatchToRecordAdapter) 팩토리에서는 늘 null 이다 —
            //    운영에서 이어받기가 아무것도 하지 않았다(docs/51 §4g). 두 모양을 다 받는 recordHeaders 로.
            if (!code.contains("OutboxTraceContext.recordHeaders(")) {
                missing.add(listener + " — 헤더는 @Headers 전체에서 recordHeaders(...) 로 꺼낸다(레코드 어댑터면 배치 헤더가 없다)");
            }
        }
        assertThat(missing).isEmpty();
    }

    /**
     * B-60b: 모든 Kafka 리스너가 보낸 쪽의 추적을 이어받는다 (docs/51 §4h).
     *
     * <p>배치 리스너에는 스프링의 컨슈머 추적이 걸리지 않는다 — 리스너가 레코드마다 헤더에서 이어받아야 한다
     * ({@code recordHeaders(} 로 헤더를 꺼내 {@code openFrom(}/{@code continueFrom(} 으로 감싼다). 레코드 단위
     * 리스너는 팩토리의 {@code setObservationEnabled(true)} 가 이어받으므로 아래 목록에만 둔다. 새 리스너가
     * 이것을 빠뜨리면 그 리스너 안의 로그·아웃박스 행이 따로 떨어진 추적이 된다.
     */
    @Test
    @DisplayName("B-60b: 모든 @KafkaListener 는 레코드마다 추적을 이어받는다 (배치) — 레코드 단위는 팩토리 옵저베이션")
    void everyKafkaListenerContinuesTheSendersTrace() throws IOException {
        // 레코드 단위 팩토리(setBatchListener 가 없거나 false)의 리스너 — 스프링 옵저베이션이 이어받는다.
        Set<String> recordLevel = Set.of(
                "chat-service/src/main/java/com/project/chat_service/message/consumer/MessageConsumer.java",
                "coupon-service/src/main/java/com/project/coupon_service/message/consumer/UserCreatedConsumer.java");
        List<String> missing = new ArrayList<>();
        int listeners = 0;
        List<Path> sources = new ArrayList<>();
        try (var modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path src = module.resolve("src/main/java");
                if (Files.isDirectory(src)) {
                    try (var walk = Files.walk(src)) {
                        walk.filter(x -> x.toString().endsWith(".java")).forEach(sources::add);
                    }
                }
            }
        }
        {
            for (Path p : sources) {
                String code = Files.readString(p, StandardCharsets.UTF_8);
                String rel = repoRoot().relativize(p).toString().replace('\\', '/');
                if (code.contains("new ConcurrentKafkaListenerContainerFactory<>()")) {
                    int factories = code.split("new ConcurrentKafkaListenerContainerFactory<>\\(\\)", -1).length - 1;
                    int observed = code.split("setObservationEnabled\\(true\\)", -1).length - 1;
                    if (observed < factories) {
                        missing.add(rel + " — 컨테이너 팩토리 " + factories + "개 중 setObservationEnabled(true) 가 " + observed + "개");
                    }
                }
                int n = code.split("@KafkaListener\\(", -1).length - 1;
                if (n == 0 || recordLevel.contains(rel)) {
                    listeners += n;
                    continue;
                }
                listeners += n;
                int continued = code.split("OutboxTraceContext\\.recordHeaders\\(", -1).length - 1;
                if (continued < n) {
                    missing.add(rel + " — @KafkaListener " + n + "개 중 recordHeaders(...) 로 추적을 이어받는 것이 " + continued + "개");
                }
            }
        }
        assertThat(listeners).as("리스너를 하나도 못 찾았다면 경로가 바뀐 것이다").isGreaterThan(20);
        assertThat(missing).isEmpty();
    }

    /**
     * B-61: "사람이 손대야 하는 일" 의 로그 문구가 Loki 경보 정규식에 걸린다 (docs/51 §4i).
     *
     * <p>경보는 로그 문구를 정규식으로 찾는다. 누가 "수동 확인 필요" 를 "수동으로 확인 바람" 으로 바꾸면 경보가
     * <b>조용히</b> 안 온다 — 평소엔 아무 증상이 없다가 정말 필요한 날 모른다. 코드의 log.error/warn 문구 중
     * "수동" 과 "필요" 가 함께 있는 것은 전부 규칙 파일의 {@code MANUAL_ACTION_REGEX} 에 걸려야 하고, 그
     * 정규식은 규칙의 expr 에 그대로 있어야 하며, compose 가 규칙 디렉터리를 테넌트 경로에 붙여야 한다.
     */
    @Test
    @DisplayName("B-61: '수동 … 필요' 로그 문구가 전부 Loki 경보 규칙에 걸린다")
    void manualActionLogsAreAlerted() throws IOException {
        String rules = Files.readString(repoRoot().resolve("observability/loki-rules/ecommerce-log-alerts.yml"),
                StandardCharsets.UTF_8);
        Matcher declared = Pattern.compile("# MANUAL_ACTION_REGEX: (.+)").matcher(rules);
        assertThat(declared.find()).as("규칙 파일에 MANUAL_ACTION_REGEX 주석").isTrue();
        String regex = declared.group(1).trim();
        assertThat(rules).as("expr 이 같은 정규식을 쓴다").contains("|~ \"" + regex + "\"");
        Pattern alert = Pattern.compile(regex);

        List<String> unmatched = new ArrayList<>();
        int found = 0;
        Pattern logCall = Pattern.compile("log\\.(error|warn)\\(\\s*\"([^\"]*)\"");
        try (var modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (var walk = Files.walk(src)) {
                    for (Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                        Matcher m = logCall.matcher(Files.readString(p, StandardCharsets.UTF_8));
                        while (m.find()) {
                            String text = m.group(2);
                            if (text.contains("수동") && text.contains("필요")) {
                                found++;
                                if (!alert.matcher(text).find()) {
                                    unmatched.add(repoRoot().relativize(p) + ": " + text);
                                }
                            }
                        }
                    }
                }
            }
        }
        assertThat(found).as("그런 문구를 하나도 못 찾았다면 검사가 헛돈다").isGreaterThan(3);
        assertThat(unmatched).isEmpty();

        // 품절 경보는 catalog 의 StockEventProducer.SOLD_OUT_MARKER 문구로 찾는다(docs/53).
        Matcher soldOut = Pattern.compile("# SOLD_OUT_MARKER: (.+)").matcher(rules);
        assertThat(soldOut.find()).as("규칙 파일에 SOLD_OUT_MARKER 주석").isTrue();
        String marker = soldOut.group(1).trim();
        assertThat(rules).as("expr 이 같은 문구를 쓴다").contains("|= \"" + marker + "\"");
        assertThat(Files.readString(repoRoot().resolve(
                "catalog-service/src/main/java/com/project/catalog_service/message/producer/StockEventProducer.java"),
                StandardCharsets.UTF_8))
                .as("catalog 가 찍는 문구와 경보가 찾는 문구가 같다")
                .contains("SOLD_OUT_MARKER = \"" + marker + "\"");
        assertThat(Files.readString(repoRoot().resolve("docker-compose-observability.yml"), StandardCharsets.UTF_8))
                .as("규칙 디렉터리가 테넌트 경로에 붙는다").contains("./observability/loki-rules:/etc/loki/rules/fake:ro");
        assertThat(Files.readString(repoRoot().resolve("observability/loki-config.yml"), StandardCharsets.UTF_8))
                .as("ruler 가 Alertmanager 로 보낸다").contains("ruler:").contains("alertmanager_url: http://alertmanager:9093");
    }

    private static int compareVersions(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /**
     * B-62: 게이트웨이 라우트 id 는 유일하다 (docs/57 §4).
     *
     * <p>🐛 {@code user-service} 가 두 번 선언돼 있었다 — 인증 전 경로(로그인·가입)와 인증 경로. 레이트리미터
     * ({@code RedisRateLimiter})는 설정을 <b>라우트 id 로</b> 저장하므로, 같은 id 의 다른 라우트(전역 기본값 20/s)가
     * 로그인 경로의 강한 제한(1/s, 버스트 10)을 덮을 수 있었다. 무차별 대입 방어가 설정 한 줄로 사라지는 모양이고,
     * 평소에는 아무 증상이 없다.
     */
    @Test
    @DisplayName("B-62: 게이트웨이 라우트 id 가 파일 안에서 겹치지 않는다")
    void gatewayRouteIdsAreUnique() throws IOException {
        Pattern routeId = Pattern.compile("^\\s*-\\s*id:\\s*(\\S+)\\s*$");
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(repoRoot().resolve("gateway-server/src/main/resources"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml") || f.toString().endsWith(".yaml")).toList()) {
                Map<String, Integer> seen = new TreeMap<>();
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    Matcher m = routeId.matcher(line);
                    if (m.matches()) {
                        seen.merge(m.group(1), 1, Integer::sum);
                        checked++;
                    }
                }
                seen.forEach((id, n) -> {
                    if (n > 1) {
                        offenders.add(file.getFileName() + ": " + id + " × " + n);
                    }
                });
            }
        }
        assertThat(checked).as("라우트를 하나도 못 찾았다면 이 검사의 정규식이 낡은 것이다").isGreaterThan(5);
        assertThat(offenders).as("라우트 id 가 겹치면 레이트리미터 설정이 서로를 덮는다").isEmpty();
    }

    /**
     * B-64: 게이트웨이의 자동 라우트(discovery locator)는 꺼져 있다 (docs/57 §9).
     *
     * <p>🐛 켜져 있었다. Eureka 의 서비스마다 {@code /ORDER-SERVICE/**} 같은 라우트가 생기는데 JWT 필터가 없고, 전역 필터가 신원
     * 헤더를 지우므로 하위 서비스에는 "헤더 없음 = 내부 호출" 로 닿는다 — {@code InternalCallGuard.allowed} 가 통과시키는 모양이다.
     * 밖에서 닿지 않은 것은 터널이 소문자 접두사만 넘기기 때문이었다(설정 한 줄에 기댄 방어).
     */
    @Test
    @DisplayName("B-64: 게이트웨이 discovery locator 가 켜져 있지 않다")
    void gatewayDiscoveryLocatorIsOff() throws IOException {
        Pattern enabledTrue = Pattern.compile("^\\s*enabled:\\s*true\\b");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(repoRoot().resolve("gateway-server/src/main/resources"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml") || f.toString().endsWith(".yaml")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 1; i < lines.size(); i++) {
                    if (lines.get(i - 1).trim().equals("locator:") && enabledTrue.matcher(lines.get(i)).find()) {
                        offenders.add(file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(offenders).as("자동 라우트에는 JWT 필터가 없다 — 명시 라우트만 쓴다").isEmpty();
    }

    /**
     * B-65: 스프링 빈에 생성자가 둘 이상이면 하나에 {@code @Autowired} 가 있거나, 인자 없는 생성자가 있어야 한다.
     *
     * <p>🐛 {@code UserLifecycleProducer} 가 운영용·테스트용 생성자 둘을 갖고 어느 쪽에도 표시가 없었다. 스프링은 기본 생성자를
     * 찾다 "No default constructor found" 로 user-service 기동에 실패했다 — 단위 테스트는 생성자를 직접 불러 모두 초록이었고,
     * 컨텍스트 기동 테스트는 Docker 가 있어야 돌아 CI 에서 건너뛰었다. 배포하는 순간에야 드러났다(docs/57 §17).
     */
    @Test
    @DisplayName("B-65: 생성자가 여럿인 빈은 @Autowired 로 하나를 고른다")
    void beansWithSeveralConstructorsPickOne() throws IOException {
        Pattern beanAnno = Pattern.compile("@(Component|Service|Repository|Controller|RestController|Configuration)\\b");
        Pattern classDecl = Pattern.compile("\\bclass\\s+(\\w+)");
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> paths = Files.walk(repoRoot(), 12)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")
                    && p.toString().contains("/src/main/java/") && !p.toString().contains("/build/")).toList()) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                if (!beanAnno.matcher(src).find()) {
                    continue;
                }
                Matcher cls = classDecl.matcher(src);
                if (!cls.find()) {
                    continue;
                }
                String name = cls.group(1);
                checked++;
                Matcher ctor = Pattern.compile("(?m)^\\s*(?:public|protected|private)?\\s*" + name + "\\s*\\(([^)]*)\\)").matcher(src);
                int count = 0;
                boolean noArg = false;
                while (ctor.find()) {
                    count++;
                    noArg |= ctor.group(1).isBlank();
                }
                if (count < 2 || noArg) {
                    continue;
                }
                boolean marked = Pattern.compile("@(?:org\\.springframework\\.beans\\.factory\\.annotation\\.)?Autowired\\s*\\n\\s*(?:public|protected|private)?\\s*"
                        + name + "\\s*\\(").matcher(src).find();
                if (!marked) {
                    offenders.add(repoRoot().relativize(file) + " (생성자 " + count + "개)");
                }
            }
        }
        assertThat(checked).as("빈을 하나도 못 찾았다면 이 검사의 정규식이 낡은 것이다").isGreaterThan(50);
        assertThat(offenders).as("어느 생성자를 쓸지 모르면 기동에 실패한다 — 하나에 @Autowired").isEmpty();
    }

    /**
     * B-67: 서비스가 Feign 으로 부르는 내부 경로는 ConfigMap 의 경로 규칙(INTERNAL_AUTH_PATH_RULES)이 그 서비스를 허용한다.
     *
     * <p>규칙은 사람이 적는 값이다. Feign 호출을 더하거나 다른 서비스로 옮기고 규칙을 잊으면, 규칙이 enforce 인 순간 그 호출이 403 으로
     * 끊긴다 — 테스트는 모두 초록인 채로(단위 테스트는 필터를 거치지 않는다). 규칙 대상 경로(내부 전용)를 부르는 Feign 메서드마다
     * 호출하는 모듈이 가장 긴 앞부분 규칙의 목록에 있는지 본다(docs/57 §16).
     */
    @Test
    @DisplayName("B-67: Feign 이 부르는 내부 경로는 경로 규칙이 그 호출 서비스를 허용한다 — 규칙은 enforce")
    void feignInternalCallsAreAllowedByPathRules() throws IOException {
        String cm = Files.readString(repoRoot().resolve("k8s/10-configmap.yaml"), StandardCharsets.UTF_8);
        Matcher rm = Pattern.compile("INTERNAL_AUTH_PATH_RULES:\\s*\"([^\"]*)\"").matcher(cm);
        assertThat(rm.find()).as("k8s/10-configmap.yaml 에 INTERNAL_AUTH_PATH_RULES 가 있어야 한다").isTrue();
        // 규칙은 강제한다(2026-10-01). 기본값이 observe 로 돌아가면 ops/28 배포가 운영의 enforce 를 조용히 되돌린다.
        // [JA] ルールは強制する。既定値が observe に戻ると ops/28 のデプロイが運用の enforce を黙って戻す。
        assertThat(cm).as("INTERNAL_AUTH_RULES_MODE 는 enforce — 낮추려면 오버레이에서(docs/57 §16)")
                .containsPattern("(?m)^\\s*INTERNAL_AUTH_RULES_MODE:\\s*\"enforce\"");
        java.util.List<java.util.Map.Entry<String, Set<String>>> rules = new ArrayList<>();
        for (String part : rm.group(1).split(";")) {
            int eq = part.indexOf('=');
            rules.add(java.util.Map.entry(part.substring(0, eq).trim(), Set.of(part.substring(eq + 1).trim().split("\\|"))));
        }
        rules.sort((a, b) -> b.getKey().length() - a.getKey().length());

        Pattern mapping = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> paths = Files.walk(repoRoot(), 12)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/java/")
                    && !p.toString().contains("/build/")).toList()) {
                String src = stripJavaComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!src.contains("@FeignClient")) {
                    continue;
                }
                String caller = repoRoot().relativize(file).getName(0).toString();
                Matcher m = mapping.matcher(src);
                while (m.find()) {
                    String path = m.group(2).startsWith("/") ? m.group(2) : "/" + m.group(2);
                    for (var rule : rules) {
                        if (path.startsWith(rule.getKey())) {
                            checked++;
                            if (!rule.getValue().contains(caller)) {
                                offenders.add(repoRoot().relativize(file) + ": " + path + " — 규칙 " + rule.getKey() + "=" + rule.getValue());
                            }
                            break;
                        }
                    }
                }
            }
        }
        assertThat(checked).as("규칙에 걸리는 Feign 호출을 하나도 못 찾았다면 이 검사의 정규식이 낡은 것이다").isGreaterThan(5);
        assertThat(offenders).as("Feign 으로 부르는 내부 경로를 경로 규칙이 허용하지 않는다 — INTERNAL_AUTH_PATH_RULES 에 더한다").isEmpty();
    }

    /**
     * B-68: 백엔드가 마운트하는 신원 Secret(tls-id-*)마다 cert-manager Certificate(k8s/96)가 같은 이름으로 발급하고, 그 Deployment 에
     * Reloader 주석이 있다.
     *
     * <p>하나라도 빠지면: Certificate 가 없으면 그 서비스만 손으로 만든 1년짜리 인증서로 남아 어느 날 만료되고, Reloader 주석이 없으면
     * 갱신된 인증서를 파드가 읽지 않아 옛 인증서가 만료되는 날 그 서비스의 호출만 끊긴다(docs/57 §18).
     */
    @Test
    @DisplayName("B-68: 신원 Secret 마다 Certificate 와 Reloader 주석이 있다")
    void identitySecretsAreManagedAndReloaded() throws IOException {
        String certs = Files.readString(repoRoot().resolve("k8s/96-service-identities.yaml"), StandardCharsets.UTF_8);
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> paths = Files.list(repoRoot().resolve("k8s"))) {
            for (Path f : paths.filter(p -> p.getFileName().toString().matches("4\\d-.*\\.yaml")).sorted().toList()) {
                String y = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("secretName: (tls-id-[a-z-]+)").matcher(y);
                while (m.find()) {
                    checked++;
                    String name = m.group(1);
                    if (!certs.contains("secretName: " + name + "\n")) {
                        offenders.add(f.getFileName() + ": " + name + " 을 발급하는 Certificate 가 k8s/96 에 없다");
                    }
                    if (!y.contains("secret.reloader.stakater.com/reload: \"" + name + "\"")) {
                        offenders.add(f.getFileName() + ": Reloader 주석(secret.reloader.stakater.com/reload: \"" + name + "\")이 없다");
                    }
                }
            }
        }
        assertThat(checked).as("백엔드 매니페스트가 신원 Secret 을 하나도 마운트하지 않는다면 이 검사가 낡은 것이다").isGreaterThanOrEqualTo(10);
        assertThat(offenders).isEmpty();
    }

    /**
     * B-66: 장바구니 줄({@code CartProductDto})의 {@code id} 는 <b>장바구니 줄</b> id 다 — 상품 id 자리에 쓰지 않는다.
     *
     * <p>🐛 order-service 가 {@code .productId(Long.parseLong(p.getId()))} 로 주문 품목을 만들었다. 줄 id 는 TSID 라 어느 상품도
     * 가리키지 않았고, 재고 예약이 모든 주문을 "품절" 로 막았다(그 전에는 결제 뒤 차감이 전량 부족 → 자동 환불). 상품 id 는
     * {@code getProductId()} 다(docs/59 §10).
     */
    @Test
    @DisplayName("B-66: 장바구니 줄 id 를 상품 id 로 쓰지 않는다")
    void cartLineIdIsNotProductId() throws IOException {
        Pattern misuse = Pattern.compile("\\.productId\\([^;]*\\.getId\\(\\)");
        List<String> offenders = new ArrayList<>();
        for (String module : List.of("order-service", "coupon-service", "pay-service")) {
            Path root = repoRoot().resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String src = stripJavaComments(Files.readString(file, StandardCharsets.UTF_8));
                    if (!src.contains("CartProductDto")) {
                        continue;
                    }
                    Matcher m = misuse.matcher(src);
                    while (m.find()) {
                        offenders.add(repoRoot().relativize(file) + ": " + oneLine(m.group()));
                    }
                }
            }
        }
        assertThat(offenders).as("장바구니 줄 id 는 상품 id 가 아니다 — getProductId()").isEmpty();
    }

    /**
     * B-69: 모든 JPA 엔티티에 인자 없는 생성자가 있다({@code @NoArgsConstructor} 또는 직접 쓴 {@code Foo()}).
     *
     * <p>🐛 user-service 의 {@code Review} 가 {@code @SuperBuilder} 만 있고 인자 없는 생성자가 없었다. JPA 는 DB 에서 읽은 행으로
     * 엔티티를 만들 때 그 생성자를 쓴다 — 쓰기(빌더로 만들어 저장)는 되고 <b>읽기</b>가 실패한다. 단위 테스트는 엔티티를 빌더로
     * 만들어 목에 넣으므로 모두 초록이었고, 운영에서 상품에 첫 리뷰가 저장되는 순간 그 상품의 리뷰 목록이 400 이 됐다.
     */
    @Test
    @DisplayName("B-69: 모든 @Entity 에 인자 없는 생성자가 있다")
    void everyEntityHasNoArgConstructor() throws IOException {
        List<String> offenders = new ArrayList<>();
        int entities = 0;
        try (Stream<Path> paths = Files.walk(repoRoot(), 12)) {
            List<Path> files = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/node_modules/"))
                    .toList();
            for (Path file : files) {
                String src = stripJavaComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!Pattern.compile("(?m)^\\s*@Entity\\b").matcher(src).find()) {
                    continue;
                }
                entities++;
                String cls = file.getFileName().toString().replace(".java", "");
                boolean lombok = src.contains("@NoArgsConstructor");
                boolean explicit = Pattern.compile("\\b" + cls + "\\s*\\(\\s*\\)\\s*\\{").matcher(src).find();
                // 생성자를 하나도 선언하지 않았으면 자바가 기본 생성자를 만든다(chat 의 Alarm). 문제는 다른 생성자가 있을 때다 —
                // 직접 쓴 것이든 Lombok 이 만든 것이든(@Builder·@SuperBuilder·@AllArgsConstructor·@RequiredArgsConstructor).
                boolean otherCtor = Pattern.compile("@(Builder|SuperBuilder|AllArgsConstructor|RequiredArgsConstructor)\\b").matcher(src).find()
                        || Pattern.compile("(?m)^\\s*(public|protected|private)?\\s*" + cls + "\\s*\\(\\s*[^)\\s]").matcher(src).find();
                if (otherCtor && !lombok && !explicit) {
                    offenders.add(repoRoot().relativize(file).toString());
                }
            }
        }
        assertThat(entities).as("엔티티를 하나도 못 찾았다 — 경로가 바뀌었나").isGreaterThan(20);
        assertThat(offenders).as("JPA 는 읽을 때 인자 없는 생성자로 만든다 — @NoArgsConstructor(access = AccessLevel.PROTECTED)").isEmpty();
    }

    /**
     * B-70: 월 파티션 테이블을 만드는 서비스는 그 테이블을 파티션 잡({@code outbox.partitions.tables})에 등록한다.
     *
     * <p>🐛 아웃박스 파티션은 V1 마이그레이션이 2027-01 까지만 만들었고 그 뒤를 만드는 장치가 없었다 — 2027-02 부터 모든 행이
     * DEFAULT 로 가고, 에러는 나지 않는다. 잡(common {@code OutboxPartitionAutoConfiguration})을 만들었어도, 새 파티션 테이블을
     * 더하면서 설정을 빠뜨리면 같은 일이 그 테이블에서 다시 일어난다.
     */
    @Test
    @DisplayName("B-70: 파티션 테이블을 만드는 서비스는 outbox.partitions.tables 에 그 테이블을 둔다")
    void partitionedTablesAreMaintained() throws IOException {
        Pattern rename = Pattern.compile("ALTER\\s+TABLE\\s+(\\w+)\\.(\\w+)_p\\s+RENAME\\s+TO\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        Pattern tablesLine = Pattern.compile("(?m)^\\s*tables:\\s*(\\S.*)$");
        List<String> offenders = new ArrayList<>();
        int found = 0;
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path migrations = module.resolve("src/main/resources/db/migration");
                if (!Files.isDirectory(migrations)) {
                    continue;
                }
                List<String> partitioned = new ArrayList<>();
                try (Stream<Path> files = Files.list(migrations)) {
                    for (Path f : files.filter(x -> x.toString().endsWith(".sql")).toList()) {
                        String sql = Files.readString(f, StandardCharsets.UTF_8);
                        if (!sql.toUpperCase().contains("PARTITION BY RANGE")) {
                            continue;
                        }
                        Matcher m = rename.matcher(sql);
                        while (m.find()) {
                            partitioned.add(m.group(1) + "." + m.group(3));
                        }
                    }
                }
                if (partitioned.isEmpty()) {
                    continue;
                }
                found += partitioned.size();
                Path yml = module.resolve("src/main/resources/application-local.yml");
                String text = Files.exists(yml) ? Files.readString(yml, StandardCharsets.UTF_8) : "";
                int at = text.indexOf("partitions:");
                Matcher t = tablesLine.matcher(at < 0 ? "" : text.substring(at));
                List<String> configured = t.find()
                        ? Arrays.stream(t.group(1).split(",")).map(String::trim).toList()
                        : List.of();
                for (String table : partitioned) {
                    if (!configured.contains(table)) {
                        offenders.add(module.getFileName() + " → " + table);
                    }
                }
            }
        }
        assertThat(found).as("파티션 테이블을 하나도 못 찾았다 — 마이그레이션 모양이 바뀌었나").isGreaterThanOrEqualTo(4);
        assertThat(offenders).as("outbox.partitions.tables 에 없으면 V1 이 만든 마지막 달 뒤로는 DEFAULT 로 간다").isEmpty();
    }

    /**
     * B-63: 한 서비스의 Flyway 버전 번호는 겹치지 않는다.
     *
     * <p>🐛 catalog 에 {@code V10__home_section.sql} 이 이미 있는데 재고 부족 아웃박스를 {@code V10__stock_shortage_outbox.sql}
     * 로 더했다(49a10fa). Flyway 는 같은 버전이 둘이면 <b>기동을 거부한다</b>("Found more than one migration with version 10") —
     * 단위 테스트는 Flyway 를 돌리지 않아 모두 초록이었고, 배포하는 순간 catalog 가 뜨지 않는다. V13 으로 옮겼다(docs/59 §0).
     */
    @Test
    @DisplayName("B-63: 서비스마다 Flyway 마이그레이션 버전이 유일하다")
    void flywayVersionsAreUnique() throws IOException {
        Pattern version = Pattern.compile("^V([0-9][0-9_.]*)__.+\\.sql$");
        List<String> offenders = new ArrayList<>();
        int dirs = 0;
        try (Stream<Path> paths = Files.walk(repoRoot(), 6)) {
            List<Path> migrationDirs = paths
                    .filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Path.of("src", "main", "resources", "db", "migration")))
                    .filter(p -> !p.toString().contains("/build/"))
                    .toList();
            for (Path dir : migrationDirs) {
                dirs++;
                Map<String, List<String>> byVersion = new TreeMap<>();
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : files.toList()) {
                        Matcher m = version.matcher(f.getFileName().toString());
                        if (m.matches()) {
                            // Flyway 는 1_1 과 1.1 을 같은 버전으로 본다.
                            String v = m.group(1).replace('_', '.').replaceAll("(\\.0+)+$", "");
                            byVersion.computeIfAbsent(v, k -> new ArrayList<>()).add(f.getFileName().toString());
                        }
                    }
                }
                byVersion.forEach((v, names) -> {
                    if (names.size() > 1) {
                        offenders.add(repoRoot().relativize(dir) + " V" + v + " → " + names);
                    }
                });
            }
        }
        assertThat(dirs).as("마이그레이션 디렉터리를 못 찾았다면 이 검사가 낡은 것이다").isGreaterThan(5);
        assertThat(offenders).as("같은 버전이 둘이면 Flyway 가 기동을 거부한다").isEmpty();
    }
}
