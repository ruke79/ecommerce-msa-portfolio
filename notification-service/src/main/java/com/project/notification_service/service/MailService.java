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
 *
 * <p>[JA] メール送信要求を<b>キューに入れる</b>。実際の送信は {@link MailDispatcher} が行う。
 * {@link NotificationService} が通知作成と SMS 送信を分けたのと同じ構造で、同じ理由だ —
 * イベントを受けたその場で SMTP を呼ぶと (1) メールサーバーが遅いとき Kafka
 * コンシューマが掴まれて他の通知まで遅れ、(2) 送信失敗をコンシューマの再試行で処理すると再試行のたびにメー
 * ルが一通ずつ多く出る。
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
     * <p>[JA] メールをキューに入れる。同じ {@code sourceEventId} では一度だけ入る。
     *
     * @return 새로 들어갔으면 그 행, 이미 있거나 보내지 않기로 했으면 비어 있음
     *         [JA] 新しく入ったらその行、すでにあるか送らないと決めたら空
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
        // [JA] ⚠️ アドレス検証をここでもう一度行う。明らかに誤ったアドレスへ繰り返し送るとハードバウンスが溜まり、
        //      送信ドメインの評判が落ちる — 正常なメールまで迷惑メールフォルダへ行くようになる。
        if (!EmailAddresses.isValid(toEmail)) {
            log.warn("보낼 수 없는 주소라 건너뜁니다: kind={} to={}",
                    kind, EmailAddresses.mask(toEmail));
            return Optional.empty();
        }

        // 1차 방어. UNIQUE 제약이 2차로 막지만, 여기서 걸러내면 예외 경로를 타지 않는다.
        // [JA] 一次防御。UNIQUE 制約が二次で防ぐが、ここで弾けば例外経路を通らない。
        if (sourceEventId != null && mailMessageRepository.findBySourceEventId(sourceEventId).isPresent()) {
            log.debug("이미 큐에 있는 메일입니다: sourceEventId={}", sourceEventId);
            return Optional.empty();
        }

        // 수신 거부 확인. 단, 사용자가 방금 직접 요청한 메일(가입 확인·비밀번호 재설정)과 보안 경고는
        // 설정으로 막지 않는다 — 앞은 막으면 사용자가 스스로 계정에 못 들어가고, 뒤는 공격자가 먼저 수신을 끌 수 있다
        // (MailKind#ignoresOptOut).
        // [JA] 受信拒否の確認。ただし、ユーザーがたった今自分で要求したメール(登録確認・パスワード再設定)
        //      とセキュリティ警告は設定では止めない — 前者は止めるとユーザーが自分でアカウントに入れなくなり、
        //      後者は攻撃者が先に受信を切れる(MailKind#ignoresOptOut)。
        if (!kind.ignoresOptOut() && recipientUserId != null) {
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
            // [JA] ⚠️ params はログに残さない — 確認リンクが入っている。
            log.info("메일 큐 등록: kind={} to={} id={}",
                    kind, EmailAddresses.mask(toEmail), saved.getMailId());
            return Optional.of(saved);

        } catch (DataIntegrityViolationException e) {
            // UNIQUE(source_event_id) 위반 — 동시에 같은 명령이 두 번 들어왔다. 정상 경로다.
            // [JA] UNIQUE(source_event_id) 違反 — 同時に同じ命令が二度入った。正常経路だ。
            log.debug("동시 중복 등록을 UNIQUE 제약이 막았습니다: sourceEventId={}", sourceEventId);
            return Optional.empty();
        }
    }

    /** 저장된 JSON 을 다시 맵으로. 깨져 있으면 빈 맵 — 문구가 어색해질지언정 발송은 계속한다. */
    // [JA] 保存した JSON をマップに戻す。壊れていたら空マップ — 文言が不自然になっても送信は続ける。
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
            // [JA] シリアライズが失敗したら値なしで保存する。文言は不自然になるがメール自体は出る — ⚠️
            //      例外メッセージに値が混じりうるので内容はログに入れない。
            log.error("메일 파라미터 직렬화 실패 — 값 없이 저장합니다 ({}개 키)", params.size());
            return null;
        }
    }
}
