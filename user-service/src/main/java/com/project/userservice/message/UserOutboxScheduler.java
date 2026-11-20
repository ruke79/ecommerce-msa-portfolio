package com.project.userservice.message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.userservice.message.model.OutboxEvent;
import com.project.userservice.message.producer.UserCreatedProducer;
import com.project.userservice.message.repository.jpa.OutboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 아웃박스에 적힌 사용자 이벤트를 카프카로 내보내는 폴러.
 *
 * <p>{@link OutboxScheduler} 인터페이스는 이 저장소에 <b>구현체 없이</b> 놓여 있었다.
 * 이것이 그 구현이다. 설계는 order-service 의 {@code PaymentOutboxScheduler} 와 같다 —
 * 같은 문제를 두 번 다르게 풀 이유가 없다.
 *
 * <h3>레플리카가 둘이어도 안전해야 한다</h3>
 *
 * <p>user-service 는 {@code replicas: 2} 다. 두 가지를 지켜야 중복 발행이 없다.
 *
 * <ol>
 *   <li><b>선점해서 읽는다</b> — {@code FOR UPDATE SKIP LOCKED}. 잠금 없이 읽으면 두
 *       레플리카가 같은 행을 본다.</li>
 *   <li><b>발행을 이 트랜잭션 안에서 동기로 기다린다.</b> 비동기로 두면 상태를 바꾸는
 *       콜백이 <b>커밋 뒤에</b> 돌고, 그 사이 행은 여전히 STARTED 인데 잠금은 풀려 있다 —
 *       그 틈에 다른 레플리카가 집어 간다.</li>
 * </ol>
 *
 * <p>order-service 의 아웃박스가 정확히 이 두 가지를 어겨서 결제 요청이 두 번 나갈 수
 * 있었다(docs/21).
 *
 * <h3>그래도 중복은 남는다 — 그건 소비 측의 몫이다</h3>
 *
 * <p>카프카는 at-least-once 다. 발행에는 성공했는데 커밋 직전에 죽으면 그 행은 STARTED 로
 * 남고 다음 주기에 다시 나간다. 여기서 없애는 것은 <b>레플리카 때문에 생기는</b>
 * 중복뿐이다. 소비자 셋은 이미 이를 전제로 짜여 있다 — order·chat 은
 * {@code DataIntegrityViolationException} 을 흘려보내고, coupon 은 중복 발급을 막는다.
 *
 * <h3>실패를 지우지 않는다</h3>
 *
 * <p>발행이 실패하면 {@code FAILED} 로 남긴다. 지우면 <b>무엇이 안 나갔는지 알 수 없다</b> —
 * 조용히 사라지는 것이 이 전체 사태의 원인이었다(docs/47). 남아 있으면 세어 볼 수 있고,
 * 손으로든 배치로든 다시 보낼 수 있다.
 *
 * <p>⚠️ FAILED 는 <b>자동으로 재시도하지 않는다.</b> 브로커가 보내는 도중에 죽으면 그 주기에 보낸
 * 묶음은 기한(publish-timeout-ms) 뒤 FAILED 가 되는데, 그걸 계속 되돌리면 무한 루프에 가깝다.
 * 복구는 대사 배치의 몫이다(docs/48 ③). 지금은 <b>보이게 하는 것</b>까지가 목표다. 보내기가 처음부터 바로
 * 실패하면(브로커에 닿지 못함) 그 한 건만 FAILED 이고 나머지는 STARTED 로 다음 주기에 간다
 * ({@link #processOutboxMessage()}, 2026-11-20).
 *
 * <p>[JA] アウトボックスに書かれたユーザーイベントを Kafka へ送り出すポーラー。
 * {@link OutboxScheduler} インターフェースはこのリポジトリに<b>実装なしで</b>置かれていた。これがその実装だ。
 * 設計は order-service の {@code PaymentOutboxScheduler} と同じ — 同じ問題を二度違うやり方で解く理由はない。
 * レプリカが二つでも安全でなければならない: user-service は {@code replicas: 2} だ。二つを守ってはじめて重複発行がない。
 * 1) <b>先取りして読む</b> — {@code FOR UPDATE SKIP LOCKED}。ロックなしで読むと二つのレプリカが同じ行を見る。
 * 2) <b>発行をこのトランザクション内で同期的に待つ。</b>非同期にすると状態を変えるコールバックが<b>コミット後に</b>動き、
 * その間行はまだ STARTED なのにロックは外れている — その隙に別のレプリカが拾っていく。
 * order-service のアウトボックスがまさにこの二つを破っていて、決済リクエストが二回出る可能性があった(docs/21)。
 * それでも重複は残る — それは消費側の役目だ: Kafka は at-least-once である。発行には成功したのにコミット直前に落ちるとその行は
 * STARTED のまま残り、次の周期に再び出る。ここで除くのは<b>レプリカのせいで生じる</b>重複だけだ。三つのコンシューマは既に
 * これを前提に作られている — order・chat は {@code DataIntegrityViolationException} を流し、coupon は重複発行を防ぐ。
 * 失敗を消さない: 発行が失敗したら {@code FAILED} として残す。消すと<b>何が送られなかったか分からない</b> —
 * 黙って消えることがこの事態全体の原因だった(docs/47)。残っていれば数えられ、手作業でもバッチでも再送できる。
 * ⚠️ FAILED は<b>自動で再試行しない。</b>ブローカーが送信途中で落ちるとその周期に送った束は期限(publish-timeout-ms)後に
 * FAILED になるが、それを戻し続けると無限ループに近い。復旧は突合バッチの役目だ(docs/48 ③)。今は<b>見えるようにする</b>ところまでが
 * 目標だ。送信が最初からすぐ失敗したら(ブローカーに届かない)その一件だけが FAILED で、残りは STARTED のまま次の周期に行く
 * ({@link #processOutboxMessage()}、2026-11-20)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserOutboxScheduler implements OutboxScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final UserCreatedProducer userCreatedProducer;
    /** 프로필 변경·탈퇴(docs/48 §6-⑤). */
    // [JA] プロフィール変更・退会(docs/48 §6-⑤)。
    private final com.project.userservice.message.producer.UserLifecycleProducer lifecycleProducer;
    private final ObjectMapper objectMapper;
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    /**
     * 한 번에 선점할 행 수.
     *
     * <p>제한이 없으면 한 레플리카가 밀린 이벤트를 전부 잠가 나머지가 놀고, 트랜잭션도
     * 그만큼 길어진다.
     *
     * <p>[JA] 一度に先取りする行数。
     * 制限がないと一つのレプリカが溜まったイベントを全部ロックして残りが遊び、トランザクションもその分長くなる。
     */
    @Value("${app.outbox.batch-size:100}")
    private int batchSize;

    /**
     * 한 건의 ack 를 기다리는 상한 — 묶음 전체가 이 시간 안에 끝난다(건마다가 아니다).
     *
     * <p>[JA] 1 件の ack を待つ上限 — バッチ全体がこの時間内に終わる(1 件ごとではない)。
     */
    @Value("${app.outbox.publish-timeout-ms:10000}")
    private long publishTimeoutMs = 10_000;

    /** 테스트용. [JA] テスト用。 */
    void setPublishTimeoutMs(long publishTimeoutMs) {
        this.publishTimeoutMs = publishTimeoutMs;
    }

    /**
     * 선점한 묶음을 <b>모두 보낸 뒤 함께</b> 기다린다(docs/79 §4).
     *
     * <h3>🐛 예전: 한 건씩 보내고 기다렸다</h3>
     *
     * <p>트랜잭션(행 잠금 · DB 커넥션 하나)이 묶음 × 왕복이었고, 브로커가 죽으면 묶음 × 상한(100건 × 10초 ≈ 17분) 동안
     * 끝나지 않았다. 묶음을 키워 빨리 흘릴 수도 없었다(1만 건이면 약 28시간). 이제는 보내기만 먼저 하고(프로듀서가 묶어 보낸다)
     * 한 번의 기한 안에서 ack 를 모은다 — 트랜잭션은 왕복 한 번 · 최악 상한 한 번이다.
     *
     * <h3>지키는 것</h3>
     *
     * <ul>
     *   <li><b>상태는 커밋 전에 정한다</b> — 기다리기는 여전히 이 트랜잭션 안이다. 비동기 콜백으로 상태를 바꾸면 커밋 뒤의 틈에
     *       다른 레플리카가 같은 행을 집는다(위 클래스 주석).</li>
     *   <li><b>같은 사용자의 순서</b> — user-created 와 user-updated · deleted 는 서로 다른 프로듀서라 함께 보내면 순서가 뒤바뀔 수
     *       있다. 같은 aggregate 의 앞 이벤트가 아직 ack 전이면 그것을 먼저 기다린 뒤 보낸다.</li>
     *   <li><b>보내기가 바로 실패하면(브로커에 닿지 못함)</b> 그 행만 FAILED, 나머지는 보내지 않고 STARTED 로 둔다 — 다음 주기가
     *       다시 집는다. 예전에는 건마다 메타데이터 대기(max.block)를 되풀이하고 묶음 전체를 FAILED 로 굳혔다.</li>
     * </ul>
     *
     * <p>[JA] 確保したバッチを<b>すべて送ってからまとめて</b>待つ(docs/79 §4)。
     *
     * <p>🐛 以前: 1 件ずつ送って待っていた。トランザクション(行ロック · DB コネクション一つ)がバッチ × 往復で、ブローカーが死ぬと
     * バッチ × 上限(100 件 × 10 秒 ≈ 17 分)の間終わらなかった。バッチを大きくして速く流すこともできなかった(1 万件なら約 28 時間)。
     * 今は送信だけを先に行い(プロデューサーがまとめて送る)、一度の期限の中で ack を集める — トランザクションは往復一回 · 最悪でも上限一回である。
     *
     * <p>守ること:
     * <ul>
     *   <li><b>状態はコミット前に決める</b> — 待つのは依然としてこのトランザクションの中である。非同期コールバックで状態を変えると、
     *       コミット後の隙に別のレプリカが同じ行を拾う(上のクラスコメント)。</li>
     *   <li><b>同じユーザーの順序</b> — user-created と user-updated · deleted は別のプロデューサーなので、一緒に送ると順序が入れ替わる
     *       ことがある。同じ aggregate の前のイベントがまだ ack 前なら、それを先に待ってから送る。</li>
     *   <li><b>送信が即失敗したら(ブローカーに届かない)</b>その行だけ FAILED、残りは送らず STARTED のままにする — 次の周期が
     *       また拾う。以前は 1 件ごとにメタデータ待ち(max.block)を繰り返し、バッチ全体を FAILED で固めていた。</li>
     * </ul>
     */
    @Override
    @Transactional
    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay-ms:10000}",
            initialDelayString = "${app.outbox.initial-delay-ms:3000}")
    public void processOutboxMessage() {
        List<OutboxEvent> claimed = outboxEventRepository.claimPending(batchSize);
        if (claimed.isEmpty()) {
            return;
        }

        log.debug("아웃박스 선점: {}건", claimed.size());

        // ① 보내기 — ack 는 기다리지 않는다(같은 사용자의 앞 이벤트만 기다린다)
        // [JA] ① 送る — ack は待たない(同じユーザーの前のイベントだけを待つ)
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(publishTimeoutMs);
        List<InFlight> inFlight = new ArrayList<>(claimed.size());
        Map<String, CompletableFuture<?>> lastByAggregate = new HashMap<>();
        int failed = 0;
        int deferred = 0;
        boolean unreachable = false;
        for (OutboxEvent event : claimed) {
            if (unreachable) {
                deferred++;   // STARTED 그대로 — 다음 주기 ／ [JA] STARTED のまま — 次の周期
                continue;
            }
            CompletableFuture<?> before = lastByAggregate.get(event.getAggregateId());
            if (before != null) {
                awaitQuietly(before, deadline);
            }
            CompletableFuture<?> future;
            try {
                future = send(event);
            } catch (UnsendableEvent e) {
                log.error("아웃박스 발행 실패: id={} type={} — {}", event.getId(), event.getEventType(), e.getMessage());
                event.markFailed();
                failed++;
                continue;
            } catch (org.apache.kafka.common.errors.SerializationException e) {
                // 그 행의 값 탓이다 — 브로커는 멀쩡할 수 있으니 묶음을 멈추지 않는다
                // [JA] その行の値のせいだ — ブローカーは健全かもしれないので束を止めない
                log.error("아웃박스 발행 실패(직렬화): id={} type={}", event.getId(), event.getEventType(), e);
                event.markFailed();
                failed++;
                continue;
            } catch (RuntimeException e) {
                log.error("아웃박스 발행 실패(브로커에 닿지 못함): id={} — 이 묶음의 나머지는 다음 주기로", event.getId(), e);
                event.markFailed();
                failed++;
                unreachable = true;
                continue;
            }
            inFlight.add(new InFlight(event, future));
            if (event.getAggregateId() != null) {
                lastByAggregate.put(event.getAggregateId(), future);
            }
        }

        // ② 함께 기다리기 — 묶음 전체가 한 번의 기한 안에서
        // [JA] ② まとめて待つ — 束全体が一回の期限内で
        int sent = 0;
        for (InFlight f : inFlight) {
            if (awaitQuietly(f.future(), deadline)) {
                f.event().markCompleted();
                sent++;
            } else {
                f.event().markFailed();
                failed++;
            }
        }

        // 선점한 행은 관리 상태다 — 더티 체킹으로 상태가 커밋된다. save 를 부르면
        // 그 엔티티는 isNew()=false 라 merge 로 가고, 같은 일을 한 번 더 한다.
        // [JA] 先取りした行は管理状態だ — ダーティチェックで状態がコミットされる。save を呼ぶと
        //      そのエンティティは isNew()=false なので merge に行き、同じことをもう一度する。
        if (failed > 0) {
            log.error("아웃박스 발행 실패 {}건 — FAILED 로 남긴다(자동 재시도하지 않는다)", failed);
        }
        if (deferred > 0) {
            log.warn("아웃박스: 브로커에 닿지 못해 {}건은 보내지 않았다 — STARTED 그대로, 다음 주기에 다시", deferred);
        }
        log.info("아웃박스 처리: 발행={} 실패={} 미룸={}", sent, failed, deferred);
    }

    private record InFlight(OutboxEvent event, CompletableFuture<?> future) {}

    /** 보낼 수 없는 행(모르는 종류 · 깨진 페이로드) — 브로커 탓이 아니므로 묶음을 멈추지 않는다. */
    // [JA] 送れない行(未知の種別 · 壊れたペイロード) — ブローカーのせいではないので束を止めない。
    private static final class UnsendableEvent extends RuntimeException {
        UnsendableEvent(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /** @return 기한 안에 성공했으면 true */
    // [JA] @return 期限内に成功したら true
    private boolean awaitQuietly(CompletableFuture<?> future, long deadlineNanos) {
        long left = deadlineNanos - System.nanoTime();
        try {
            future.get(Math.max(0, left), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException e) {
            log.error("아웃박스 발행 ack 시간 초과({}ms 기한)", publishTimeoutMs);
            return false;
        } catch (ExecutionException e) {
            log.error("아웃박스 발행 실패: {}", e.getCause() == null ? e.toString() : e.getCause().toString());
            return false;
        }
    }

    /** 보내기만 한다. 결과는 돌려준다. */
    // [JA] 送るだけ。結果は返す。
    private CompletableFuture<?> send(OutboxEvent event) {
        String type = event.getEventType();
        if (com.project.userservice.message.producer.UserLifecycleProducer.UPDATED.equals(type)
                || com.project.userservice.message.producer.UserLifecycleProducer.DELETED.equals(type)) {
            // 페이로드는 이미 JSON 문자열이다 — 그대로 보낸다. 행을 쓴 요청의 추적 아래에서(docs/51 §4e).
            // [JA] ペイロードは既に JSON 文字列だ — そのまま送る。行を書いたリクエストの追跡の下で(docs/51 §4e)。
            return outboxTraceContext.resume(event.getTraceContext(), "outbox relay " + type,
                    () -> lifecycleProducer.send(type, event.getAggregateId(), event.getPayload()));
        }
        if (!UserCreatedProducer.TOPIC.equals(type)) {
            // 모르는 종류를 조용히 흘려보내면 그 행은 영원히 STARTED 로 남아 매 주기마다
            // 다시 선점된다. FAILED 로 못 박아 눈에 띄게 한다.
            // [JA] 未知の種別を黙って流すとその行は永遠に STARTED のまま残り、毎周期
            //      再び先取りされる。FAILED に固定して目に付くようにする。
            throw new UnsendableEvent("아웃박스에 모르는 event_type 이 있다", null);
        }
        UserCreatedRequest dto;
        try {
            dto = objectMapper.readValue(event.getPayload(), UserCreatedRequest.class);
        } catch (Exception e) {
            throw new UnsendableEvent("페이로드를 읽지 못했다: " + e.getClass().getSimpleName(), e);
        }
        // 가입 확인 요청의 추적 문맥 아래에서 보낸다(docs/51 §4e).
        // [JA] 登録確認リクエストの追跡コンテキストの下で送る(docs/51 §4e)。
        return outboxTraceContext.resume(event.getTraceContext(), "outbox relay user-created",
                () -> userCreatedProducer.send(dto));
    }
}
