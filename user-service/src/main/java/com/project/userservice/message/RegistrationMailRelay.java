package com.project.userservice.message;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.OutgoingMailRequest;
import com.project.userservice.message.model.OutboxEvent;
import com.project.userservice.message.producer.OutgoingMailProducer;
import com.project.userservice.message.repository.jpa.OutboxEventRepository;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.service.UserService;

import lombok.extern.slf4j.Slf4j;

/**
 * 가입 확인 메일 아웃박스 릴레이(docs/93 ③ 2단계 · docs/29 §5) — {@code users.outbox_event} 의 {@value #EVENT_TYPE} 행을 읽어
 * 확인 토큰을 <b>보낼 때</b> 만들고 메일 요청({@code outgoing-mail})을 보낸 뒤 완료로 표시한다.
 *
 * <h3>왜 행에 토큰을 싣지 않나</h3>
 *
 * <p>행에는 사용자 id 와 언어만 있다 — 원문 토큰 · 이메일을 아웃박스 행에 남기지 않는다(파티션 보존 동안 DB 에 남는다).
 * 토큰은 이 릴레이가 같은 트랜잭션에서 새로 만든다(DB 에는 해시만 — {@code regenerateVerificationToken}).
 *
 * <h3>실패하면</h3>
 *
 * <ul>
 *   <li>행 하나 = 트랜잭션 하나. 메일 요청을 보내지 못하면 그 트랜잭션을 <b>롤백</b>한다 — 토큰도 바뀌지 않고(앞 링크가 살아 있다)
 *       행은 STARTED 로 남아 다음 주기에 다시 보낸다. 그 묶음은 거기서 멈춘다(브로커가 없으면 행마다 기다리지 않는다).</li>
 *   <li>보낸 뒤 커밋이 실패하면 토큰 해시가 롤백돼 그 메일의 링크는 무효지만, 행이 완료로 표시되지 않았으므로 다음 주기에 <b>새 토큰으로
 *       다시 보낸다</b> — 스스로 복구된다. 그래서 {@code sourceEventId} 는 시도마다 다르다(같으면 notification 이 두 번째를 중복으로 버린다).</li>
 *   <li>그 사이 사용자가 이미 인증했거나 없어졌으면 보내지 않고 완료로 표시한다.</li>
 *   <li>가입 이벤트 릴레이({@link UserOutboxScheduler} — 실패하면 FAILED · 자동 재시도 없음)는 이 종류를 선점하지 않는다.</li>
 * </ul>
 *
 * <p>🐛 그 전: 가입 커밋 뒤 비동기 리스너가 Kafka 에 바로 보냈다 — 그 발행이 실패하면(브로커 · 파드 종료) 메일은 사라졌고 ERROR 한 줄만
 * 남았다. 사용자는 재발송을 눌러야만 했다(docs/29 §5 '남은 창').
 *
 * <p>[JA] 登録確認メールのアウトボックスのリレー — 行にはユーザー id と言語だけ(トークン · メールは載せない)。トークンは送るときに同じ
 *      トランザクションで作る。送れなければロールバック(トークンも変わらず行は STARTED のまま次の周期へ)。送った後にコミットが失敗しても、
 *      行が完了にならないので次の周期に新しいトークンで送り直す。以前はコミット後のリスナーが直接 Kafka に送り、失敗すればメールは消えた。
 */
@Slf4j
@Component
public class RegistrationMailRelay {

    /** {@code users.outbox_event.event_type} — 토픽 이름이 아니라 '이 사용자에게 확인 메일을 보내라' 는 뜻. */
    // [JA] event_type — トピック名ではなく「このユーザーに確認メールを送れ」という意味。
    public static final String EVENT_TYPE = "registration-mail";

    /** 한 번에 보내는 메일 수 — 가입은 분당 몇 건이라 넉넉하다. [JA] 一度に送るメール数。 */
    static final int BATCH = 50;

    enum Step { EMPTY, SENT, SKIPPED, FAILED }

    private final OutboxEventRepository outboxEventRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final OutgoingMailProducer mailProducer;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final String publicUrl;
    private final Supplier<String> attemptId;

