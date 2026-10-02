package com.project.userservice.message.producer;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.userservice.model.KnownDevice;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 새 기기 로그인을 알린다 — {@code SECURITY_ALERT}.
 *
 * <p>🐛 <b>이 알림은 만들어지는 경로가 없었다.</b> 메일 제목({@code MailTemplates})·
 * SMS 문구({@code SmsTemplates})·메일 종류 매핑({@code MailKind})은 물론이고, 수신 설정에
 * <b>"보안 알림은 사용자가 꺼도 보낸다"</b>는 예외까지 들어가 있었다
 * ({@code NotificationPreference.shouldSendSms}). 전부 갖춰 놓고 만드는 쪽만 없었다.
 *
 * <p><b>값을 JSON 문자열로 보낸다.</b> 이 서비스의 다른 프로듀서는 Avro 를 쓰지만(사가
 * 경로라 스키마가 계약이다), 알림 서비스는 이벤트를 {@code JsonNode} 로 느슨하게 읽는다
 * — 알림 하나 때문에 모든 도메인의 스키마 변경에 끌려다니지 않기 위해서다
 * ({@code NotificationEventListener} 주석). 공지·기획전 알림도 같은 모양이다.
 *
 * <p><b>발행 실패가 로그인을 되돌리지 않는다.</b> 로그인은 이미 성공했고 그것이 진실이다.
 * 알림을 못 보냈다고 로그인을 실패시키면, 카프카가 잠깐 안 뜨는 동안 아무도 로그인하지
 * 못한다 — 보안 알림을 붙이려다 서비스를 멈추는 것이다.
 */
@Slf4j
@Component
public class SecurityAlertProducer {

    public static final String TOPIC = "security-alert";

    private final DefaultKafkaProducerFactory<String, String> factory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SecurityAlertProducer(@Qualifier("producerConfig") Map<String, Object> baseProducerConfig,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistry) {
        Map<String, Object> config = new HashMap<>(baseProducerConfig);

        // ⚠️ 트랜잭션 ID 를 뺀다. 기본 프로듀서는 사가용이라 트랜잭셔널인데, 트랜잭셔널
        //    프로듀서로 트랜잭션 밖에서 send() 하면 "No transaction is in process" 로 죽는다
        //    (OutgoingMailProducer 가 같은 이유로 같은 처리를 한다).
        config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");

        // 로그인 응답 경로에서 불린다. 여기서 오래 붙잡히면 로그인이 그만큼 느려진다 —
        // 브로커가 안 뜰 때 사용자가 기다리는 시간이다.
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);

