package com.project.common.logging;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.util.ClassUtils;

/**
 * 작업을 스레드풀로 넘길 때 로그 문맥(MDC)을 함께 넘긴다.
 *
 * <p>MDC 는 스레드마다 따로다. {@code @Async} 메일 발송·SSE 전송·상품 가져오기·색인 작업이
 * 풀의 스레드에서 돌면 traceId·userId 가 비어서, 그 작업의 로그를 요청과 이을 수 없었다 —
 * 가입 확인 메일이 안 갔다는 문의에 "그 요청의 메일 로그" 를 찾을 방법이 없었다.
 *
 * <p><b>넘길 때(호출 스레드)</b>의 MDC 를 복사해 <b>돌 때(작업 스레드)</b>에 붙이고, 끝나면 작업
 * 스레드의 원래 MDC 로 되돌린다. 되돌리지 않으면 재사용되는 풀 스레드가 남의 traceId 를 달고
 * 다음 작업을 돈다.
 *
 * <p>스프링의 {@code ThreadPoolTaskExecutor} 는 {@code setTaskDecorator} 로, 직접 만든
 * {@code ExecutorService} 는 {@link #wrap} 으로 쓴다. 공통 자동 구성이 이것을 빈으로 등록하므로
 * 스프링 부트의 기본 {@code @Async} 실행기에는 따로 하지 않아도 붙는다.
 *
 * <p>뼈대는 ruke79/facebook 의 로깅 실습 서비스에서 옮겼다.
 *
 * <p>[JA] タスクをスレッドプールに渡すとき、ログコンテキスト(MDC)も一緒に渡す。
 *
 * <p>MDC はスレッドごとに別々である。{@code @Async} のメール送信・SSE 送信・商品の取り込み・インデックス作業が
 * プールのスレッドで動くと traceId・userId が空になり、その作業のログをリクエストと結び付けられなかった —
 * 会員登録の確認メールが届かないという問い合わせに対し、「そのリクエストのメールログ」を探す方法がなかった。
 *
 * <p><b>渡すとき(呼び出しスレッド)</b>の MDC をコピーして<b>実行するとき(ワーカースレッド)</b>に付け、終わったら
 * ワーカースレッドの元の MDC に戻す。戻さないと、再利用されるプールのスレッドが他人の traceId を付けたまま
 * 次のタスクを実行してしまう。
 *
 * <p>Spring の {@code ThreadPoolTaskExecutor} は {@code setTaskDecorator} で、自前で作った
 * {@code ExecutorService} は {@link #wrap} で使う。共通の自動構成がこれを Bean として登録するので、
 * Spring Boot のデフォルトの {@code @Async} エグゼキューターには別途何もしなくても適用される。
 *
 * <p>骨格は ruke79/facebook のロギング演習サービスから移した。
 */
public class MdcTaskDecorator implements TaskDecorator {

    private static final MdcTaskDecorator INSTANCE = new MdcTaskDecorator();

    /** 직접 만든 {@code ExecutorService} 에 넘길 작업을 감싼다. */
    // [JA] 自前で作った {@code ExecutorService} に渡すタスクをラップする。
    public static Runnable wrap(Runnable task) {
        return INSTANCE.decorate(task);
    }

    /** context-propagation(Micrometer)이 있는가 — 있으면 현재 관측(추적 문맥)도 넘긴다. ／ [JA] あれば現在の観測(トレース文脈)も渡す。 */
    private static final boolean CONTEXT_PROPAGATION =
            ClassUtils.isPresent("io.micrometer.context.ContextSnapshotFactory", MdcTaskDecorator.class.getClassLoader());

    /**
     * 🐛 예전에는 MDC 만 복사했다. 작업 스레드에는 부모 관측이 없어, 그 안에서 관측을 켠 KafkaTemplate 으로 보내면(가입 메일 요청)
     * <b>새 루트 추적</b>이 시작되고 OTel 브리지가 스코프를 닫으며 MDC 의 traceId 를 지웠다 — 이 데코레이터의 목적인 "가입 메일을 요청과
     * 이어 찾기" 가 바로 그 경로에서 실패했다. 이제 context-propagation 스냅샷(Boot 의 ContextPropagatingTaskDecorator 와 같은 방식)
     * 으로 현재 관측을 함께 넘긴다.
     *
     * <p>[JA] 以前は MDC だけをコピーしていた。作業スレッドには親の観測がなく、そこで観測を有効にした KafkaTemplate で送ると新しい
     * ルートトレースが始まり、traceId が切れていた。今は context-propagation のスナップショットで現在の観測も渡す。
     */
    /**
     * context-propagation 이 없으면 기동 때 한 번 알린다 — 🐛 예전에는 조용히 MDC 만 넘겨, 작업 스레드의 추적이 끊겨도(새 루트 추적)
     * 아무 표시가 없었다. 의존성(io.micrometer:context-propagation)이 빠진 빌드를 로그로 알아챈다.
     *
     * @return 있으면 true
     */
    // [JA] context-propagation がなければ起動時に一度知らせる — 以前は黙って MDC だけを渡していた。
    static boolean warnIfNoContextPropagation() {
        if (!CONTEXT_PROPAGATION) {
            org.slf4j.LoggerFactory.getLogger(MdcTaskDecorator.class).warn(
                    "context-propagation 이 클래스패스에 없다 — 작업 스레드로 추적 문맥이 넘어가지 않는다(MDC 만). io.micrometer:context-propagation 을 더한다.");
        }
        return CONTEXT_PROPAGATION;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        Runnable withMdc = withMdc(runnable);
        return CONTEXT_PROPAGATION ? Snapshots.wrap(withMdc) : withMdc;
    }

    /** context-propagation 클래스를 이 안에서만 참조한다 — 없으면 이 클래스는 읽히지 않는다. ／ [JA] 無ければこのクラスは読まれない。 */
    private static final class Snapshots {
        private static final io.micrometer.context.ContextSnapshotFactory FACTORY =
                io.micrometer.context.ContextSnapshotFactory.builder().build();

        static Runnable wrap(Runnable task) {
            return FACTORY.captureAll().wrap(task);
        }
    }

    private static Runnable withMdc(Runnable runnable) {
        Map<String, String> caller = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> workerBackup = MDC.getCopyOfContextMap();
            try {
                if (caller != null) {
                    MDC.setContextMap(caller);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                if (workerBackup != null) {
                    MDC.setContextMap(workerBackup);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
