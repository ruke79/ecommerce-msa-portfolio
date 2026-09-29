package com.project.common.outbox;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 아웃박스 행이 <b>커밋되자마자</b> 릴레이를 한 번 돌린다 (docs/56).
 *
 * <h3>왜</h3>
 *
 * <p>릴레이는 폴러다 — order 의 결제 요청, pay 의 결제 응답 모두 10초 간격이었다. 결제 사가는 그
 * 폴러를 <b>두 번</b> 건너므로, 결제를 마친 사용자는 주문이 PAID 가 될 때까지 평균 10초, 운이
 * 나쁘면 20초를 기다렸다(실측 약 12초). 간격을 줄이면 빈 조회만 늘어난다 — 두 서비스가 1초마다
 * {@code FOR UPDATE SKIP LOCKED} 를 날리지만 대부분 빈손이다.
 *
 * <h3>어떻게</h3>
 *
 * <p>행을 쓴 쪽이 {@link #afterCommit()} 을 부른다. 트랜잭션이 <b>커밋된 뒤에</b> 별도 스레드가
 * 릴레이({@code processOutboxMessage()})를 한 번 돌린다. 커밋 전에 돌리면 행이 아직 보이지 않고,
 * 롤백되면 돌릴 것이 없다 — 그래서 커밋 콜백이다.
 *
 * <ul>
 *   <li><b>폴러는 그대로 둔다.</b> 이것은 지름길일 뿐이다. 커밋 직후 파드가 죽거나, 브로커가
 *       흔들려 발행이 실패하거나, 스레드가 거절돼도 행은 STARTED 로 남고 다음 폴링이 가져간다.
 *       전달 보장은 여전히 아웃박스 + 폴러가 진다.</li>
 *   <li><b>폴러와 겹쳐도 안전하다.</b> 둘 다 같은 {@code FOR UPDATE SKIP LOCKED} 선점을 거치므로
 *       한 행은 한 쪽만 가져간다 — 레플리카 두 개가 동시에 도는 것과 같은 상황이다.</li>
 *   <li><b>몰려도 한 줄로 선다.</b> 스레드는 하나고, 돌고 있는 중에 들어온 요청은 "끝나면 한 번
 *       더" 로 합쳐진다. 주문 100건이 한꺼번에 커밋돼도 릴레이가 100번 겹쳐 돌지 않는다 — 한
 *       번 도는 동안 배치로 가져가고, 그 사이 쌓인 것은 다음 한 번이 가져간다.</li>
 *   <li><b>요청 스레드를 붙잡지 않는다.</b> 커밋 콜백은 큐에 넣고 바로 돌아온다. Kafka 동기 발행을
 *       기다리는 것은 릴레이 스레드다.</li>
 * </ul>
 *
 * <p>스프링 빈이 아니다 — 모듈이 자기 스케줄러를 넘겨 만든다. common 에는 spring-tx 가
 * compileOnly 라, 아웃박스가 없는 모듈이 이 클래스를 읽을 일이 없어야 한다.
 *
 * <p>[JA] アウトボックス行が<b>コミットされた直後に</b>リレーを一度だけ回す (docs/56)。
 *
 * <h3>なぜ</h3>
 *
 * <p>リレーはポーラーである — order の決済リクエストも pay の決済レスポンスも 10 秒間隔だった。決済 Saga は
 * そのポーラーを<b>二回</b>またぐため、決済を終えたユーザーは注文が PAID になるまで平均 10 秒、運が
 * 悪ければ 20 秒待たされていた(実測で約 12 秒)。間隔を縮めても空振りの照会が増えるだけである — 二つの
 * サービスが 1 秒ごとに {@code FOR UPDATE SKIP LOCKED} を投げるが、ほとんどは空振りになる。
 *
 * <h3>どうやって</h3>
 *
 * <p>行を書いた側が {@link #afterCommit()} を呼ぶ。トランザクションが<b>コミットされた後に</b>別スレッドが
 * リレー({@code processOutboxMessage()})を一度回す。コミット前に回すと行はまだ見えず、
 * ロールバックされれば回すものがない — だからコミットコールバックなのである。
 *
 * <ul>
 *   <li><b>ポーラーはそのまま残す。</b>これは近道にすぎない。コミット直後に Pod が落ちても、ブローカーが
 *       不安定で発行に失敗しても、スレッドが拒否されても、行は STARTED のまま残り次のポーリングが拾う。
 *       配信保証は引き続きアウトボックス + ポーラーが担う。</li>
 *   <li><b>ポーラーと重なっても安全である。</b>どちらも同じ {@code FOR UPDATE SKIP LOCKED} による確保を経るので、
 *       一つの行はどちらか一方しか取らない — レプリカ二つが同時に回るのと同じ状況である。</li>
 *   <li><b>集中しても一列に並ぶ。</b>スレッドは一つで、実行中に来た要求は「終わったらもう一回」に
 *       まとめられる。注文 100 件が一度にコミットされてもリレーが 100 回重なって回ることはない — 一回
 *       回る間にバッチで取り、その間に溜まった分は次の一回が取る。</li>
 *   <li><b>リクエストスレッドを拘束しない。</b>コミットコールバックはキューに入れてすぐ戻る。Kafka の同期発行を
 *       待つのはリレースレッドである。</li>
 * </ul>
 *
 * <p>Spring Bean ではない — モジュールが自分のスケジューラを渡して生成する。common では spring-tx が
 * compileOnly なので、アウトボックスを持たないモジュールがこのクラスを読み込むことがあってはならない。
 */
public class OutboxRelayTrigger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayTrigger.class);

    /** 아무것도 하지 않는다. 단위 테스트가 헬퍼를 직접 만들 때, 그리고 설정으로 껐을 때. */
    // [JA] 何もしない。単体テストがヘルパーを直接生成するとき、および設定で無効化したときに使う。
    public static final OutboxRelayTrigger DISABLED = new OutboxRelayTrigger("disabled", () -> { }, false, null);

    private static final int IDLE = 0;
    /** 돌고 있다. */
    // [JA] 実行中である。
    private static final int RUNNING = 1;
    /** 돌고 있는 중에 새 행이 커밋됐다 — 끝나면 한 번 더 돈다. */
    // [JA] 実行中に新しい行がコミットされた — 終わったらもう一回回す。
    private static final int RERUN = 2;

    private final String name;
    private final Runnable relay;
    private final boolean enabled;
    private final ExecutorService executor;
    private final AtomicInteger state = new AtomicInteger(IDLE);

    /**
     * @param name    스레드 이름과 로그에 쓴다 — 예: {@code payment-outbox}
     *                [JA] スレッド名とログに使う — 例: {@code payment-outbox}
     * @param relay   릴레이 한 번. 스케줄러의 {@code processOutboxMessage()} 를 <b>프록시를 거쳐</b>
     *                불러야 한다 — {@code @Transactional} 이 없으면 선점 잠금이 곧바로 풀린다
     *                [JA] リレー一回分。スケジューラの {@code processOutboxMessage()} を<b>プロキシ経由で</b>
     *                呼ばなければならない — {@code @Transactional} がないと確保ロックがすぐに解放される
     * @param enabled false 면 {@link #afterCommit()} 이 아무것도 하지 않는다 — 폴러만 남는다
     *                [JA] false なら {@link #afterCommit()} は何もしない — ポーラーだけが残る
     */
    public OutboxRelayTrigger(String name, Runnable relay, boolean enabled) {
        this(name, relay, enabled, enabled ? singleDaemonThread(name) : null);
    }

    /** 테스트가 실행기를 바꿔 끼운다. */
    // [JA] テストが Executor を差し替える。
    OutboxRelayTrigger(String name, Runnable relay, boolean enabled, ExecutorService executor) {
        this.name = name;
        this.relay = relay;
        this.enabled = enabled;
        this.executor = executor;
    }

    /**
     * 아웃박스 행을 쓴 직후 부른다. 트랜잭션 안이면 <b>커밋된 뒤에</b>, 밖이면(이미 커밋됐다) 바로
     * 릴레이를 예약한다. 롤백되면 아무 일도 없다.
     *
     * <p>[JA] アウトボックス行を書いた直後に呼ぶ。トランザクション内なら<b>コミットされた後に</b>、外なら(既にコミット済み)
     * 即座にリレーを予約する。ロールバックされた場合は何も起きない。
     */
    public void afterCommit() {
        if (!enabled) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kick();
                }
            });
        } else {
            kick();
        }
    }

    /** 릴레이를 예약한다. 이미 돌고 있으면 "끝나면 한 번 더" 로 합친다. */
    // [JA] リレーを予約する。既に実行中なら「終わったらもう一回」にまとめる。
    void kick() {
        for (;;) {
            int s = state.get();
            if (s == IDLE) {
                if (state.compareAndSet(IDLE, RUNNING)) {
                    submit();
                    return;
                }
            } else if (s == RUNNING) {
                if (state.compareAndSet(RUNNING, RERUN)) {
                    return;
                }
            } else {
                return;   // 이미 한 번 더 돌기로 했다 ／ [JA] 既にもう一回回すことになっている
            }
        }
    }

    private void submit() {
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException e) {
            // 종료 중이다. 행은 STARTED 로 남아 있고 폴러(또는 다음 기동)가 가져간다.
            // [JA] シャットダウン中である。行は STARTED のまま残り、ポーラー(または次回起動)が拾う。
            state.set(IDLE);
            log.debug("아웃박스 즉시 릴레이 생략 — 실행기 종료 중: {}", name);
        }
    }

    private void drain() {
        for (;;) {
            try {
                relay.run();
            } catch (RuntimeException e) {
                // 실패해도 행은 STARTED 다 — 폴러가 다시 가져간다. 여기서 던지면 상태가 RUNNING 에
                // 걸려 이후의 즉시 릴레이가 전부 멈춘다.
                // [JA] 失敗しても行は STARTED である — ポーラーが再度拾う。ここで例外を投げると状態が RUNNING で
                //      固まり、以降の即時リレーがすべて止まる。
                log.warn("아웃박스 즉시 릴레이 실패 — 폴러가 이어받는다: {} ({})", name, e.toString());
            } catch (Error e) {
                state.set(IDLE);
                throw e;
            }
            if (state.compareAndSet(RUNNING, IDLE)) {
                return;
            }
            // RERUN — 도는 사이 새 행이 커밋됐다. RERUN 에서 빠져나가는 것은 이 스레드뿐이다.
            // [JA] RERUN — 実行中に新しい行がコミットされた。RERUN から抜け出すのはこのスレッドだけである。
            state.set(RUNNING);
        }
    }

    @Override
    public void close() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService singleDaemonThread(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "outbox-relay-" + name);
            t.setDaemon(true);
            return t;
        });
    }
}
