package com.project.common.featureflag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.LongSupplier;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FlagNotFoundError;
import dev.openfeature.sdk.exceptions.TypeMismatchError;
import lombok.extern.slf4j.Slf4j;

/**
 * 파일에서 플래그를 읽는 OpenFeature 공급자(docs/74).
 *
 * <p>k8s 에서는 ConfigMap {@code feature-flags} 를 <b>디렉터리로</b> 마운트한 파일을 읽는다. kubelet 은 ConfigMap 이
 * 바뀌면 마운트된 파일을 1분 안팎에 바꿔 놓는다 — 그래서 플래그를 바꾸는 데 <b>파드 재시작이 필요 없다</b>.
 * (subPath 마운트는 갱신되지 않는다. 매니페스트가 디렉터리 마운트인 이유다.)
 *
 * <p>읽기 규칙:
 * <ul>
 *   <li>평가할 때 마지막 확인에서 {@code reloadInterval} 이 지났으면 파일을 다시 읽는다. 내용이 같으면 파싱하지 않는다.
 *       따로 도는 스레드가 없다 — 평가가 없으면 읽지도 않는다</li>
 *   <li>새 내용이 틀렸으면 <b>직전 정의를 그대로 쓴다</b>(경고 한 번). 오타 하나로 모든 플래그가 기본값으로
 *       떨어지면 그것이 곧 장애다</li>
 *   <li>파일이 처음부터 없으면 빈 정의 — 모든 평가가 FLAG_NOT_FOUND 가 되어 <b>코드의 기본값</b>으로 돈다.
 *       그래서 코드의 기본값은 언제나 "플래그가 생기기 전의 동작" 이어야 한다</li>
 * </ul>
 *
 * <p>[JA] ファイルからフラグを読む OpenFeature プロバイダー(docs/74)。k8s では ConfigMap {@code feature-flags} を
 * <b>ディレクトリとして</b>マウントしたファイルを読む。kubelet は ConfigMap が変わるとマウントされたファイルを
 * 1 分前後で差し替える — そのためフラグの変更に<b>Pod の再起動は不要</b>である(subPath マウントは更新されない)。
 * 新しい内容が誤っていれば<b>直前の定義をそのまま使う</b>。ファイルが最初からなければ空の定義 — すべての評価が
 * FLAG_NOT_FOUND となり<b>コードのデフォルト値</b>で動く。だからコードのデフォルト値は常に「フラグができる前の
 * 動作」でなければならない。
 */
@Slf4j
public class FileFlagProvider implements FeatureProvider {

    public static final String NAME = "file";

    private final Path file;
    private final long reloadIntervalNanos;
    private final LongSupplier nanoClock;

    private volatile FlagDefinitions current = FlagDefinitions.EMPTY;
    private volatile byte[] lastBytes;
    private volatile byte[] lastRejected;
    private volatile long lastCheck;
    private volatile boolean checkedOnce;

    public FileFlagProvider(Path file, Duration reloadInterval) {
        this(file, reloadInterval, System::nanoTime);
    }

    FileFlagProvider(Path file, Duration reloadInterval, LongSupplier nanoClock) {
        this.file = file;
        this.reloadIntervalNanos = reloadInterval.toNanos();
        this.nanoClock = nanoClock;
    }

    @Override
    public Metadata getMetadata() {
        return () -> NAME;
    }

    @Override
    public void initialize(EvaluationContext evaluationContext) {
        reloadIfDue(true);
        log.info("기능 플래그 파일 {} — 플래그 {}개", file, current.all().size());
    }

    /** 지금 쓰는 정의. 가드 테스트와 진단용. */
    public FlagDefinitions definitions() {
        reloadIfDue(false);
        return current;
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        return evaluate(key, defaultValue, ctx, Boolean.class);
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        return evaluate(key, defaultValue, ctx, String.class);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Long> l = evaluate(key, defaultValue == null ? null : defaultValue.longValue(), ctx, Long.class);
        return ProviderEvaluation.<Integer>builder()
                .value(l.getValue() == null ? null : Math.toIntExact(l.getValue()))
                .variant(l.getVariant()).reason(l.getReason()).build();
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        return evaluate(key, defaultValue, ctx, Double.class);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        throw new TypeMismatchError("파일 공급자는 객체 값을 지원하지 않는다: " + key);
    }

    private <T> ProviderEvaluation<T> evaluate(String key, T defaultValue, EvaluationContext ctx, Class<T> type) {
        FlagDefinitions defs = definitions();
        FlagDefinitions.Flag flag = defs.get(key);
        if (flag == null) {
            throw new FlagNotFoundError("플래그가 정의 파일에 없다: " + key);
        }
        if (!flag.enabled()) {
            return ProviderEvaluation.<T>builder().value(defaultValue).reason(Reason.DISABLED.name()).build();
        }
        FlagDefinitions.Resolution r = flag.resolve(ctx == null ? null : ctx.getTargetingKey());
        Object raw = flag.variants().get(r.variant());
        if (!type.isInstance(raw)) {
            throw new TypeMismatchError("플래그 " + key + " 의 변형 '" + r.variant() + "' 은 " + type.getSimpleName() + " 가 아니다");
        }
        return ProviderEvaluation.<T>builder().value(type.cast(raw)).variant(r.variant()).reason(r.reason()).build();
    }

    private void reloadIfDue(boolean force) {
        long now = nanoClock.getAsLong();
        if (!force && checkedOnce && now - lastCheck < reloadIntervalNanos) {
            return;
        }
        synchronized (this) {
            if (!force && checkedOnce && now - lastCheck < reloadIntervalNanos) {
                return;
            }
            lastCheck = now;
            checkedOnce = true;
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (NoSuchFileException e) {
                if (lastBytes != null) {
                    log.warn("기능 플래그 파일 {} 이 사라졌다 — 직전 정의를 유지한다", file);
                } else if (force) {
                    log.warn("기능 플래그 파일 {} 이 없다 — 모든 플래그가 코드의 기본값으로 돈다", file);
                }
                return;
            } catch (Exception e) {
                log.warn("기능 플래그 파일 {} 을 읽지 못했다 — 직전 정의를 유지한다: {}", file, e.toString());
                return;
            }
            if (Arrays.equals(bytes, lastBytes)) {
                return;
            }
            try {
                FlagDefinitions next = FlagDefinitions.parse(new String(bytes, StandardCharsets.UTF_8));
                current = next;
                lastBytes = bytes;
                lastRejected = null;
                log.info("기능 플래그 정의를 다시 읽었다 — 플래그 {}개", next.all().size());
            } catch (IllegalArgumentException e) {
                if (!Arrays.equals(bytes, lastRejected)) {
                    lastRejected = bytes;
                    log.warn("기능 플래그 파일 {} 이 틀렸다 — 직전 정의를 유지한다: {}", file, e.getMessage());
                }
            }
        }
    }
}