    @org.springframework.beans.factory.annotation.Autowired
    public RegistrationMailRelay(OutboxEventRepository outboxEventRepository, UserRepository userRepository,
            @org.springframework.context.annotation.Lazy UserService userService, OutgoingMailProducer mailProducer,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
            @Value("${app.public-url:https://localhost:8000}") String publicUrl) {
        this(outboxEventRepository, userRepository, userService, mailProducer, objectMapper,
                new TransactionTemplate(transactionManager), publicUrl, () -> String.valueOf(System.nanoTime()));
    }

    RegistrationMailRelay(OutboxEventRepository outboxEventRepository, UserRepository userRepository, UserService userService,
            OutgoingMailProducer mailProducer, ObjectMapper objectMapper, TransactionTemplate tx, String publicUrl,
            Supplier<String> attemptId) {
        this.outboxEventRepository = outboxEventRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.mailProducer = mailProducer;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.publicUrl = publicUrl;
        this.attemptId = attemptId;
    }

    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay-ms:10000}",
            initialDelayString = "${app.outbox.initial-delay-ms:3000}")
    public void scheduled() {
        try {
            relayOnce();
        } catch (RuntimeException e) {
            log.warn("가입 확인 메일 릴레이가 이번 주기에 실패했다 — 다음 주기에 다시 한다: {}", e.toString());
        }
    }

    /** @return 보낸 메일 수 [JA] 送ったメールの数 */
    public int relayOnce() {
        int sent = 0;
        int skipped = 0;
        boolean failed = false;
        for (int i = 0; i < BATCH; i++) {
            Step step = tx.execute(status -> {
                Step s = sendOne();
                if (s == Step.FAILED) {
                    status.setRollbackOnly();   // 토큰도 그대로 · 행은 STARTED — 다음 주기에 [JA] トークンもそのまま · 行は STARTED
                }
                return s;
            });
            if (step == null || step == Step.EMPTY) {
                break;
            }
            if (step == Step.FAILED) {
                failed = true;
                break;
            }
            if (step == Step.SENT) {
                sent++;
            } else {
                skipped++;
            }
        }
        if (sent + skipped > 0 || failed) {
            log.info("가입 확인 메일 아웃박스 처리: 발행={} 건너뜀={} 실패로 멈춤={}", sent, skipped, failed);
        }
        return sent;
    }

    private Step sendOne() {
        List<OutboxEvent> rows = outboxEventRepository.claimRegistrationMail(1);
        if (rows.isEmpty()) {
            return Step.EMPTY;
        }
        OutboxEvent row = rows.get(0);
        User user = userRepository.findById(Long.valueOf(row.getAggregateId())).orElse(null);
        if (user == null || user.isEnabled()) {
            row.markCompleted();   // 그 사이 인증했거나 없어졌다 — 보낼 것이 없다 [JA] その間に認証済み · 消えた
            return Step.SKIPPED;
        }

        String rawToken = userService.regenerateVerificationToken(user);
        String confirmationUrl = publicUrl.replaceAll("/+$", "") + "/user-service/registrationConfirm?token=" + rawToken;
        boolean published = mailProducer.publish(OutgoingMailRequest.builder()
                .sourceEventId("registration-confirm:" + user.getUserId() + ":" + attemptId.get())
                .toEmail(user.getEmail())
                .recipientUserId(user.getUserId())
                .kind("REGISTRATION_CONFIRM")
                .params(Map.of("confirmationUrl", confirmationUrl))
                .locale(locale(row))
                .build());
        if (!published) {
            log.warn("가입 확인 메일 요청을 보내지 못했다 — 다음 주기에 다시 보낸다: userId={}", user.getUserId());
            return Step.FAILED;
        }
        row.markCompleted();
        log.info("가입 확인 메일 요청 발행: userId={}", user.getUserId());
        return Step.SENT;
    }

    private String locale(OutboxEvent row) {
        try {
            String tag = objectMapper.readTree(row.getPayload()).path("locale").asText(null);
            return tag == null || tag.isBlank() ? null : tag;
        } catch (Exception e) {
            return null;
        }
    }
}
