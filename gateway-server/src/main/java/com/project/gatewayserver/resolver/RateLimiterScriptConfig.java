package com.project.gatewayserver.resolver;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 유입 제한(RequestRateLimiter)의 Redis 스크립트를 기동 때 한 번 읽어 글자로 고정한다 — 요청마다 jar 안의 파일을 다시 확인하지 않게.
 *
 * <h3>왜</h3>
 *
 * <p>2026-10-18 부하 측정(docs/83 §5)에서 게이트웨이 CPU 가 1 코어에 붙어 벌크헤드 503 이 났다. async-profiler 로 떠 보니 게이트웨이 CPU 의
 * <b>18.7%</b> 가 이 한 줄이었다: Spring Cloud Gateway 는 스크립트 빈({@code redisRequestRateLimiterScript})을
 * {@code ResourceScriptSource(ClassPathResource("META-INF/scripts/request_rate_limiter.lua"))} 로 만들고, Spring Data Redis 의
 * {@code DefaultRedisScript.getSha1()} 은 <b>요청마다</b> {@code scriptSource.isModified()} 를 부른다 → 파일 수정 시각을 보려고
 * 클래스로더로 Boot 중첩 jar 를 뒤지고({@code NestedJarFile.hasEntry} · {@code URL.<init>}) {@code ResourceUtils.getFile} 이
 * {@code FileNotFoundException} 을 만든다(jar 안이라 파일이 아니다). 게다가 그 확인은 <b>전역 잠금 안</b>에서 돈다 — 이벤트 루프 스레드가 줄을 선다.
 * 비로그인 요청은 모두 유입 제한을 지나니 요청 수만큼이다.
 *
 * <h3>어떻게</h3>
 *
 * <p>그 빈을 바꾸지 않고(SCG 의 빈은 {@code @ConditionalOnMissingBean} 이 없어 같은 이름으로 덮으면 기동이 실패한다) 만든 직후에
 * 스크립트 원본만 같은 글자의 {@code StaticScriptSource} 로 바꾼다 — 스크립트 내용 · SHA1 · 결과 타입 · 쓰는 곳({@code RedisRateLimiter})은 그대로다.
 * jar 안의 파일은 배포 사이에 바뀌지 않으니 "바뀌었나" 를 볼 이유가 없다. 파일을 못 읽으면 기동이 실패한다(조용히 유입 제한이 빠지지 않게).
 *
 * <p>[JA] 流入制限(RequestRateLimiter)の Redis スクリプトを起動時に一度読んで文字列に固定する — 要求ごとに jar 内のファイルを確かめ直さないように。
 * <h3>[JA] なぜ</h3>
 * <p>[JA] 2026-10-18 負荷計測(docs/83 §5)でゲートウェイの CPU が 1 コアに張り付き、バルクヘッド 503 が出た。async-profiler で取ると
 * ゲートウェイ CPU の <b>18.7%</b> がこの一行だった: Spring Cloud Gateway はスクリプト Bean({@code redisRequestRateLimiterScript})を
 * {@code ResourceScriptSource(ClassPathResource("META-INF/scripts/request_rate_limiter.lua"))} で作り、Spring Data Redis の
 * {@code DefaultRedisScript.getSha1()} は<b>要求ごとに</b> {@code scriptSource.isModified()} を呼ぶ → ファイルの更新時刻を見るために
 * クラスローダーで Boot の入れ子 jar を探し({@code NestedJarFile.hasEntry} · {@code URL.<init>})、{@code ResourceUtils.getFile} が
 * {@code FileNotFoundException} を作る(jar 内なのでファイルではない)。しかもその確認は<b>グローバルロックの中</b>で走る — イベントループの
 * スレッドが列を作る。未ログインの要求はすべて流入制限を通るので、要求数だけ起きる。
 * <h3>[JA] どうやって</h3>
 * <p>[JA] その Bean は置き換えず(SCG の Bean は {@code @ConditionalOnMissingBean} がなく、同じ名前で上書きすると起動が失敗する)、作った直後に
 * スクリプトのソースだけを同じ文字列の {@code StaticScriptSource} に替える — スクリプト内容 · SHA1 · 結果型 · 使う所({@code RedisRateLimiter})は
 * そのまま。jar 内のファイルはデプロイの間に変わらないので「変わったか」を見る理由がない。ファイルを読めなければ起動が失敗する
 * (黙って流入制限が抜けないように)。
 */
@Configuration(proxyBeanMethods = false)
public class RateLimiterScriptConfig {

    /** Spring Cloud Gateway(GatewayRedisAutoConfiguration)의 스크립트 빈 이름. */
    // [JA] Spring Cloud Gateway(GatewayRedisAutoConfiguration)のスクリプト Bean 名。
    static final String SCRIPT_BEAN = "redisRequestRateLimiterScript";

    @Bean
    static BeanPostProcessor staticRateLimiterScript() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (SCRIPT_BEAN.equals(beanName) && bean instanceof DefaultRedisScript<?> script) {
                    // 지금 원본(jar 의 lua)을 한 번 읽어 같은 글자로 — 이후 isModified() 는 false, getSha1() 은 계산해 둔 값
                    // [JA] 今の原本(jar の lua)を一度読んで同じ文字列で — 以後 isModified() は false、getSha1() は計算済みの値
                    script.setScriptText(script.getScriptAsString());
                }
                return bean;
            }
        };
    }
}
