package com.project.gatewayserver.resolver;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 유입 제한(RequestRateLimiter)의 Redis 경로를 기동 때 한 번 데운다 — 첫 손님 요청이 연결 · 인증 · 스크립트 적재를 떠안지 않게.
 *
 * <h3>왜</h3>
 *
 * <p>Redis 명령 타임아웃은 500ms 이고(B-84 — 기본 60초면 Redis 가 잠깐 끊겨도 모든 요청이 멈춘다), 넘으면 RedisRateLimiter 는
 * 요청을 <b>통과</b>시킨다(fail-open). 그런데 막 뜬 게이트웨이의 첫 명령들은 Redis 연결 · AUTH · 스크립트 적재(EVALSHA 가
 * NOSCRIPT → EVAL)를 함께 치르고, 그 동안 들어온 요청은 줄을 선다. 2026-10-24 운영 배포에서 스모크 3번(동시 70발)이
 * 429 를 하나도 받지 못해 배포가 자동 복귀했다 — 전 서비스가 한꺼번에 뜬 단일 노드에서 첫 명령들이 상한을 넘어 모두 통과했다.
 * 같은 일은 배포 · 재시작 직후의 실제 손님에게도 생긴다(그 몇 초 동안 제한이 없다).
 *
 * <h3>어떻게</h3>
 *
 * <p>{@link ApplicationRunner} 는 준비 상태(readiness)가 ACCEPTING_TRAFFIC 이 되기 <b>전</b>에 돈다. 여기서 유입 제한과 같은 스크립트를
 * 손님 키와 겹치지 않는 키로 한 번 실행한다 — 연결이 열리고 스크립트가 Redis 에 올라간 뒤에야 프로브가 통과한다.
 * Redis 에 닿지 못하면 경고만 남기고 뜬다(예전과 같은 fail-open — 게이트웨이가 Redis 때문에 뜨지 못하는 것이 더 나쁘다).
 *
 * <p>[JA] 流入制限の Redis 経路を起動時に一度温める。タイムアウト 500ms を超えると制限は通過(fail-open)になり、起動直後の最初の
 * コマンド(接続 · AUTH · スクリプト読み込み)が上限を超えて、2026-10-24 の本番スモークで 429 が一つも出ず自動復帰した。
 * ApplicationRunner は readiness より前に動く。Redis に届かなければ警告だけで起動する。
 */
@Configuration(proxyBeanMethods = false)
public class RateLimiterWarmup {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterWarmup.class);

    /** 손님 버킷(`request_rate_limiter.{user:…}` · `{ip:…}`)과 겹치지 않는 키. 수명은 스크립트가 정한다(몇 초). */
    static final List<String> KEYS = List.of(
            "request_rate_limiter.{gateway-warmup}.tokens",
            "request_rate_limiter.{gateway-warmup}.timestamp");

    @Bean
    ApplicationRunner rateLimiterRedisWarmup(ReactiveStringRedisTemplate redis,
            @Qualifier(RateLimiterScriptConfig.SCRIPT_BEAN) RedisScript<List<Long>> script,
            @Value("${gateway.rate-limiter.warmup-timeout:15s}") Duration timeout) {
        return args -> warm(redis, script, timeout);
    }

    static boolean warm(ReactiveStringRedisTemplate redis, RedisScript<List<Long>> script, Duration timeout) {
        long started = System.nanoTime();
        try {
            // 인자는 RedisRateLimiter 와 같은 모양(보충 · 용량 · 지금(빈 값 → Redis TIME) · 요청 토큰)
            redis.execute(script, KEYS, List.of("1", "1", "", "1")).blockLast(timeout);
            log.info("유입 제한 Redis 데우기 끝 — {}ms", (System.nanoTime() - started) / 1_000_000);
            return true;
        } catch (RuntimeException e) {
            log.warn("유입 제한 Redis 데우기 실패 — 그대로 뜬다(Redis 에 닿을 때까지 유입 제한은 통과): {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
