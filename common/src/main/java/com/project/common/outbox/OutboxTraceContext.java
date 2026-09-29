package com.project.common.outbox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * 아웃박스를 건너도 추적이 이어지게 한다 (docs/51 §4e).
 *
 * <h3>끊기던 자리</h3>
 *
 * <p>아웃박스는 "행을 쓰는 순간" 과 "보내는 순간" 이 다른 스레드다. 행은 요청(또는 리스너) 안에서
 * 쓰이지만, 보내는 것은 몇 초 뒤 스케줄러다. 추적 문맥은 스레드에 붙어 있으므로 스케줄러에는 없다 —
 * 그래서 Kafka 헤더에 실리는 추적은 <b>스케줄러가 새로 시작한 것</b>이었다. Zipkin 에서 "주문 결제"
 * 요청은 DB 에 행을 쓰고 끝났고, 결제 요청·결제 응답·주문 확정은 따로 떨어진 추적이었다.
 *
 * <h3>잇는 방법</h3>
 *
 * <ol>
 *   <li>{@link #capture()} — 행을 쓸 때 현재 추적 문맥을 전파 헤더 모양 그대로(W3C 면
 *       {@code traceparent}) 문자열로 만들어 행의 {@code trace_context} 에 넣는다.</li>
 *   <li>{@link #resume} — 릴레이가 보내기 직전에 그 문자열에서 스팬을 되살리고, 그 스팬을 현재
 *       스팬으로 둔 채 보낸다. KafkaTemplate 의 프로듀서 추적이 그 아래 자식이 되어 헤더에 원래
 *       추적이 실린다.</li>
 * </ol>
 *
 * <p>결과: HTTP 결제 요청 → (아웃박스) → 결제 요청 → pay-service 리스너 → (아웃박스) → 결제 응답 →
 * order-service 리스너가 <b>하나의 추적</b>이다.
 *
 * <p>추적이 없는 환경(테스트, 추적 빈이 없는 서비스)에서는 아무것도 하지 않는다 — {@code capture}
 * 는 null, {@code resume} 은 그냥 실행한다. 문자열이 깨져 있어도 발행을 막지 않는다. 추적은
 * 부가 정보다 — 추적 때문에 결제 요청이 안 나가면 안 된다.
 *
 * <p>[JA] アウトボックスをまたいでもトレースが途切れないようにする (docs/51 §4e)。
 *
 * <h3>途切れていた箇所</h3>
 *
 * <p>アウトボックスでは「行を書く瞬間」と「送る瞬間」が別スレッドである。行はリクエスト(またはリスナー)の中で
 * 書かれるが、送るのは数秒後のスケジューラである。トレースコンテキストはスレッドに紐づくのでスケジューラには存在しない —
 * そのため Kafka ヘッダーに載るトレースは<b>スケジューラが新たに開始したもの</b>だった。Zipkin 上で「注文決済」
 * リクエストは DB に行を書いて終わり、決済リクエスト・決済レスポンス・注文確定はそれぞれ別のトレースだった。
 *
 * <h3>つなぐ方法</h3>
 *
 * <ol>
 *   <li>{@link #capture()} — 行を書くときに現在のトレースコンテキストを伝搬ヘッダーの形そのまま(W3C なら
 *       {@code traceparent})で文字列にし、行の {@code trace_context} に入れる。</li>
 *   <li>{@link #resume} — リレーが送信する直前にその文字列からスパンを復元し、そのスパンをカレント
 *       スパンにしたまま送る。KafkaTemplate のプロデューサートレースがその子になり、ヘッダーに元の
 *       トレースが載る。</li>
 * </ol>
 *
 * <p>結果: HTTP 決済リクエスト → (アウトボックス) → 決済リクエスト → pay-service リスナー → (アウトボックス) → 決済レスポンス →
 * order-service リスナーが<b>一つのトレース</b>になる。
 *
 * <p>トレースのない環境(テスト、トレース Bean のないサービス)では何もしない — {@code capture}
 * は null、{@code resume} はそのまま実行する。文字列が壊れていても発行は妨げない。トレースは
 * 付加情報である — トレースのせいで決済リクエストが出ていかないことがあってはならない。
 */
public class OutboxTraceContext {

    /** 추적 없이 동작한다. 단위 테스트가 헬퍼·스케줄러를 직접 만들 때 쓴다. */
    // [JA] トレースなしで動作する。単体テストがヘルパー・スケジューラを直接生成するときに使う。
    public static final OutboxTraceContext NOOP = new OutboxTraceContext(() -> null, () -> null);

    /** 컬럼 길이. traceparent 는 55자, tracestate 가 붙어도 이 안에 든다. 넘으면 저장하지 않는다. */
    // [JA] カラム長。traceparent は 55 文字で、tracestate が付いてもこの範囲に収まる。超えたら保存しない。
    public static final int MAX_LENGTH = 512;

    private final Supplier<Tracer> tracer;
    private final Supplier<Propagator> propagator;

    /**
     * 공급자로 받는 이유: 자동 구성 순서상 이 빈이 추적 빈보다 먼저 만들어질 수 있다. 쓰는 순간에
     * 찾는다({@code ObjectProvider::getIfAvailable}).
     *
     * <p>[JA] サプライヤーで受け取る理由: 自動構成の順序上、この Bean がトレース Bean より先に作られることがある。使う瞬間に
     * 探す({@code ObjectProvider::getIfAvailable})。
     */
    public OutboxTraceContext(Supplier<Tracer> tracer, Supplier<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * @return 현재 추적 문맥의 전파 헤더를 {@code 이름=값} 줄로 이은 문자열. 추적이 없으면 null
     *         [JA] 現在のトレースコンテキストの伝搬ヘッダーを {@code 名前=値} の行で連結した文字列。トレースがなければ null
     */
    public String capture() {
        Tracer t = tracer.get();
        Propagator p = propagator.get();
        if (t == null || p == null) {
            return null;
        }
        Span current = t.currentSpan();
        if (current == null) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        p.inject(current.context(), headers, Map::put);
        if (headers.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        headers.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        String encoded = sb.toString();
        return encoded.length() > MAX_LENGTH ? null : encoded;
    }

    /**
     * 저장해 둔 추적 문맥 아래에서 {@code action} 을 실행한다(보통 Kafka 발행).
     *
     * <p>[JA] 保存しておいたトレースコンテキストの下で {@code action} を実行する(通常は Kafka 発行)。
     *
     * @param stored {@link #capture()} 가 만든 문자열. null·빈 값·깨진 값이면 그냥 실행한다
     *               [JA] {@link #capture()} が作った文字列。null・空・壊れた値ならそのまま実行する
     * @param name   되살린 스팬의 이름 — Jaeger 에 "outbox relay payment-request" 처럼 보인다
     *               [JA] 復元したスパンの名前 — Jaeger では "outbox relay payment-request" のように見える
     */
    public <T> T resume(String stored, String name, Supplier<T> action) {
        if (stored == null || stored.isBlank()) {
            return action.get();
        }
        return runUnder(decode(stored), name, action);
    }

    /**
     * 받은 메시지의 추적 헤더를 이어받아 {@code action} 을 실행한다 — <b>배치 리스너</b>용.
     *
     * <h3>🐛 배치 리스너에는 컨슈머 추적이 없다</h3>
     *
     * <p>Spring Kafka 의 컨슈머 추적({@code ContainerProperties.setObservationEnabled(true)})은 레코드
     * 단위 리스너에만 걸린다. 이 저장소의 컨테이너 팩토리는 <b>전부 배치</b>라, 켜 둔 설정이 어디서도
     * 동작하지 않았다. 그래서 pay-service 가 결제 요청을 받아 결제 응답 행을 쓸 때 현재 추적이 없었고
     * ({@link #capture()} 가 null), 결제 응답 릴레이는 새 추적으로 시작했다 — "결제 요청까지는 이어지고
     * 그 뒤가 끊긴다" 였다.
     *
     * <p>배치 안의 레코드마다 헤더에서 문맥을 꺼내 이 메서드로 감싼다. 헤더 값은 스프링의 기본 헤더
     * 변환기가 {@code byte[]} 로 넘기므로 문자열로 바꿔 읽는다.
     *
     * <p>[JA] 受信したメッセージのトレースヘッダーを引き継いで {@code action} を実行する — <b>バッチリスナー</b>用。
     *
     * <h3>🐛 バッチリスナーにはコンシューマートレースがない</h3>
     *
     * <p>Spring Kafka のコンシューマートレース({@code ContainerProperties.setObservationEnabled(true)})はレコード
     * 単位のリスナーにしか効かない。このリポジトリのコンテナファクトリは<b>すべてバッチ</b>なので、有効にした設定がどこでも
     * 機能していなかった。そのため pay-service が決済リクエストを受けて決済レスポンス行を書くときに現在のトレースがなく
     * ({@link #capture()} が null)、決済レスポンスのリレーは新しいトレースとして始まった — 「決済リクエストまではつながり、
     * その先が途切れる」状態だった。
     *
     * <p>バッチ内のレコードごとにヘッダーからコンテキストを取り出し、このメソッドで包む。ヘッダー値は Spring のデフォルトの
     * ヘッダーコンバーターが {@code byte[]} で渡すので、文字列に変換して読む。
     *
     * @param headers 한 레코드의 헤더({@code KafkaHeaders.BATCH_CONVERTED_HEADERS} 의 한 칸). null 이면 그냥 실행한다
     *                [JA] 一レコードのヘッダー({@code KafkaHeaders.BATCH_CONVERTED_HEADERS} の一要素)。null ならそのまま実行する
     */
    public <T> T continueFrom(Map<String, ?> headers, String name, Supplier<T> action) {
        if (headers == null || headers.isEmpty()) {
            return action.get();
        }
        return runUnder(carrier(headers), name, action);
    }

    /**
     * {@link #continueFrom} 의 스코프 모양 — 리스너 루프 본문을 <b>그대로 둔 채</b> 감쌀 수 있다.
     *
     * <pre>
     * for (int i = 0; i &lt; messages.size(); i++) {
     *     try (OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
     *             OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive cart-empty")) {
     *         ... 예전 본문(continue·체크 예외 그대로) ...
     *     } catch (Exception e) {
     *         throw new BatchListenerFailedException("…", e, i);
     *     }
     * }
     * </pre>
     *
     * <p>람다로 감싸는 {@code continueFrom} 과 달리 {@code continue}·체크 예외가 본문에 그대로 남는다.
     * 스팬은 스코프가 닫힐 때 끝난다 — {@code catch} 는 그 뒤라 거기서 찍는 로그에는 traceId 가 없다.
     * 헤더·추적기가 없거나 문맥이 깨져 있으면 아무것도 하지 않는 스코프다.
     *
     * <p>[JA] {@link #continueFrom} のスコープ版 — リスナーのループ本体を<b>そのままにして</b>包める。
     *
     * <pre>
     * for (int i = 0; i &lt; messages.size(); i++) {
     *     try (OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
     *             OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive cart-empty")) {
     *         ... 従来の本体(continue・チェック例外はそのまま) ...
     *     } catch (Exception e) {
     *         throw new BatchListenerFailedException("…", e, i);
     *     }
     * }
     * </pre>
     *
     * <p>ラムダで包む {@code continueFrom} と違い、{@code continue}・チェック例外が本体にそのまま残る。
     * スパンはスコープが閉じるときに終わる — {@code catch} はその後なので、そこで出すログには traceId がない。
     * ヘッダー・トレーサーがない、またはコンテキストが壊れている場合は何もしないスコープになる。
     */
    public TraceScope openFrom(Map<String, ?> headers, String name) {
        Tracer t = tracer.get();
        Propagator p = propagator.get();
        if (headers == null || headers.isEmpty() || t == null || p == null) {
            return TraceScope.NOOP;
        }
        Map<String, String> carrier = carrier(headers);
        if (carrier.isEmpty()) {
            return TraceScope.NOOP;
        }
        Span span;
        try {
            span = p.extract(carrier, Map::get).name(name).start();
        } catch (RuntimeException e) {
            return TraceScope.NOOP;   // 깨진 문맥 때문에 처리를 막지 않는다 ／ [JA] 壊れたコンテキストのせいで処理を止めない
        }
        Tracer.SpanInScope inScope = t.withSpan(span);
        return () -> {
            try {
                inScope.close();
            } finally {
                span.end();
            }
        };
    }

    /** {@link #openFrom} 이 돌려주는 스코프. {@code close()} 는 예외를 던지지 않는다. */
    // [JA] {@link #openFrom} が返すスコープ。{@code close()} は例外を投げない。
    @FunctionalInterface
    public interface TraceScope extends AutoCloseable {
        TraceScope NOOP = () -> { };

        @Override
        void close();
    }

    /** 헤더 값은 스프링의 헤더 매퍼가 {@code byte[]} 로, 때로는 문자열로 넘긴다. 둘 다 읽는다. */
    // [JA] ヘッダー値は Spring のヘッダーマッパーが {@code byte[]} で、ときには文字列で渡す。どちらも読む。
    private static Map<String, String> carrier(Map<String, ?> headers) {
        Map<String, String> carrier = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (v instanceof byte[] bytes) {
                carrier.put(k, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            } else if (v instanceof String s) {
                carrier.put(k, s);
            }
        });
        return carrier;
    }

    /**
     * 리스너가 받은 메시지 헤더({@code @Headers})에서 i 번째 레코드의 헤더를 꺼낸다.
     *
     * <h3>🐛 배치 헤더가 오지 않았다</h3>
     *
     * <p>{@code List<...>} 를 받는 리스너라도 컨테이너 팩토리에 {@code setBatchToRecordAdapter(...)} 가 있으면
     * 스프링은 배치를 <b>레코드 하나씩</b> 넘긴다 — 그때 헤더는 그 레코드의 것이고
     * {@code BATCH_CONVERTED_HEADERS} 는 없다. 배치 헤더만 찾던 첫 구현은 운영에서 늘 null 을 받아
     * 아무것도 잇지 못했다(pay-service·order-service 가 이 모양이다). 두 모양을 다 받는다:
     *
     * <ul>
     *   <li>배치 변환 헤더가 있으면 그 목록의 i 번째</li>
     *   <li>없으면 메시지 헤더 자체가 레코드 하나의 헤더다 — 그 경우 목록에는 한 건뿐이라 i 는 0 이다</li>
     * </ul>
     *
     * <p>[JA] リスナーが受け取ったメッセージヘッダー({@code @Headers})から i 番目のレコードのヘッダーを取り出す。
     *
     * <h3>🐛 バッチヘッダーが来なかった</h3>
     *
     * <p>{@code List<...>} を受け取るリスナーであっても、コンテナファクトリに {@code setBatchToRecordAdapter(...)} があれば
     * Spring はバッチを<b>レコード一件ずつ</b>渡す — そのときヘッダーはそのレコードのものであり、
     * {@code BATCH_CONVERTED_HEADERS} は存在しない。バッチヘッダーだけを探していた最初の実装は本番で常に null を受け取り、
     * 何もつなげなかった(pay-service・order-service がこの形である)。両方の形を受け付ける:
     *
     * <ul>
     *   <li>バッチ変換ヘッダーがあれば、そのリストの i 番目</li>
     *   <li>なければメッセージヘッダー自体が一レコードのヘッダーである — その場合リストには一件しかないので i は 0 である</li>
     * </ul>
     */
    public static Map<String, ?> recordHeaders(Map<String, ?> messageHeaders, int i) {
        if (messageHeaders == null) {
            return null;
        }
        Object batch = messageHeaders.get(org.springframework.kafka.support.KafkaHeaders.BATCH_CONVERTED_HEADERS);
        if (batch instanceof java.util.List<?> list) {
            @SuppressWarnings("unchecked")
            java.util.List<? extends Map<String, ?>> perRecord = (java.util.List<? extends Map<String, ?>>) list;
            return headersAt(perRecord, i);
        }
        return i == 0 ? messageHeaders : null;
    }

    /** 배치 헤더 목록의 i 번째 — 없으면 null. 리스너가 인덱스로 부른다. */
    // [JA] バッチヘッダーリストの i 番目 — なければ null。リスナーがインデックスで呼ぶ。
    public static Map<String, ?> headersAt(java.util.List<? extends Map<String, ?>> batchHeaders, int i) {
        return batchHeaders == null || i < 0 || i >= batchHeaders.size() ? null : batchHeaders.get(i);
    }

    private <T> T runUnder(Map<String, String> carrier, String name, Supplier<T> action) {
        Tracer t = tracer.get();
        Propagator p = propagator.get();
        if (t == null || p == null || carrier.isEmpty()) {
            return action.get();
        }
        Span span;
        try {
            span = p.extract(carrier, Map::get).name(name).start();
        } catch (RuntimeException e) {
            return action.get();   // 깨진 문맥 때문에 처리를 막지 않는다 ／ [JA] 壊れたコンテキストのせいで処理を止めない
        }
        try (Tracer.SpanInScope ignored = t.withSpan(span)) {
            return action.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    static Map<String, String> decode(String stored) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : stored.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                headers.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return headers;
    }
}
