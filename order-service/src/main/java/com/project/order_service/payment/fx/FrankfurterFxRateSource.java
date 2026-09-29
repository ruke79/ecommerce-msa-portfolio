package com.project.order_service.payment.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Frankfurter(ECB 참조 환율) — 키 없는 공개 API.
 *
 * <p>{@code GET /latest?from=USD} 가 상점 통화 기준의 환율 표를 준다:
 * <pre>{"amount":1.0,"base":"USD","date":"2026-09-18","rates":{"JPY":147.8,"KRW":1352.4,…}}</pre>
 * ECB 는 <b>평일 하루 한 번</b>(16:00 CET) 갱신한다. 그래서 캐시 TTL 이 한 시간이어도
 * 하루 한 번만 값이 바뀌고, 주말·공휴일은 금요일 값이다. 실결제의 청구액을 정하기에는
 * 거친 값이지만 — 어차피 청구액은 <b>주문에 고정</b>되고({@code fx_rate}) 확인·환불은
 * 그 값과 대조하므로, 환율의 정밀도는 정산 손익의 문제이지 정합성의 문제가 아니다.
 *
 * <h3>캐시와 실패</h3>
 *
 * <ul>
 *   <li>기준 통화마다 표 하나를 TTL 동안 들고 있다. 주문마다 API 를 부르지 않는다.</li>
 *   <li>갱신에 실패하면 <b>옛 표를 그대로 쓴다</b>(있으면). 없으면 {@code empty} 를 돌려주고
 *       정책이 고정 환율로 넘어간다. 어느 쪽이든 주문 생성은 죽지 않는다.</li>
 *   <li>실패 뒤 {@link #RETRY_AFTER} 동안은 다시 부르지 않는다. 장애 중에 주문마다
 *       타임아웃을 기다리게 하지 않기 위해서다.</li>
 * </ul>
 *
 * <p>🔒 이 API 에는 자격증명이 없다. 요청에 주문·고객 정보도 싣지 않는다 — 통화 코드뿐이다.
 */
@Slf4j
public class FrankfurterFxRateSource implements FxRateSource {

    /** 실패한 뒤 다시 시도하기까지. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(1);

    /** {@code fx_rate numeric(19,6)} 에 맞춘다. */
    private static final int RATE_SCALE = 6;

    private final RestClient client;
    private final Duration ttl;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    /** 한 기준 통화의 환율 표. {@code nextRefresh} 전에는 API 를 부르지 않는다. */
    private record Snapshot(Map<String, BigDecimal> rates, Instant fetchedAt, Instant nextRefresh) {
    }

    public FrankfurterFxRateSource(RestClient client, Duration ttl) {
        this(client, ttl, Clock.systemUTC());
    }

    FrankfurterFxRateSource(RestClient client, Duration ttl, Clock clock) {
        this.client = client;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public Optional<BigDecimal> rate(String base, String quote) {
        String from = normalize(base);
        String to = normalize(quote);
        if (from.isEmpty() || to.isEmpty()) {
            return Optional.empty();
        }
        if (from.equals(to)) {
            return Optional.of(BigDecimal.ONE);
        }
        Snapshot snapshot = cache.compute(from, (key, current) -> refreshIfDue(key, current));
        return Optional.ofNullable(snapshot.rates().get(to));
    }

    private Snapshot refreshIfDue(String base, Snapshot current) {
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.nextRefresh())) {
            return current;
        }
        Map<String, BigDecimal> fresh = fetch(base);
        if (fresh != null) {
            return new Snapshot(fresh, now, now.plus(ttl));
        }
        // 실패 — 옛 표가 있으면 그대로(오래됐다고 경고), 없으면 빈 표. 둘 다 잠시 뒤 재시도.
        if (current != null && !current.rates().isEmpty()) {
            log.warn("실시간 환율 갱신 실패 — {} 기준 표를 {} 에 받은 값으로 계속 쓴다",
                    base, current.fetchedAt());
            return new Snapshot(current.rates(), current.fetchedAt(), now.plus(RETRY_AFTER));
        }
        return new Snapshot(Map.of(), now, now.plus(RETRY_AFTER));
    }

    /** 성공하면 환율 표, 실패하면 {@code null}. 예외는 여기서 멈춘다. */
    private Map<String, BigDecimal> fetch(String base) {
        try {
            String body = client.get()
                    .uri(uri -> uri.path("/latest").queryParam("from", base).build())
                    .retrieve()
                    .body(String.class);
            Map<String, BigDecimal> rates = parse(body, base);
            log.info("실시간 환율 갱신: base={} 통화 {}개 (date={})", base, rates.size(), dateOf(body));
            return rates;
        } catch (RuntimeException e) {
            // 본문은 싣지 않는다 — 장애 메시지에 HTML 한 페이지가 들어오는 일이 흔하다.
            log.warn("실시간 환율 조회 실패: base={} {}", base, e.getClass().getSimpleName());
            return null;
        }
    }

    private Map<String, BigDecimal> parse(String body, String base) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("환율 응답이 JSON 이 아니다", e);
        }
        if (root == null || !root.hasNonNull("rates") || !root.get("rates").isObject()) {
            throw new IllegalStateException("환율 응답에 rates 가 없다");
        }
        String responseBase = root.path("base").asText("");
        if (!base.equalsIgnoreCase(responseBase)) {
            throw new IllegalStateException("환율 응답의 기준 통화가 다르다: " + responseBase);
        }
        Map<String, BigDecimal> rates = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.get("rates").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isNumber()) {
                continue;
            }
            BigDecimal rate = e.getValue().decimalValue().setScale(RATE_SCALE, RoundingMode.HALF_UP);
            if (rate.signum() > 0) {
                rates.put(normalize(e.getKey()), rate);
            }
        }
        return Map.copyOf(rates);
    }

    private String dateOf(String body) {
        try {
            return mapper.readTree(body).path("date").asText("?");
        } catch (java.io.IOException e) {
            return "?";
        }
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
