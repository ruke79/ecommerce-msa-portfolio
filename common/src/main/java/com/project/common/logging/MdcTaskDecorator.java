package com.project.common.logging;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

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

    @Override
    public Runnable decorate(Runnable runnable) {
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