        this.factory = new DefaultKafkaProducerFactory<>(config,
                new StringSerializer(), new StringSerializer());
        this.kafkaTemplate = new KafkaTemplate<>(factory);
        // 빈이 아닌 템플릿이라 공통 후처리기(KafkaTemplateObservationPostProcessor)가 닿지 않는다.
        // 레지스트리를 직접 넣어야 로그인 요청의 추적이 알림 서비스까지 이어진다(docs/51 §4d).
        observationRegistry.ifAvailable(registry -> {
            this.kafkaTemplate.setObservationRegistry(registry);
            this.kafkaTemplate.setObservationEnabled(true);
        });
    }

    /**
     * 새 기기 로그인을 발행한다.
     *
     * @param email 알림 서비스가 메일을 보낼 주소. 이 서비스만 알고 있다.
     */
    public void publishNewDevice(Long userId, String email, KnownDevice device) {
        if (userId == null || device == null) {
            return;
        }

        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("customerId", userId);
            event.put("email", email);
            event.put("deviceLabel", device.getLabel());
            // 마스킹된 값이다(DeviceIdentity.maskIp) — 원문 IP 는 저장도 발행도 하지 않는다.
            event.put("maskedIp", device.getLastIp());
            event.put("occurredAt", LocalDateTime.now().toString());
            // 기기 하나당 알림 하나. 재전달돼도 sourceEventId 가 UNIQUE 라 중복되지 않는다.
            event.put("eventId", "security:device:" + device.getDeviceId());

            kafkaTemplate.send(TOPIC, String.valueOf(userId), objectMapper.writeValueAsString(event));
            // ⚠️ 이메일과 IP 는 로그에 남기지 않는다. 사실만 남긴다.
            log.info("새 기기 로그인 알림 발행: userId={}, deviceId={}", userId, device.getDeviceId());

        } catch (Exception e) {
            // 로그인은 이미 성공했다. 알림만 못 나간 것이라 되돌리지 않는다.
            log.error("새 기기 로그인 알림을 발행하지 못했습니다: userId={} ({})", userId, e.getMessage());
        }
    }

    /**
     * 리프레시 토큰 재사용으로 그 로그인 묶음을 끊었다고 알린다(docs/68).
     *
     * <p>받는 사람은 <b>계정 주인</b>이다 — 재사용을 일으킨 요청이 도둑인지 주인인지 서버는 모르지만, 메일 주소는 주인의 것이다.
     * 주인이 "로그인이 풀렸다" 를 이상하게 여기고 비밀번호를 바꾸는 것이 이 알림의 목적이다.
     *
     * <p>{@code eventId} 는 묶음 id 의 해시다. 묶음은 한 번만 끊기므로 알림도 하나 — 동시에 두 요청이 재사용으로 걸려도,
     * 카프카가 재전달해도 하나다. 묶음 id 원문은 싣지 않는다(토큰의 클레임이다).
     *
     * <p>로그인 때와 같이 <b>발행 실패가 응답을 바꾸지 않는다</b> — 묶음은 이미 끊겼고 그것이 진실이다.
     */
    // [JA] リフレッシュトークン再利用でログインのファミリーを切ったことを知らせる。宛先はアカウントの持ち主。
    //      eventId はファミリー id のハッシュ(一度だけ切られるので通知も一つ)。発行失敗は応答を変えない。
    public void publishRefreshReuse(Long userId, String email, String familyId) {
        if (userId == null || familyId == null) {
            return;
        }

        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("alert", ALERT_REFRESH_REUSE);
            event.put("customerId", userId);
            event.put("email", email);
            event.put("occurredAt", LocalDateTime.now().toString());
            event.put("eventId", "security:refresh-reuse:" + familyDigest(familyId));

            kafkaTemplate.send(TOPIC, String.valueOf(userId), objectMapper.writeValueAsString(event));
            log.info("로그인 해제(토큰 재사용) 알림 발행: userId={}", userId);

        } catch (Exception e) {
            log.error("로그인 해제 알림을 발행하지 못했습니다: userId={} ({})", userId, e.getMessage());
        }
    }

    /** {@code security-alert} 의 사건 구분 — notification-service 가 문구를 고른다. 없으면 새 기기 로그인. */
    public static final String ALERT_REFRESH_REUSE = "refresh-reuse";
    public static final String ALERT_2FA_ENABLED = "2fa-enabled";
    public static final String ALERT_2FA_DISABLED = "2fa-disabled";
    /** 비밀번호는 맞았는데 2FA 코드가 창 안에서 한도만큼 틀렸다 — 비밀번호가 샜다는 신호(docs/68 §15). */
    public static final String ALERT_2FA_FAILURES = "2fa-failures";
    /** 비밀번호 찾기로 비밀번호를 새로 정했다(docs/68 §15). */
    public static final String ALERT_PASSWORD_RESET = "password-reset";
    /** 소셜 로그인이 처음으로 이 계정에 연결됐다(docs/68 §15). provider 를 싣는다. */
    public static final String ALERT_SOCIAL_LINKED = "social-linked";
    /** 소셜 로그인이 이메일 소유를 증명해, 확인된 적 없는 비밀번호를 해제했다(사전 탈취 방어, docs/68 §15). */
    public static final String ALERT_UNPROVEN_PASSWORD_CLEARED = "unproven-password-cleared";

    /**
     * 2단계 인증을 켜거나 껐다고 알린다(docs/68 §14). 본인이 한 일이 아니면 세션을 누군가 쓰고 있다는 신호다 — 수신 설정과 무관하게 나간다.
     * 발행 실패는 응답을 바꾸지 않는다(변경은 이미 저장됐다).
     */
    // [JA] 2 段階認証を有効・無効にしたことを知らせる。本人でなければ誰かがセッションを使っている合図。発行失敗は応答を変えない。
    public void publishTwoFactorChanged(Long userId, String email, boolean enabled) {
        if (userId == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("alert", enabled ? ALERT_2FA_ENABLED : ALERT_2FA_DISABLED);
            event.put("customerId", userId);
            event.put("email", email);
            event.put("occurredAt", LocalDateTime.now().toString());
            event.put("eventId", "security:2fa:" + userId + ":" + (enabled ? "on" : "off") + ":" + System.currentTimeMillis());
            kafkaTemplate.send(TOPIC, String.valueOf(userId), objectMapper.writeValueAsString(event));
            log.info("2단계 인증 {} 알림 발행: userId={}", enabled ? "켜기" : "끄기", userId);
        } catch (Exception e) {
            log.error("2단계 인증 변경 알림을 발행하지 못했습니다: userId={} ({})", userId, e.getMessage());
        }
    }

    /**
     * 사건 하나를 알린다 — 2FA 실패 한도 · 비밀번호 재설정 · 소셜 연결 · 미확인 비밀번호 해제(docs/68 §15).
     *
     * <p>싣는 것은 종류 · userId · 주소 · (소셜이면) 프로바이더 이름뿐 — 코드 · 토큰 · IP 는 싣지 않는다. 발행 실패는 응답을 바꾸지 않는다.
     */
    // [JA] 出来事を一つ知らせる。載せるのは種類 · userId · アドレス · (ソーシャルなら)プロバイダー名だけ。発行失敗は応答を変えない。
    public void publishAccountEvent(Long userId, String email, String alert, String provider) {
        if (userId == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("alert", alert);
            event.put("customerId", userId);
            event.put("email", email);
            if (provider != null) {
                event.put("provider", provider);
            }
            event.put("occurredAt", LocalDateTime.now().toString());
            event.put("eventId", "security:" + alert + ":" + userId + ":" + System.currentTimeMillis());
            kafkaTemplate.send(TOPIC, String.valueOf(userId), objectMapper.writeValueAsString(event));
            log.info("보안 알림 발행: alert={}, userId={}", alert, userId);
        } catch (Exception e) {
            log.error("보안 알림을 발행하지 못했습니다: alert={}, userId={} ({})", alert, userId, e.getMessage());
        }
    }

    static String familyDigest(String familyId) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(familyId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 은 모든 JVM 에 있다
        }
    }

    /** 자체 프로듀서 팩토리를 만들었으므로 직접 닫는다. 안 닫으면 종료 시 스레드가 남는다. */
    @PreDestroy
    public void close() {
        factory.destroy();
    }
}
