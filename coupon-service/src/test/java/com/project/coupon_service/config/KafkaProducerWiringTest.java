package com.project.coupon_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.coupon_service.message.producer.CouponIssuedProducer;

/**
 * 🐛 <b>coupon-service 가 운영에서 기동에 실패했다.</b>
 *
 * <pre>
 * Parameter 0 of constructor in …CouponIssuedProducer required a bean of type
 * 'org.springframework.kafka.core.KafkaTemplate' that could not be found.
 * </pre>
 *
 * <p>{@code CouponIssuedProducer} 는 JSON 문자열을 발행하므로
 * {@code KafkaTemplate<String, String>} 을 주입받는데, 설정에는 Avro 용
 * {@code KafkaTemplate<String, CouponUseRequest>} 하나뿐이었다. 스프링은 제네릭까지 보고
 * 후보를 거르므로 맞는 빈이 하나도 없다고 판단한다. 부트의 자동 설정도 도와주지 않는다 —
 * {@code KafkaAutoConfiguration} 은 사용자가 정의한 {@code KafkaTemplate} 이 하나라도 있으면
 * 통째로 물러난다({@code @ConditionalOnMissingBean}).
 *
 * <p><b>왜 기존 테스트가 못 잡았나.</b> 단위 테스트는 {@code KafkaTemplate} 을 목으로 넣으니
 * 배선을 검증하지 않는다. 배선을 검증하는 {@code CouponServiceApplicationTests} 는
 * {@code @Tag("containers")} 라 {@code ./gradlew test} 에서 <b>제외된다</b>(Docker 없는
 * 환경을 위한 의도된 분리다). 그래서 컴파일도 테스트도 전부 통과한 채로 배포됐고,
 * 파드가 CrashLoopBackOff 에 들어가서야 드러났다.
 *
 * <p>이 테스트는 그 틈을 메운다. {@link ApplicationContextRunner} 는 <b>브로커도 Docker 도
 * 없이</b> 설정 클래스만으로 컨텍스트를 띄운다 — {@code DefaultKafkaProducerFactory} 는
 * 생성 시점에 접속하지 않기 때문에 가능하다.
 *
 * <p>[JA] 🐛 <b>coupon-service が運用で起動に失敗した。</b>{@code CouponIssuedProducer} は JSON 文字列を発行するので {@code KafkaTemplate<String, String>} を注入されるが、設定には Avro 用の {@code KafkaTemplate<String, CouponUseRequest>} 一つしかなかった。Spring はジェネリクスまで見て候補を絞るので合う Bean が一つもないと判断する。Boot の自動設定も助けない — {@code KafkaAutoConfiguration} はユーザー定義の {@code KafkaTemplate} が一つでもあれば丸ごと退く({@code @ConditionalOnMissingBean})。
 * <p><b>なぜ既存のテストが捕まえられなかったか。</b>単体テストは {@code KafkaTemplate} をモックで入れるので配線を検証しない。配線を検証する {@code CouponServiceApplicationTests} は {@code @Tag("containers")} なので {@code ./gradlew test} から<b>除外される</b>(Docker のない環境のための意図した分離)。そのためコンパイルもテストもすべて通ったままデプロイされ、ポッドが CrashLoopBackOff に入って初めて分かった。
 * <p>このテストはその隙間を埋める。{@link ApplicationContextRunner} は<b>ブローカーも Docker もなしで</b>設定クラスだけでコンテキストを起動する — {@code DefaultKafkaProducerFactory} は生成時点で接続しないので可能だ。
 */
@DisplayName("coupon-service Kafka 프로듀서 배선")
class KafkaProducerWiringTest {

    /** 실제 설정과 같은 조건: kafka.url 은 기본값이 없어 반드시 주어져야 한다. */
    // [JA] 実際の設定と同じ条件: kafka.url はデフォルト値がないので必ず与えなければならない。
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("kafka.url=localhost:9092");

