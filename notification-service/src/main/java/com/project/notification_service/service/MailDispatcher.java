package com.project.notification_service.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.notification_service.domain.MailMessage;
import com.project.notification_service.domain.MailStatus;
import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.mail.EmailAddresses;
import com.project.notification_service.mail.MailResult;
import com.project.notification_service.mail.MailSender;
import com.project.notification_service.mail.MailTemplates;
import com.project.notification_service.repository.MailMessageRepository;
import com.project.notification_service.repository.NotificationPreferenceRepository;
import com.project.notification_service.sms.PhoneNumbers.Region;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 발송 대기 메일을 집어 내보낸다. {@link SmsDispatcher} 의 메일판이다.
 *
 * <p>구조가 같은 것은 우연이 아니다 — 둘 다 아웃박스 폴러이고, 같은 함정이 있다(docs/21):
 *
 * <ul>
 *   <li><b>선점.</b> {@code FOR UPDATE SKIP LOCKED}. 잠금이 없으면 레플리카 둘이 같은 행을 집어
 *       메일이 두 통 간다.</li>
 *   <li><b>동기 발송.</b> 트랜잭션 안에서 결과를 확정한다. 비동기 콜백으로 상태를 갱신하면
 *       잠금이 상태 확정 전에 풀려 창이 열린다.</li>
 *   <li><b>일시 실패를 영구 실패로 적지 않는다.</b> 메일 서버가 잠깐 흔들렸다고 FAILED 로 두면
 *       사용자는 가입 확인 메일을 영영 못 받는다.</li>
 * </ul>
 *
 * <p>SMS 와 다른 점이 둘 있다. 첫째, 재시도 상한이 더 크다(기본 5회) — 메일은 건당 과금이
 * 아니므로 몇 번 더 시도해도 비용이 아니고, SMTP 는 그레이리스팅 때문에 첫 시도가 실패하는 것이
 * 정상인 경우가 있다. 둘째, <b>오래된 행의 파라미터를 주기적으로 지운다</b> — 링크가 자격증명이기
 * 때문이다(§{@link #scrubStaleParams}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailDispatcher {

    private final MailMessageRepository mailMessageRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final MailService mailService;
    private final MailSender mailSender;

    @Value("${notification.mail.batch-size:50}")
    private int batchSize;

    /**
     * 이 횟수를 넘기면 포기한다.
     *
     * <p>SMS(3회)보다 큰 이유: 그레이리스팅을 쓰는 수신 서버는 <b>처음 온 발신자를 일부러 한 번
     * 거절</b>하고 재시도를 기다린다(스팸 봇은 재시도하지 않는다는 전제). 상한이 작으면 그런
     * 서버로는 첫 메일이 영영 안 간다.
     */
    @Value("${notification.mail.max-attempts:5}")
    private int maxAttempts;

    /**
     * 첫 재시도까지 기다리는 시간. 시도할 때마다 두 배가 된다.
     *
     * <p>🔴 <b>기본값이 1분인 것은 상한 5회와 짝이다.</b> 이게 없던 동안 재시도는 이름뿐이었다 —
     * 폴링 주기가 5초라 5회가 20초 만에 소진됐고, 그레이리스팅(수 분 대기)을 쓰는 서버로는
     * 첫 메일이 영영 나가지 못했다. 1·2·4·8분이면 마지막 시도가 약 15분 뒤라, 그레이리스팅이
     * 풀리는 구간을 지난다.
     */
    @Value("${notification.mail.retry-backoff-ms:60000}")
    private long retryBackoffMs;

    /** 간격 상한. 오래 걸리는 장애에서도 하루에 몇 번은 시도한다. */
    @Value("${notification.mail.retry-backoff-max-ms:1800000}")
    private long retryBackoffMaxMs;

    /** 이 시간이 지난 미발송 행의 링크는 지운다. 링크 자체가 24시간이면 만료된다. */
    @Value("${notification.mail.params-retention-hours:24}")
    private int paramsRetentionHours;

    @Transactional
    @Scheduled(fixedDelayString = "${notification.mail.dispatch-interval-ms:5000}",
               initialDelayString = "${notification.mail.dispatch-initial-delay-ms:10000}")
    public void dispatch() {
        List<MailMessage> batch = mailMessageRepository.claimForSend(
                List.of(MailStatus.PENDING, MailStatus.RETRYABLE),
                maxAttempts,
                LocalDateTime.now(ZoneOffset.UTC),
                PageRequest.of(0, batchSize));

        if (batch.isEmpty()) {
            return;
        }
        log.debug("메일 발송 대상 {}건 선점", batch.size());

        for (int i = 0; i < batch.size(); i++) {
            if (!sendOne(batch.get(i))) {
                // 메일 서버가 흔들리는 중이다. 남은 배치를 계속 시도하면 타임아웃 × 배치 크기만큼
                // 행 잠금과 커넥션을 붙잡는다. 나머지는 다음 주기가 가져간다.
                log.warn("일시적 실패로 배치 중단 — 남은 {}건은 다음 주기에", batch.size() - i - 1);
                break;
            }
        }
    }

    /** @return 상태가 확정됐으면 true, 일시적 실패라 배치를 멈춰야 하면 false */
    private boolean sendOne(MailMessage message) {
        Region region = resolveRegion(message);

        MailTemplates.Rendered rendered =
                MailTemplates.render(region, message.getKind(), mailService.readParams(message));

        MailResult result = mailSender.send(
                message.getToEmail(), rendered.subject(), rendered.text(), rendered.html());

        switch (result.status()) {
            case SENT -> {
                // recordAttempt 가 종료 상태에서 params 를 지운다 — 링크를 발송 후까지 들고
                // 있지 않는다.
                message.recordAttempt(MailStatus.SENT, null);
                return true;
            }
            case PERMANENT -> {
                // 주소가 없거나 도메인이 없다. 재시도하면 하드 바운스만 쌓인다.
                log.error("메일 영구 실패: mailId={} to={} detail={}",
                        message.getMailId(), EmailAddresses.mask(message.getToEmail()), result.detail());
                message.recordAttempt(MailStatus.FAILED, result.detail());
                return true;
            }
            default -> {
                // 다음에 집어도 되는 시각을 함께 정한다. 이게 없으면 5초 뒤 같은 행을 또 집어
                // 상한이 20초 만에 소진된다 — 이 채널의 재시도를 실제로 만드는 한 줄이다.
                message.scheduleRetry(result.detail(), retryBackoffMs, retryBackoffMaxMs);

                if (!message.canRetry(maxAttempts)) {
                    log.warn("메일 재시도 상한 도달, 포기: mailId={} to={} attempts={}",
                            message.getMailId(), EmailAddresses.mask(message.getToEmail()),
                            message.getAttempts());
                    // giveUp 이다 — recordAttempt 를 부르면 방금의 한 번이 두 번으로 기록된다.
                    message.giveUp("재시도 상한 초과");
                    return true;
                }
                log.debug("메일 재시도 예약: mailId={} attempts={} nextAttemptAt={}",
                        message.getMailId(), message.getAttempts(), message.getNextAttemptAt());
                return false;
            }
        }
    }

    /**
     * 문구 언어를 정한다. 사용자 설정이 있으면 그쪽이 우선한다.
     *
     * <p>설정이 없는 경우가 정상적으로 존재한다 — 가입 확인 메일은 사용자가 알림 설정을 만들기
     * 전에 나간다. 그때는 명령에 실려 온 로케일을 쓰고, 그것도 없으면 한국어다.
     */
    private Region resolveRegion(MailMessage message) {
        if (message.getRecipientUserId() != null) {
            NotificationPreference preference =
                    preferenceRepository.findByUserId(message.getRecipientUserId()).orElse(null);
            if (preference != null && preference.getRegion() != null) {
                try {
                    return Region.valueOf(preference.getRegion());
                } catch (IllegalArgumentException ignored) {
                    // 설정에 알 수 없는 값이 들어 있다. 명령의 로케일로 넘어간다.
                }
            }
        }
        return MailTemplates.regionOf(message.getLocale());
    }

    /**
     * 끝내 발송되지 못한 오래된 행에서 링크를 지운다.
     *
     * <p>정상 경로에서는 발송 직후 지워지지만, 디스패처가 꺼져 있던 사이 쌓인 행이나 상한을
     * 넘기기 전에 방치된 행이 남을 수 있다. 링크는 24시간이면 어차피 만료되므로 그 뒤로 값을
     * 들고 있을 이유가 없다 — <b>DB 덤프 한 번이 곧 계정 탈취 수단이 되는 것을 막는다.</b>
     *
     * <p>행 자체는 남긴다. "왜 이 사용자에게 메일이 안 갔나"는 나중에도 볼 수 있어야 한다.
     */
    @Transactional
    @Scheduled(fixedDelayString = "${notification.mail.scrub-interval-ms:3600000}",
               initialDelayString = "${notification.mail.scrub-initial-delay-ms:60000}")
    public void scrubStaleParams() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusHours(paramsRetentionHours);
        int scrubbed = mailMessageRepository.scrubParamsOlderThan(before);
        if (scrubbed > 0) {
            log.info("만료된 메일 링크 {}건을 지웠습니다(행은 남깁니다)", scrubbed);
        }
    }
}
