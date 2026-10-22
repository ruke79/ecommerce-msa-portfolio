package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.scripting.support.StaticScriptSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.gatewayserver.resolver.RateLimiterScriptConfig;

/**
 * 유입 제한 스크립트는 기동 때 한 번 읽는다 — 요청마다 jar 안의 파일 수정 시각을 확인하지 않는다(RateLimiterScriptConfig).
 *
 * <p>🐛 2026-10-18 부하 측정(docs/83 §5): async-profiler 로 게이트웨이 CPU 의 18.7% 가 {@code DefaultRedisScript.getSha1()} →
 * {@code ResourceScriptSource.isModified()} → 클래스로더의 중첩 jar 검색 · {@code FileNotFoundException} 이었다(전역 잠금 안).
 *
 * <p>[JA] 流入制限のスクリプトは起動時に一度読む — 要求ごとに jar 内のファイルの更新時刻を確かめない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("게이트웨이 — 유입 제한 스크립트는 기동 때 한 번 읽는다")
class RateLimiterScriptTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        NettyServerTest.properties(registry);
    }

    @Autowired
    @Qualifier("redisRequestRateLimiterScript")
    RedisScript<List<Long>> script;

    @Autowired
    RedisRateLimiter rateLimiter;

    private static String sha1(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String lua() throws Exception {
        return new ClassPathResource("META-INF/scripts/request_rate_limiter.lua").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("🔴 실제 컨텍스트 — 스크립트 원본이 글자(StaticScriptSource)이고 내용 · SHA1 은 SCG 의 lua 그대로, RedisRateLimiter 가 그 빈을 쓴다")
    void contextScriptIsStaticAndUnchanged() throws Exception {
        assertThat(ReflectionTestUtils.getField(script, "scriptSource"))
                .as("요청마다 jar 를 뒤지는 ResourceScriptSource 가 아니다").isInstanceOf(StaticScriptSource.class);
        assertThat(script.getScriptAsString()).isEqualTo(lua());
        assertThat(script.getSha1()).isEqualTo(sha1(lua()));
        assertThat(ReflectionTestUtils.getField(rateLimiter, "script")).as("유입 제한이 쓰는 스크립트").isSameAs(script);
    }

    @Test
    @DisplayName("🔴 고친 뒤 getSha1() 1,000번 — 원본 파일의 수정 시각을 한 번도 보지 않는다(고치기 전에는 부를 때마다) · 다른 이름의 빈은 그대로")
    void sha1NoLongerChecksTheFileEveryCall() throws Exception {
        AtomicInteger lastModifiedChecks = new AtomicInteger();
        ByteArrayResource file = new ByteArrayResource(lua().getBytes(StandardCharsets.UTF_8)) {
            @Override
            public long lastModified() {
                lastModifiedChecks.incrementAndGet();
                return 0L;
            }
        };
        DefaultRedisScript<List> before = new DefaultRedisScript<>();
        before.setScriptSource(new ResourceScriptSource(file));
        before.setResultType(List.class);
        before.getSha1();
        before.getSha1();
        assertThat(lastModifiedChecks.get()).as("SCG 그대로면 부를 때마다 파일을 본다").isGreaterThanOrEqualTo(2);

        BeanPostProcessor fix = ReflectionTestUtils.invokeMethod(RateLimiterScriptConfig.class, "staticRateLimiterScript");
        DefaultRedisScript<List> fixed = new DefaultRedisScript<>();
        fixed.setScriptSource(new ResourceScriptSource(file));
        fixed.setResultType(List.class);
        fix.postProcessAfterInitialization(fixed, "redisRequestRateLimiterScript");
        int afterFix = lastModifiedChecks.get();
        for (int i = 0; i < 1000; i++) {
            assertThat(fixed.getSha1()).isEqualTo(sha1(lua()));
        }
        assertThat(lastModifiedChecks.get()).isEqualTo(afterFix);

        DefaultRedisScript<List> other = new DefaultRedisScript<>();
        other.setScriptSource(new ResourceScriptSource(file));
        fix.postProcessAfterInitialization(other, "someOtherScript");
        assertThat(ReflectionTestUtils.getField(other, "scriptSource")).isInstanceOf(ResourceScriptSource.class);
    }
}
