package com.project.notification_service.listener;

import com.project.notification_service.service.NotificationTexts;
import java.util.Map;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.service.NotificationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 업무 이벤트를 알림으로 바꾼다.
 *
 * <p><b>왜 JsonNode 로 받나.</b> 이 서비스는 주문·결제·채팅·쿠폰의 이벤트를 모두 듣는데,
 * 각 DTO 를 그대로 의존하면 알림 서비스가 <b>모든 도메인의 스키마 변경에 끌려다닌다.</b>
 * 주문 DTO 에 필드 하나가 늘면 알림 서비스가 역직렬화에 실패해 죽는 식이다. 알림에 필요한 것은
 * "누구에게, 무슨 일이"뿐이므로 필요한 필드만 꺼내 읽는다. 없으면 기본값으로 넘어간다 —
 * <b>알림 하나 때문에 컨슈머가 멈추는 것이 더 나쁘다.</b>
 *
 * <p><b>멱등성.</b> 이벤트마다 안정적인 키를 만들어 {@code sourceEventId} 로 넘긴다.
 * 그 컬럼이 UNIQUE 라 재전달이 와도 알림은 하나만 생긴다(NotificationService).
 * Kafka 는 at-least-once 이므로 이 방어가 없으면 재전달마다 문자가 한 통씩 더 나간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    /** {@code security-alert} 의 {@code alert} 값 — 리프레시 토큰 재사용으로 로그인이 끊겼다(user-service SecurityAlertProducer). */
    static final String ALERT_REFRESH_REUSE = "refresh-reuse";
    static final String ALERT_2FA_ENABLED = "2fa-enabled";
    static final String ALERT_2FA_DISABLED = "2fa-disabled";
    // 로그인 보안 2차(user-service docs/68 §15) — SecurityAlertProducer 의 같은 이름 상수와 값이 같아야 한다.
    static final String ALERT_2FA_FAILURES = "2fa-failures";
    static final String ALERT_PASSWORD_RESET = "password-reset";
    static final String ALERT_SOCIAL_LINKED = "social-linked";
    static final String ALERT_UNPROVEN_PASSWORD_CLEARED = "unproven-password-cleared";

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    /** 레코드 헤더의 traceparent 로 보낸 쪽의 추적을 이어받는다(docs/51 §4h). */
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    /** 결제 응답 — 성공/실패에 따라 알림 종류가 갈린다. */
    @KafkaListener(id = "notification-payment", topics = "payment",
                   containerFactory = "notificationListenerContainerFactory")
    public void onPayment(java.util.List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive payment")) {
                JsonNode event = objectMapper.readTree(messages.get(i));
                Long userId = longOf(event, "customerId", "userId");
                String orderId = textOf(event, "orderId");
                if (userId == null) {
                    // 수신자를 모르면 알림을 만들 수 없다. 버리되 조용히 버리지는 않는다.
                    log.warn("결제 이벤트에 수신자가 없어 알림을 만들지 못함: {}", abbreviate(messages.get(i)));
                    continue;
                }
                String status = textOf(event, "paymentStatus");
                String upper = status == null ? "" : status.toUpperCase();

                // 🐛 예전에는 "FAIL 이 아니면 전부 결제 완료" 였다. 환불(CANCELED)도 "결제 완료" 로
                //    분류됐고, 중복 키까지 같은 주문의 결제 완료와 겹쳐 아무것도 만들어지지 않았다.
                //    (그 전에 pay-service 가 환불 요청을 중복으로 오인해 응답 자체를 안 보냈다 —
                //    PaymentService.publishIfOutboxEventProcessedForPayment 주석.)
                NotificationType type;
                String title;
                String body;
                if (upper.contains("FAIL")) {
                    type = NotificationType.PAYMENT_FAILED;
                    title = NotificationTexts.PAYMENT_FAILED_TITLE;
                    body = NotificationTexts.paymentFailed(orderId);
                } else if (upper.contains("CANCEL") || upper.contains("REFUND")) {
                    type = NotificationType.PAYMENT_REFUNDED;
                    title = NotificationTexts.PAYMENT_REFUNDED_TITLE;
                    body = NotificationTexts.paymentRefunded(orderId);
                } else {
                    type = NotificationType.PAYMENT_COMPLETED;
                    title = NotificationTexts.PAYMENT_COMPLETED_TITLE;
                    body = NotificationTexts.paymentCompleted(orderId);
                }

                notificationService.create(
                        userId, type, title, body,
                        type.path() + orderId,
                        "payment:" + orderId + ":" + status,
                        Map.of("subject", NotificationTexts.orderSubject(orderId)));

            } catch (Exception e) {
                // 이 레코드만 DLT 로 보내고 나머지 배치는 계속 처리한다(docs/11 §3).
                throw new BatchListenerFailedException("payment 알림 처리 실패", e, i);
            }
        }
    }

    /** 주문 접수. */
    @KafkaListener(id = "notification-order", topics = "order",
                   containerFactory = "notificationListenerContainerFactory")
    public void onOrder(java.util.List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive order")) {
                JsonNode event = objectMapper.readTree(messages.get(i));
                Long userId = longOf(event, "customerId", "userId");
                String orderId = textOf(event, "orderId");
                if (userId == null) {
                    continue;
                }
                notificationService.create(
                        userId, NotificationType.ORDER_PLACED,
                        NotificationTexts.ORDER_PLACED_TITLE, NotificationTexts.orderPlaced(orderId),
                        NotificationType.ORDER_PLACED.path() + orderId,
                        "order:" + orderId,
                        Map.of("subject", NotificationTexts.orderSubject(orderId)));
            } catch (Exception e) {
                throw new BatchListenerFailedException("order 알림 처리 실패", e, i);
            }
        }
    }

    /**
     * 고객센터 상담원의 답변(docs/58 §10) — chat-service 가 {@code support-reply} 로 보낸다.
     *
     * <p>본문은 오지 않는다 — "답이 왔다" 만 알리고, 내용은 본인만 여는 상담 화면(/support)에서 읽는다. 알림
     * 테이블·메일에 상담 내용이 남지 않게. 종류는 {@link NotificationType#CHAT_MESSAGE} 를 쓴다 — 고객의 "채팅
     * 알림" 설정을 따르고, SMS 는 나가지 않는다. chat-service 가 이미 걸렀다: 보고 있는 고객·같은 상담 60초 안의
     * 연속 답변에는 오지 않는다.
     */
    @KafkaListener(id = "notification-support-reply", topics = "support-reply",
                   containerFactory = "notificationListenerContainerFactory")
    public void onSupportReply(java.util.List<String> messages,
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive support-reply")) {
                JsonNode event = objectMapper.readTree(messages.get(i));
                Long receiverId = longOf(event, "receiverId");
                String messageId = textOf(event, "messageId");
                String threadId = textOf(event, "threadId");
                if (receiverId == null || (messageId == null && threadId == null)) {
                    continue;
                }
                notificationService.create(
                        receiverId, NotificationType.CHAT_MESSAGE,
                        NotificationTexts.SUPPORT_REPLY_TITLE, NotificationTexts.SUPPORT_REPLY_BODY,
                        "support",
                        "support-reply:" + (messageId != null ? messageId : threadId + ":" + receiverId),
                        Map.of());
            } catch (Exception e) {
                throw new BatchListenerFailedException("support-reply 알림 처리 실패", e, i);
            }
        }
    }

    /**
     * 채팅 메시지.
     *
     * <p>SMS 로 보내지 않는다({@link NotificationType#CHAT_MESSAGE} 의 smsWorthy=false).
     * 대화 한 번에 문자 수십 통이 나가면 사용자에게도 우리에게도 재앙이다.
     */
    @KafkaListener(id = "notification-chat", topics = "chat",
                   containerFactory = "notificationListenerContainerFactory")
    public void onChat(java.util.List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive chat")) {
                JsonNode event = objectMapper.readTree(messages.get(i));
                Long receiverId = longOf(event, "receiverId", "toUserId");
                String roomId = textOf(event, "roomId", "chatRoomId");
                String senderName = textOf(event, "senderName", "nickname");
                String messageId = textOf(event, "messageId", "id");
                if (receiverId == null) {
                    continue;
                }
                notificationService.create(
                        receiverId, NotificationType.CHAT_MESSAGE,
                        NotificationTexts.chatTitle(senderName),
                        NotificationTexts.CHAT_BODY,
                        NotificationType.CHAT_MESSAGE.path() + roomId,
                        // messageId 가 없으면 방+수신자로 대체한다. 완벽하진 않지만
                        // 없는 것보다 낫다 — 최소한 같은 메시지의 재전달은 걸러진다.
                        "chat:" + (messageId != null ? messageId : roomId + ":" + receiverId),
                        Map.of());
            } catch (Exception e) {
                throw new BatchListenerFailedException("chat 알림 처리 실패", e, i);
            }
        }
    }

    /**
     * 친구 접속.
     *
     * <p>SMS 대상이 아니다. 접속 알림은 <b>지금 이 순간</b>에만 의미가 있어서, 문자가 몇 분 뒤
     * 도착하면 이미 쓸모없다. 앱 안에서만 보여 준다.
     */
    @KafkaListener(id = "notification-presence", topics = "user-presence",
                   containerFactory = "notificationListenerContainerFactory")
    public void onPresence(java.util.List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive user-presence")) {
                JsonNode event = objectMapper.readTree(messages.get(i));
                Long watcherId = longOf(event, "watcherId", "friendId");
                Long userId = longOf(event, "userId");
                String nickname = textOf(event, "nickname", "userName");
                String at = textOf(event, "occurredAt");
                if (watcherId == null || userId == null) {
                    continue;
                }
                notificationService.create(
                        watcherId, NotificationType.FRIEND_ONLINE,
                        NotificationTexts.friendOnlineTitle(nickname),
                        NotificationTexts.FRIEND_ONLINE_BODY,
                        NotificationType.FRIEND_ONLINE.path(),
                        // 접속 이벤트는 자주 반복된다. 시각까지 키에 넣어 같은 접속을 한 번만 알린다.
                        "presence:" + userId + ":" + watcherId + ":" + at,
                        Map.of());
            } catch (Exception e) {
                throw new BatchListenerFailedException("presence 알림 처리 실패", e, i);
            }
        }
    }

    /**
     * 배송 상태 변경.
     *
     * <p>🐛 <b>이 리스너가 없었다.</b> delivery-service 의 {@code DeliveryStatusPublisher}
     * 는 {@code delivery-status} 토픽으로 상태 변경을 <b>발행하고 있었는데, 그 토픽을 듣는
     * 코드가 코드베이스 전체에 없었다.</b> 이벤트가 카프카로 나갔다가 그대로 사라졌다.
     *
     * <p>{@link NotificationType#SHIPPING_UPDATED} 는 메일 제목({@code MailTemplates}),
     * SMS 문구({@code SmsTemplates}), 수신 설정 정책({@code NotificationPreference}),
     * 테스트까지 전부 갖춰져 있었다 — <b>만드는 쪽만 없었다.</b> 발행자도 이 리스너를
     * 염두에 두고 만들어졌다: {@code worthNotifying} 플래그와 안정적인 {@code eventId} 를
     * 이미 실어 보낸다.
     *
     * <p><b>모든 전이를 알리지 않는다.</b> 한 건의 배송은 접수 → 집화 → 간선 → 배달출발 →
     * 배달완료로 5~6번 바뀐다. 전부 알리면 문자 요금이고 소음이다. 발행자가 계산해 보낸
     * {@code worthNotifying} 만 통과시킨다 — 실제로 사용자의 행동이 달라지는 순간이다
     * (오늘 받는다 · 도착했다 · 문제가 생겼다 · 통관 보류로 서류가 필요하다).
     *
     * <p>판단을 여기서 다시 하지 않는 이유: 어떤 상태가 알릴 만한지는 배송 도메인의 지식이고
     * {@code DeliveryStatus.isWorthNotifying()} 이 그것을 갖고 있다. 여기에 복사하면 두 곳이
     * 어긋난다. 다만 플래그가 <b>없는</b> 이벤트는 알리지 않는다 — 옛 형식의 이벤트가
     * 재전달됐을 때 전부 문자로 나가는 것보다, 안 나가는 쪽이 낫다.
     */
    @KafkaListener(id = "notification-delivery", topics = "delivery-status",
                   containerFactory = "notificationListenerContainerFactory")
    public void onDeliveryStatus(java.util.List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive delivery-status")) {
                JsonNode event = objectMapper.readTree(messages.get(i));

                Long userId = longOf(event, "customerId", "userId");
                String orderId = textOf(event, "orderId");
                String status = textOf(event, "status");

                if (userId == null || status == null) {
                    continue;
                }

                JsonNode worth = event.get("worthNotifying");
                if (worth == null || !worth.asBoolean(false)) {
                    // 중간 단계다. 앱에도 남기지 않는다 — 배송 상세 화면이 이력을 보여준다.
                    continue;
                }

                notificationService.create(
                        userId, NotificationType.SHIPPING_UPDATED,
                        NotificationTexts.deliveryTitle(status),
                        NotificationTexts.deliveryBody(status, orderId),
                        NotificationType.SHIPPING_UPDATED.path() + (orderId == null ? "" : orderId),
                        // 발행자가 "delivery:<orderId>:<status>" 로 만들어 준다. 같은 상태
                        // 전이가 재전달돼도 알림은 하나다(sourceEventId 가 UNIQUE).
                        textOf(event, "eventId"),
                        Map.of("subject", NotificationTexts.orderSubject(orderId)));

            } catch (Exception e) {
                throw new BatchListenerFailedException("delivery-status 알림 처리 실패", e, i);
            }
        }
    }

    /**
     * 쿠폰 발급.
     *
     * <p>🐛 <b>이 리스너도 없었다.</b> {@link NotificationType#COUPON_ISSUED} 는 메일 제목
     * ({@code MailTemplates})·SMS 문구({@code SmsTemplates})·메일 종류 매핑({@code MailKind})·
     * 수신 설정 정책이 전부 갖춰져 있었는데 <b>만드는 쪽이 없었다.</b> 가입하면 쿠폰이
     * 발급되지만 사용자는 결제 화면에 가서야 그 사실을 알았다.
     *
     * <p>SMS 로는 보내지 않는다({@code smsWorthy=false}) — 판촉이라 놓쳐도 손해가 나지
     * 않고, 건당 과금을 태울 이유가 없다. 앱과 메일로 충분하다.
     */
    @KafkaListener(id = "notification-coupon", topics = "coupon-issued",
                   containerFactory = "notificationListenerContainerFactory")
    public void onCouponIssued(java.util.List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive coupon-issued")) {
                JsonNode event = objectMapper.readTree(messages.get(i));

                Long userId = longOf(event, "customerId", "userId");
                String name = textOf(event, "name");
                if (userId == null || name == null) {
                    continue;
                }

                JsonNode discountNode = event.get("discount");
                String discount = discountNode == null || discountNode.isNull()
                        ? null
                        : discountNode.asText();

                String until = textOf(event, "endDate");
                String body = NotificationTexts.couponBody(name, until);

                notificationService.create(
                        userId, NotificationType.COUPON_ISSUED,
                        NotificationTexts.couponTitle(discount),
                        body,
                        NotificationType.COUPON_ISSUED.path(),
                        // 발행자가 "coupon:<couponId>" 로 만들어 준다 — 재전달돼도 하나다.
                        textOf(event, "eventId"),
                        Map.of("subject", name));

            } catch (Exception e) {
                throw new BatchListenerFailedException("coupon-issued 알림 처리 실패", e, i);
            }
        }
    }

    /**
     * 새 기기 로그인 · 리프레시 토큰 재사용으로 로그인 해제 — 보안 알림.
     *
     * <p>둘 다 {@code SECURITY_ALERT} 다(수신 설정을 무시하고 앱 · 메일 · SMS). 이벤트의 {@code alert} 가
     * {@code refresh-reuse} 이면 "보안을 위해 로그인이 해제되었습니다"(docs/68 §7), 없으면 새 기기 로그인이다.
     *
     * <p>🐛 <b>이 알림도 만드는 쪽이 없었다.</b> {@link NotificationType#SECURITY_ALERT} 는
     * 메일 제목({@code MailTemplates})·SMS 문구({@code SmsTemplates})·메일 종류 매핑
     * ({@code MailKind})은 물론이고, 수신 설정에 <b>"보안 알림은 사용자가 꺼도 보낸다"</b>는
     * 예외까지 들어가 있었다({@code NotificationPreference.shouldSendSms}). 전부 갖춰 놓고
     * 발행하는 쪽만 없어서, 계정이 털려도 사용자는 알 방법이 없었다.
     *
     * <p><b>이것만은 SMS 로 나간다.</b> 다른 판촉성 알림과 정반대다 — 계정이 털리는 중이라는
     * 신호는 앱을 열어야 보이는 곳에만 두면 늦는다. 공격자가 먼저 알림 설정을 꺼 버리는 것도
     * 막아야 해서 수신 설정을 무시한다.
     *
     * <p><b>이메일을 이벤트에 싣는 이유.</b> 이 서비스에는 사용자 테이블이 없다. 목록 조회
     * 경로에서는 게이트웨이가 붙인 헤더로 주소를 기억해 두지만
     * ({@code NotificationService#rememberEmail}), 보안 알림은 사용자가 화면을 한 번도
     * 열지 않은 계정에도 나가야 한다 — 그 경우 기억된 주소가 없다. 발행자가 함께 보낸다.
     */
    @KafkaListener(id = "notification-security", topics = "security-alert",
                   containerFactory = "notificationListenerContainerFactory")
    public void onSecurityAlert(java.util.List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive security-alert")) {
                JsonNode event = objectMapper.readTree(messages.get(i));

                Long userId = longOf(event, "customerId", "userId");
                if (userId == null) {
                    log.warn("보안 이벤트에 수신자가 없어 알림을 만들지 못함: {}",
                            abbreviate(messages.get(i)));
                    continue;
                }

                // 주소를 먼저 기억시킨다. 그래야 아래 create 가 메일까지 보낼 수 있다 —
                // 화면을 한 번도 열지 않은 계정에는 기억된 주소가 없다.
                String email = textOf(event, "email");
                if (email != null) {
                    notificationService.rememberEmail(userId, email);
                }

                // 같은 토픽 · 같은 종류(SECURITY_ALERT — 수신 설정을 무시한다)에 사건만 다르다. alert 가 없으면 새 기기(예전 이벤트).
                // [JA] 同じトピック・同じ種類で出来事だけが違う。alert がなければ新しい端末(以前のイベント)。
                String alert = textOf(event, "alert");
                String title;
                String body;
                if (ALERT_REFRESH_REUSE.equals(alert)) {
                    title = NotificationTexts.SESSION_REVOKED_TITLE;
                    body = NotificationTexts.SESSION_REVOKED_BODY;
                } else if (ALERT_2FA_ENABLED.equals(alert)) {
                    title = NotificationTexts.TWO_FACTOR_ENABLED_TITLE;
                    body = NotificationTexts.TWO_FACTOR_ENABLED_BODY;
                } else if (ALERT_2FA_DISABLED.equals(alert)) {
                    title = NotificationTexts.TWO_FACTOR_DISABLED_TITLE;
                    body = NotificationTexts.TWO_FACTOR_DISABLED_BODY;
                } else if (ALERT_2FA_FAILURES.equals(alert)) {
                    title = NotificationTexts.TWO_FACTOR_FAILURES_TITLE;
                    body = NotificationTexts.TWO_FACTOR_FAILURES_BODY;
                } else if (ALERT_PASSWORD_RESET.equals(alert)) {
                    title = NotificationTexts.PASSWORD_RESET_TITLE;
                    body = NotificationTexts.PASSWORD_RESET_BODY;
                } else if (ALERT_SOCIAL_LINKED.equals(alert)) {
                    title = NotificationTexts.SOCIAL_LINKED_TITLE;
                    body = NotificationTexts.socialLinkedBody(textOf(event, "provider"));
                } else if (ALERT_UNPROVEN_PASSWORD_CLEARED.equals(alert)) {
                    title = NotificationTexts.UNPROVEN_PASSWORD_CLEARED_TITLE;
                    body = NotificationTexts.UNPROVEN_PASSWORD_CLEARED_BODY;
                } else {
                    title = NotificationTexts.SECURITY_TITLE;
                    body = NotificationTexts.securityBody(textOf(event, "deviceLabel"), textOf(event, "maskedIp"));
                }
                notificationService.create(
                        userId, NotificationType.SECURITY_ALERT,
                        title,
                        body,
                        NotificationType.SECURITY_ALERT.path(),
                        // 발행자가 "security:device:<id>" · "security:refresh-reuse:<묶음 해시>" 로 만들어 준다 — 재전달돼도 하나다.
                        textOf(event, "eventId"),
                        Map.of());

            } catch (Exception e) {
                throw new BatchListenerFailedException("security-alert 알림 처리 실패", e, i);
            }
        }
    }

    /**
     * 구매 확정 (docs/25 §12).
     *
     * <p>order-service 가 {@code order-completed} 로 발행한다 — 손님이 눌렀든({@code auto:false})
     * 배달 완료 뒤 기한이 지나 자동으로 됐든({@code auto:true}). <b>자동 확정이 알림의
     * 이유다.</b> 손님이 직접 누른 것은 이미 알고 있지만, 자동은 아무것도 안 했는데 주문
     * 상태가 바뀌는 일이라 알리지 않으면 주문 내역을 열고서야 안다. 문구가 둘로 갈린다.
     *
     * <p>{@code eventId} 는 발행자가 {@code order-completed:<orderId>} 로 만든다. 같은 주문의
     * 확정은 한 번뿐이라 재전달돼도 알림은 하나다(sourceEventId 가 UNIQUE).
     */
    @KafkaListener(id = "notification-order-completed", topics = "order-completed",
                   containerFactory = "notificationListenerContainerFactory")
    public void onOrderCompleted(java.util.List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive order-completed")) {
                JsonNode event = objectMapper.readTree(messages.get(i));
                Long userId = longOf(event, "customerId", "userId");
                String orderId = textOf(event, "orderId");
                if (userId == null || orderId == null) {
                    continue;
                }
                JsonNode auto = event.get("auto");
                boolean automatic = auto != null && auto.asBoolean(false);
                String eventId = textOf(event, "eventId");

                notificationService.create(
                        userId, NotificationType.ORDER_COMPLETED,
                        NotificationTexts.PURCHASE_CONFIRMED_TITLE,
                        automatic
                                ? NotificationTexts.purchaseAutoConfirmed(orderId)
                                : NotificationTexts.purchaseConfirmed(orderId),
                        NotificationType.ORDER_COMPLETED.path() + orderId,
                        eventId == null ? "order-completed:" + orderId : eventId,
                        Map.of("subject", NotificationTexts.orderSubject(orderId)));
            } catch (Exception e) {
                throw new BatchListenerFailedException("order-completed 알림 처리 실패", e, i);
            }
        }
    }

    /**
     * 부분 환불 (docs/49 §10).
     *
     * <p>🐛 <b>부분 환불에는 알림이 없었다.</b> "환불 완료" 는 결제 응답(CANCELED, {@link #onPayment})에서
     * 나오는데, order-service 는 전액이 다 돌아갔을 때만 결제를 취소한다. 품목 일부를 환불하면 돈은
     * 돌아가는데 아무 알림이 없었다.
     *
     * <p>order-service 가 환불 <b>한 건마다</b> {@code order-refunded} 를 보낸다. 전액({@code complete:true})은
     * 결제 응답 쪽이 이미 알리므로 건너뛴다 — 둘 다 만들면 같은 환불에 알림이 두 개다. 종류는 같은
     * {@code PAYMENT_REFUNDED}(메일·문자 문구와 수신 설정을 그대로 쓴다). 키는 발행자가 만든
     * {@code order-refunded:<orderId>:<n>} — n 번째 환불은 하나뿐이라 재전달돼도 하나다.
     */
    @KafkaListener(id = "notification-order-refunded", topics = "order-refunded",
                   containerFactory = "notificationListenerContainerFactory")
    public void onOrderRefunded(java.util.List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h).
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive order-refunded")) {
                JsonNode event = objectMapper.readTree(messages.get(i));
                Long userId = longOf(event, "customerId", "userId");
                String orderId = textOf(event, "orderId");
                String eventId = textOf(event, "eventId");
                JsonNode complete = event.get("complete");
                if (userId == null || orderId == null || eventId == null) {
                    log.warn("환불 이벤트에 수신자·주문·키가 없어 알림을 만들지 못함: {}", abbreviate(messages.get(i)));
                    continue;
                }
                if (complete == null || complete.asBoolean(true)) {
                    // 전액 환불 — 결제 응답(CANCELED)이 "환불 완료" 를 이미 만든다. 플래그가 없는 옛 형식도
                    // 전액으로 보고 건너뛴다(두 번 알리는 것보다 낫다).
                    continue;
                }
                String amount = refundAmount(textOf(event, "amount"), textOf(event, "currency"));
                notificationService.create(
                        userId, NotificationType.PAYMENT_REFUNDED,
                        NotificationTexts.PARTIAL_REFUND_TITLE,
                        NotificationTexts.partialRefund(orderId, amount),
                        NotificationType.PAYMENT_REFUNDED.path() + orderId,
                        eventId,
                        Map.of("subject", NotificationTexts.orderSubject(orderId)));
            } catch (Exception e) {
                throw new BatchListenerFailedException("order-refunded 알림 처리 실패", e, i);
            }
        }
    }

    /** "8,000 KRW" — 금액이 없거나 숫자가 아니면 null(문구에서 뺀다). */
    static String refundAmount(String amount, String currency) {
        if (amount == null || amount.isBlank()) {
            return null;
        }
        try {
            String grouped = new java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT)).format(new java.math.BigDecimal(amount));
            return currency == null || currency.isBlank() ? grouped : grouped + " " + currency;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── 스키마에 관대한 읽기 헬퍼 ────────────────────────────────────────────
    // 필드 이름이 서비스마다 조금씩 다르다(customerId/userId 등). 후보를 순서대로 본다.

    private static Long longOf(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                if (value.isNumber()) {
                    return value.asLong();
                }
                try {
                    return Long.parseLong(value.asText());
                } catch (NumberFormatException ignored) {
                    // 다음 후보로
                }
            }
        }
        return null;
    }

    private static String textOf(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String abbreviate(String raw) {
        return raw == null ? "" : raw.substring(0, Math.min(raw.length(), 200));
    }
}