    /**
     * 🔴 이 파일의 핵심. 운영에서 난 실패를 그대로 재현한다 — 빈이 없으면 여기서 터진다.
     *
     * <p>[JA] 🔴 このファイルの核心。運用で起きた失敗をそのまま再現する — Bean がなければここで落ちる。
     */
    @Test
    @DisplayName("🔴 CouponIssuedProducer 가 실제로 주입된다 — 운영 기동 실패의 재현")
    void couponIssuedProducerWiresUp() {
        runner.withUserConfiguration(KafkaProducerConfig.class, CouponIssuedProducer.class)
                .withBean(ObjectMapper.class)
                .run(context -> {
                    assertThat(context)
                            .as("빈이 없으면 파드가 CrashLoopBackOff 로 들어간다")
                            .hasNotFailed();
                    assertThat(context).hasSingleBean(CouponIssuedProducer.class);
                });
    }

    /**
     * 제네릭이 맞아야 한다. {@code KafkaTemplate} 이 하나 있다고 되는 것이 아니다 —
     * 스프링은 {@code <String, String>} 과 {@code <String, CouponUseRequest>} 를 다른 타입으로
     * 본다. 바로 그것 때문에 Avro 템플릿이 있어도 "빈을 찾을 수 없다" 가 됐다.
     *
     * <p>[JA] ジェネリクスが合わなければならない。{@code KafkaTemplate} が一つあればよいわけではない — Spring は {@code <String, String>} と {@code <String, CouponUseRequest>} を別の型とみなす。まさにそのせいで Avro テンプレートがあっても「Bean が見つからない」になった。
     */
    @Test
    @DisplayName("문자열 값을 쓰는 KafkaTemplate 이 해결된다 — Avro 템플릿으로는 대체되지 않는다")
    void stringKafkaTemplateIsResolvable() {
        runner.withUserConfiguration(KafkaProducerConfig.class).run(context -> {
            assertThat(context).hasNotFailed();

            ResolvableType stringTemplate = ResolvableType.forClassWithGenerics(
                    KafkaTemplate.class, String.class, String.class);

            assertThat(context.getBeanProvider(stringTemplate).getIfAvailable())
                    .as("KafkaTemplate<String, String> 을 주입받을 수 있어야 한다")
                    .isNotNull();
        });
    }

    /**
     * 값 직렬화가 {@code JsonSerializer} 면 이미 JSON 인 문자열이 <b>한 번 더 따옴표로
     * 감싸져</b> 나간다. 컨슈머(notification-service)는 그것을 파싱하지 못하고, 알림은 조용히
     * 사라진다 — 기동은 멀쩡하므로 더 찾기 어렵다.
     *
     * <p>[JA] 値のシリアライザーが {@code JsonSerializer} だと、既に JSON の文字列が<b>もう一度引用符で包まれて</b>出ていく。コンシューマー(notification-service)はそれをパースできず通知は静かに消える — 起動は正常なのでより見つけにくい。
     */
    @Test
    @DisplayName("문자열 템플릿의 값 직렬화가 StringSerializer 다 — JSON 이 이중 인코딩되면 안 된다")
    void stringTemplateUsesStringSerializer() {
        runner.withUserConfiguration(KafkaProducerConfig.class).run(context -> {
            KafkaTemplate<?, ?> template =
                    (KafkaTemplate<?, ?>) context.getBean("couponEventKafkaTemplate");

            assertThat(template.getProducerFactory().getValueSerializer())
                    .isInstanceOf(org.apache.kafka.common.serialization.StringSerializer.class);
        });
    }

    /** Avro 경로는 그대로 살아 있어야 한다 — 문자열 템플릿을 더하면서 깨뜨리지 않았는지. */
    // [JA] Avro の経路はそのまま生きていなければならない — 文字列テンプレートを足すときに壊していないか。
    @Test
    @DisplayName("쿠폰 사용(Avro) 템플릿은 그대로 있다")
    void avroTemplateStillExists() {
        runner.withUserConfiguration(KafkaProducerConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("couponUseKafkaTemplate");
        });
    }
}
