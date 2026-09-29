package com.project.notification_service.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.notification_service.domain.Notification;
import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.domain.SmsStatus;
import com.project.notification_service.repository.NotificationPreferenceRepository;
import com.project.notification_service.repository.NotificationRepository;
import com.project.notification_service.sms.PhoneNumbers;
import com.project.notification_service.sms.PhoneNumbers.Region;
import com.project.notification_service.sms.SmsMessage;
import com.project.notification_service.sms.SmsResult;
import com.project.notification_service.sms.SmsSender;
import com.project.notification_service.sms.SmsTemplates;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 발송 대기 알림을 집어 SMS 로 내보낸다.
 *
 * <p>구조는 아웃박스 폴러와 같다(docs/21) — 같은 함정이 있기 때문이다.
 *
 * <ul>
 *   <li><b>선점.</b> {@code FOR UPDATE SKIP LOCKED} 로 가져온다. 레플리카가 둘 이상일 때
 *       잠금이 없으면 양쪽이 같은 알림을 집어 <b>같은 문자가 두 통</b> 간다.</li>
 *   <li><b>동기 발송.</b> 트랜잭션 안에서 결과를 확정한다. 비동기 콜백으로 상태를 갱신하면
 *       잠금이 상태 확정 전에 풀려 창이 열린다.</li>
 *   <li><b>일시 실패를 영구 실패로 적지 않는다.</b> 게이트웨이가 잠깐 흔들렸다고 FAILED 로
 *       두면 그 알림은 다시는 나가지 않는다.</li>
 * </ul>
 *
 * <p>다른 점은 <b>시도 횟수 상한</b>이 있다는 것이다. 아웃박스는 무한히 재시도해도 되지만
 * (언젠가는 나가야 하는 이벤트다), SMS 는 시도할 때마다 요금이 나갈 수 있고 오래된 알림은
 * 보내 봐야 의미가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsDispatcher {

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final SmsSender smsSender;

    @Value("${notification.sms.batch-size:50}")
    private int batchSize;

    /** 이 횟수를 넘기면 포기한다. 계속 시도해도 요금만 나간다. */
    @Value("${notification.sms.max-attempts:3}")
    private int maxAttempts;

    /**
     * 첫 재시도까지 기다리는 시간. 시도할 때마다 두 배가 된다.
     *
     * <p>🔴 이게 없던 동안 3회 재시도가 <b>15초 만에</b> 소진됐다 — 폴링 주기가 5초인데 선점
     * 질의가 상한에 닿지 않은 행을 무조건 다시 집었기 때문이다. 게이트웨이가 잠깐 흔들린
     * 경우조차 살아남지 못했다. 메일(1분)보다 짧게 잡는다: 알림 문자는 늦으면 의미가 줄고,
     * SMS 게이트웨이의 일시 장애는 메일 서버의 그레이리스팅보다 짧게 끝난다.
     */
    @Value("${notification.sms.retry-backoff-ms:30000}")
    private long retryBackoffMs;

    /** 간격 상한. */
    @Value("${notification.sms.retry-backoff-max-ms:600000}")
    private long retryBackoffMaxMs;

    @Transactional
    @Scheduled(fixedDelayString = "${notification.sms.dispatch-interval-ms:5000}",
               initialDelayString = "${notification.sms.dispatch-initial-delay-ms:10000}")
    public void dispatch() {
        List<Notification> batch = notificationRepository.claimForSms(
                List.of(SmsStatus.PENDING, SmsStatus.RETRYABLE),
                maxAttempts,
                LocalDateTime.now(ZoneOffset.UTC),
                PageRequest.of(0, batchSize));

        if (batch.isEmpty()) {
            return;
        }
        log.debug("SMS 발송 대상 {}건 선점", batch.size());

        for (Notification notification : batch) {
            if (!sendOne(notification)) {
                // 게이트웨이가 흔들리는 중이다. 남은 배치를 계속 시도하면 타임아웃 × 배치 크기만큼
                // 행 잠금과 커넥션을 붙잡는다. 나머지는 다음 주기가 가져간다.
                log.warn("일시적 실패로 배치 중단 — 남은 {}건은 다음 주기에",
                        batch.size() - batch.indexOf(notification) - 1);
                break;
            }
        }
    }

    /** @return 상태가 확정됐으면 true, 일시적 실패라 배치를 멈춰야 하면 false */
    private boolean sendOne(Notification notification) {
        NotificationPreference preference =
                preferenceRepository.findByUserId(notification.getRecipientId()).orElse(null);

        // 알림을 만든 뒤 사용자가 번호를 지웠거나 SMS 를 껐을 수 있다. 발송 직전에 다시 본다.
        if (preference == null || !preference.shouldSendSms(notification.getType())) {
            notification.recordSmsAttempt(SmsStatus.NOT_APPLICABLE, "수신 설정 없음");
            return true;
        }

        String e164 = preference.getPhoneE164();
        Region region = PhoneNumbers.regionOfE164(e164).orElse(Region.KR);

        String text = SmsTemplates.forNotification(region, notification.getType(),
                Map.of("subject", notification.getTitle()));

        // 길이는 곧 요금이다. 넘치면 자르되, 잘렸다는 사실을 로그로 남긴다.
        if (!SmsMessage.fitsInSingleSms(text)) {
            log.info("SMS 문구가 한 건을 넘어 잘림: type={} len={}", notification.getType(), text.length());
            text = SmsMessage.truncate(text);
        }

        SmsResult result = smsSender.send(e164, text);

        switch (result.status()) {
            case SENT -> {
                notification.recordSmsAttempt(SmsStatus.SENT, result.providerMessageId());
                return true;
            }
            case PERMANENT -> {
                // 번호가 잘못됐거나 잔액이 없다. 재시도해도 같으므로 사람이 보게 남긴다.
                log.error("SMS 영구 실패: notificationId={} phone={} detail={}",
                        notification.getNotificationId(), PhoneNumbers.mask(e164), result.detail());
                notification.recordSmsAttempt(SmsStatus.FAILED, result.detail());
                return true;
            }
            default -> {
                // 다음에 집어도 되는 시각을 함께 정한다. 이게 없으면 5초 뒤 같은 행을 또 집어
                // 상한이 15초 만에 소진된다.
                notification.scheduleSmsRetry(result.detail(), retryBackoffMs, retryBackoffMaxMs);

                if (notification.getSmsAttempts() >= maxAttempts) {
                    log.warn("SMS 재시도 상한 도달, 포기: notificationId={} attempts={}",
                            notification.getNotificationId(), notification.getSmsAttempts());
                    // giveUpSms 다 — recordSmsAttempt 를 부르면 방금의 한 번이 두 번으로 기록된다.
                    notification.giveUpSms("재시도 상한 초과");
                    return true;
                }
                return false;
            }
        }
    }
}
