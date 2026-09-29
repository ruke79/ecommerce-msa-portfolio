package com.project.notification_service.service;

import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.MailKind;
import com.project.notification_service.domain.MailMessage;
import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.mail.EmailAddresses;
import com.project.notification_service.repository.MailMessageRepository;
import com.project.notification_service.repository.NotificationPreferenceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 메일 발송 요청을 <b>큐에 넣는다</b>. 실제 발송은 {@link MailDispatcher} 가 한다.
 *
 * <p>{@link NotificationService} 가 알림 생성과 SMS 발송을 분리한 것과 같은 구조이고, 같은
 * 이유다 — 이벤트를 받은 자리에서 바로 SMTP 를 호출하면 (1) 메일 서버가 느릴 때 Kafka 컨슈머가
 * 붙잡혀 다른 알림까지 밀리고, (2) 전송 실패를 컨슈머 재시도로 처리하면 재시도할 때마다 메일이
 * 한 통씩 더 나간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final MailMessageRepository mailMessageRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final ObjectMapper objectMapper;

    /**
     * 메일을 큐에 넣는다. 같은 {@code sourceEventId} 로는 한 번만 들어간다.
     *
     * @return 새로 들어갔으면 그 행, 이미 있거나 보내지 않기로 했으면 비어 있음
     */
    @Transactional
    public Optional<MailMessage> enqueue(String sourceEventId, String toEmail, Long recipientUserId,
                                         MailKind kind, Map<String, String> params, String locale) {

        if (kind == null) {
            log.warn("메일 종류가 없어 큐에 넣지 않습니다: sourceEventId={}", sourceEventId);
            return Optional.empty();
        }

        // ⚠️ 주소 검증을 여기서 한 번 더 한다. 명백히 잘못된 주소로 반복 발송하면 하드 바운스가
        //    쌓여 발신 도메인 평판이 떨어진다 — 정상 메일까지 스팸함으로 가게 된다.
        if (!EmailAddresses.isValid(toEmail)) {
            log.warn("보낼 수 없는 주소라 건너뜁니다: kind={} to={}",
                    kind, EmailAddresses.mask(toEmail));
            return Optional.empty();
        }

        // 1차 방어. UNIQUE 제약이 2차로 막지만, 여기서 걸러내면 예외 경로를 타지 않는다.
        if (sourceEventId != null && mailMessageRepository.findBySourceEventId(sourceEventId).isPresent()) {
            log.debug("이미 큐에 있는 메일입니다: sourceEventId={}", sourceEventId);
            return Optional.empty();
        }

        // 수신 거부 확인. 단, 사용자가 방금 직접 요청한 메일(가입 확인·비밀번호 재설정)은
        // 설정으로 막지 않는다 — 막으면 사용자가 스스로 계정에 못 들어간다(MailKind#isTransactional).
        if (!kind.isTransactional() && recipientUserId != null) {
            NotificationPreference preference =
                    preferenceRepository.findByUserId(recipientUserId).orElse(null);
            if (preference != null && !preference.isEmailEnabled()) {
                log.debug("사용자가 메일 수신을 껐습니다: userId={} kind={}", recipientUserId, kind);
                return Optional.empty();
            }
        }

        MailMessage message = MailMessage.builder()
                .toEmail(toEmail.trim())
                .recipientUserId(recipientUserId)
                .kind(kind)
                .params(toJson(params))
                .locale(locale)
                .sourceEventId(sourceEventId)
                .build();

        try {
            MailMessage saved = mailMessageRepository.saveAndFlush(message);
            // ⚠️ params 는 로그에 남기지 않는다 — 확인 링크가 들어 있다.
            log.info("메일 큐 등록: kind={} to={} id={}",
                    kind, EmailAddresses.mask(toEmail), saved.getMailId());
            return Optional.of(saved);

        } catch (DataIntegrityViolationException e) {
            // UNIQUE(source_event_id) 위반 — 동시에 같은 명령이 두 번 들어왔다. 정상 경로다.
            log.debug("동시 중복 등록을 UNIQUE 제약이 막았습니다: sourceEventId={}", sourceEventId);
            return Optional.empty();
        }
    }

    /** 저장된 JSON 을 다시 맵으로. 깨져 있으면 빈 맵 — 문구가 어색해질지언정 발송은 계속한다. */
    public Map<String, String> readParams(MailMessage message) {
        String raw = message.rawParams();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("메일 파라미터를 읽지 못했습니다: mailId={} {}", message.getMailId(), e.getMessage());
            return Map.of();
        }
    }

    private String toJson(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            // 직렬화가 실패하면 값 없이 저장한다. 문구는 어색해지지만 메일 자체는 나간다 —
            // ⚠️ 예외 메시지에 값이 섞일 수 있으므로 내용은 로그에 넣지 않는다.
            log.error("메일 파라미터 직렬화 실패 — 값 없이 저장합니다 ({}개 키)", params.size());
            return null;
        }
    }
}
