package com.project.userservice.message.producer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 프로필 변경·탈퇴를 복제본을 가진 서비스에 알린다 — {@code user-updated}, {@code user-deleted} (docs/48 §6-⑤).
 *
 * <p>{@code user-created} 만 있던 동안 복제본은 <b>가입 순간의 값에서 멈춰 있었다</b>: 닉네임·프로필 사진을 바꿔도
 * 채팅에는 옛 값이 보였고, 탈퇴해 원본을 익명화해도 chat 의 복제본에는 이메일이 남았다.
 *
 * <p>아웃박스 릴레이({@code UserOutboxScheduler})가 부른다 — 그래서 <b>동기</b>로 기다려 결과를 돌려준다(행의 상태를
 * 확정해야 한다). 페이로드는 JSON 문자열이고 받는 쪽은 필드 이름으로 읽는다(알림 이벤트와 같은 모양).
 * 트랜잭션 프로듀서가 아니다 — 릴레이의 DB 트랜잭션과 묶을 이유가 없다(행 상태가 곧 발행 기록이다).
 *
 * <p>[JA] プロフィール変更・退会を、複製を持つサービスに通知する — {@code user-updated}, {@code user-deleted} (docs/48 §6-⑤)。
 * {@code user-created} しかなかった間、複製は登録時点の値で止まっていた: ニックネーム・プロフィール写真を変えても
 * チャットには古い値が見え、退会して原本を匿名化しても chat の複製にはメールアドレスが残った。
 * アウトボックスリレー({@code UserOutboxScheduler})が呼ぶ — だから同期で待って結果を返す(行の状態を確定する必要がある)。
 * ペイロードは JSON 文字列で、受け手はフィールド名で読む(通知イベントと同じ形)。トランザクショナルプロデューサーではない —
 * リレーの DB トランザクションと束ねる理由がない(行の状態がそのまま発行記録である)。
 */
@Slf4j
@Component
public class UserLifecycleProducer {

    public static final String UPDATED = "user-updated";
    public static final String DELETED = "user-deleted";

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.outbox.publish-timeout-ms:10000}")
    private long publishTimeoutMs = 10_000;

    /**
     * 🐛 {@code @Autowired} 가 없었다. 생성자가 둘(이것과 테스트용)이면 스프링은 어느 것을 쓸지 모르고 기본 생성자를 찾다
     * <b>"No default constructor found"</b> 로 기동에 실패한다 — 단위 테스트는 생성자를 직접 불러 잡지 못했다(2026-09-26 배포에서
     * user-service 가 기동하지 못한 원인). 가드 B-65 가 다시 막는다.
     *
     * <p>[JA] 🐛 {@code @Autowired} がなかった。コンストラクタが二つ(これとテスト用)あると Spring はどちらを使うか分からず、
     * デフォルトコンストラクタを探して "No default constructor found" で起動に失敗する — 単体テストはコンストラクタを
     * 直接呼ぶので捕まえられなかった(2026-09-26 のデプロイで user-service が起動できなかった原因)。ガード B-65 が再発を防ぐ。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public UserLifecycleProducer(@Qualifier("producerConfig") Map<String, Object> baseProducerConfig,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistry) {
        Map<String, Object> config = new HashMap<>(baseProducerConfig);
        config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        this.kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config,
                new StringSerializer(), new StringSerializer()));
        // 빈이 아닌 템플릿이라 공통 후처리기가 닿지 않는다 — 추적을 직접 켠다(docs/51 §4d).
        // [JA] Bean ではないテンプレートなので共通の後処理が届かない — トレースを直接有効にする(docs/51 §4d)。
        observationRegistry.ifAvailable(registry -> {
            this.kafkaTemplate.setObservationRegistry(registry);
            this.kafkaTemplate.setObservationEnabled(true);
        });
    }

    /** 테스트용 — 템플릿을 직접 넣는다. */
    // [JA] テスト用 — テンプレートを直接入れる。
    UserLifecycleProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** 키 = 사용자 id — 같은 사용자의 created·updated·deleted 가 한 파티션에서 순서대로 읽힌다. */
    // [JA] キー = ユーザー id — 同じユーザーの created・updated・deleted が一つのパーティションで順番に読まれる。
    public void publish(String topic, String userId, String json) {
        try {
            send(topic, userId, json).get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("{} 발행: userId={}", topic, userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(topic + " 발행이 중단됐다: userId=" + userId, e);
        } catch (Exception e) {
            // 이름·이미지 값은 남기지 않는다. userId 로 충분히 추적된다.
            // [JA] 名前・画像の値は残さない。userId で十分に追跡できる。
            throw new IllegalStateException(topic + " 발행 실패: userId=" + userId, e);
        }
    }

    /**
     * 보내기만 하고 결과(ack)는 돌려준다 — 아웃박스 폴러가 묶음을 모두 보낸 뒤 함께 기다린다(UserCreatedProducer#send 와 같다).
     *
     * <p>[JA] 送るだけで結果(ack)を返す — アウトボックスのポーラーがバッチをすべて送ってからまとめて待つ。
     */
    public CompletableFuture<?> send(String topic, String userId, String json) {
        return kafkaTemplate.send(topic, userId, json);
    }
}
