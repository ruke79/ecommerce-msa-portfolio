package com.project.catalog_service.config.redis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.project.common.dto.ProductDto;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Configuration
@EnableCaching
@RequiredArgsConstructor
public class RedisCacheConfig implements org.springframework.cache.annotation.CachingConfigurer {
    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password}")
    private String password;

    @Value("${spring.data.redis.ssl.enabled}")
    private boolean useSsl;

    @Value("${spring.data.redis.ssl.cacert}")
    private String cacertFileLocation;

    @Value("${spring.data.redis.ssl.keystore}")
    private String keystoreFileLocation;
    
    // @Value("${spring.data.redis.ssl.truststore}")
    // private String truststoreFileLocation;

    @Value("${spring.data.redis.ssl.storePassword}")
    private String storePassword;

    private final ResourceLoader resourceLoader;

    /**
     * Redis 명령 시간 제한. 🐛 직접 만든 Lettuce 설정은 {@code spring.data.redis.timeout} 을 읽지 않아 기본값 60초였다 — Redis 가
     * 멈추면 상품 조회 요청마다 스레드가 60초씩 묶여 톰캣 스레드가 바닥났다. 캐시는 늦느니 없는 편이 낫다.
     * [JA] Redis コマンドのタイムアウト。自前の Lettuce 設定は既定 60 秒で、Redis が止まると要求ごとにスレッドが 60 秒拘束された。
     */
    @Value("${catalog.redis.command-timeout:PT1S}")
    private Duration commandTimeout = Duration.ofSeconds(1);

    @Value("${catalog.redis.connect-timeout:PT1S}")
    private Duration connectTimeout = Duration.ofSeconds(1);

    /** 상품 목록 · 상세 캐시(products)의 수명 — 쓰기마다 비우지만, 놓친 무효화(다른 파드의 할인 맵 등)도 이 시간 안에는 풀린다. */
    static final Duration PRODUCTS_TTL = Duration.ofMinutes(30);

    /**
     * 필터 메뉴 캐시(brands · colors · sizes · details)의 수명 — 값에 계산 시각이 있어 10분({@code catalog.facets.refresh-after})이 지나면
     * 옛 값을 내주며 뒤에서 다시 계산한다({@code SearchFilterFacets}). 수명은 그보다 훨씬 길어야 그 '옛 값' 이 남아 있다.
     *
     * <p>🐛 2026-10-20 ~ 21 실습 8시간(1,000만 · 넓힘, docs/83 §2.8): 이 넷이 설정 맵에 없어 기본 10분(흩어져 8 ~ 10분)이었다. 다시 계산할
     * 때(10분)가 오기 전에 Redis 에서 사라져, 카테고리마다 8 ~ 10분에 한 번 첫 쪽 검색이 메뉴 계산(1,000만에서 수십 초)을 1.5초 기다린 뒤
     * 빈 메뉴로 나갔다 — 밤새 검색 p95 1,513ms. [JA] フィルターメニューのキャッシュの寿命 — 設定マップになく既定 10 分で、再計算の時刻より
     * 先に Redis から消え、カテゴリごとに 8〜10 分に一度、1 ページ目がメニューを 1.5 秒待って空で返っていた。
     */
    static final Duration FACETS_TTL = Duration.ofHours(24);

    /** 필터 메뉴 캐시 이름 — SearchFilterFacets 가 쓴다. */
    static final java.util.List<String> FACET_CACHES = java.util.List.of("brands_cache", "colors_cache", "sizes_cache", "details_cache");

    /** 만료의 흩어짐 — 키마다 수명을 이만큼까지(비율) 줄인다. */
    static final double TTL_JITTER = 0.2;

    /**
     * 수명을 키마다 조금씩 다르게 — {@code base} 에서 최대 20% 를 무작위로 뺀다.
     *
     * <p>🐛 2026-10-19 데이터 규모 측정(docs/83 §5 · mixed {@code 160904Z}): 한꺼번에 채워진 키는 한꺼번에 만료된다. 캐시를 통째로
     * 비운 뒤(flush-cache · 기획전 경계의 {@code EventScheduler} · 예전의 별점 갱신) 다시 채워진 {@code search_cache} 키 48개가 정확히
     * 60분 뒤 같은 초에 만료돼 20초 동안 DB 로 몰렸고, 그때 다시 채워진 48개가 또 모두 17:15 에 만료될 참이었다 — 한 번 생긴 무리는
     * 매시간 되풀이된다. 흩어 두면 한 바퀴 만에 무리가 풀린다.
     * [JA] 一度に埋まったキーは一度に切れ、その群れは毎時繰り返す。キーごとに寿命を最大 20% 縮めて散らす。
     */
    static RedisCacheWriter.TtlFunction jittered(Duration base) {
        long millis = base.toMillis();
        long spread = (long) (millis * TTL_JITTER);
        return (key, value) -> Duration.ofMillis(millis - ThreadLocalRandom.current().nextLong(spread + 1));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() throws IOException {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(host);
        redisConfig.setPort(port);
        redisConfig.setPassword(RedisPassword.of(password));

        LettuceClientConfiguration.LettuceClientConfigurationBuilder lettuceClientConfigurationBuilder = LettuceClientConfiguration
                .builder()
                .commandTimeout(commandTimeout);
        io.lettuce.core.SocketOptions socket = io.lettuce.core.SocketOptions.builder().connectTimeout(connectTimeout).build();

        if (!useSsl) {
            lettuceClientConfigurationBuilder.clientOptions(ClientOptions.builder().socketOptions(socket).build());
        }
        if (useSsl) {
            // ⚠️ 인증서 경로가 비어 있으면 SslOptions 를 아예 만들지 않는다 — JVM 기본 신뢰 저장소
            //    (공개 CA)로 검증한다. 관리형 Redis(Upstash 등)가 바로 이 경우다: 공개 CA 인증서에
            //    비밀번호 인증이고 클라이언트 인증서는 존재하지 않는다. 예전 코드는 빈 경로도
            //    무조건 열어서, TLS 를 켜는 순간 FileNotFoundException 으로 기동이 죽었다(docs/32 §4).
            ClientOptions.Builder clientOptions = ClientOptions.builder()
                    .socketOptions(socket)
                    .protocolVersion(ProtocolVersion.RESP3);

            if (isSet(cacertFileLocation) || isSet(keystoreFileLocation)) {
                SslOptions.Builder ssl = SslOptions.builder();
                if (isSet(cacertFileLocation)) {
                    ssl.trustManager(resourceLoader.getResource("file:" + cacertFileLocation).getFile());
                }
                if (isSet(keystoreFileLocation)) {
                    ssl.keystore(resourceLoader.getResource("file:" + keystoreFileLocation).getFile(),
                            storePassword == null ? null : storePassword.toCharArray());
                }
                clientOptions.sslOptions(ssl.build());
            }

            lettuceClientConfigurationBuilder
                    .clientOptions(clientOptions.build())
                    .useSsl();//.disablePeerVerification();
        }

        LettuceClientConfiguration lettuceClientConfiguration = lettuceClientConfigurationBuilder.build();

        return new LettuceConnectionFactory(redisConfig, lettuceClientConfiguration);

    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate() throws IOException {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();

        redisTemplate.setConnectionFactory(redisConnectionFactory());

        redisTemplate.setKeySerializer(new StringRedisSerializer());
        // redisTemplate.setValueSerializer(new
        // Jackson2JsonRedisSerializer<>(Object.class));
        redisTemplate.setValueSerializer(permanentValueSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        // redisTemplate.setHashValueSerializer(new
        // Jackson2JsonRedisSerializer<>(Object.class));
        redisTemplate.setHashValueSerializer(permanentValueSerializer());

        return redisTemplate;
    }

   
    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory) {
        // 만료 기간이 없는 영구 캐시 설정
        RedisCacheConfiguration permanentCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .serializeValuesWith(SerializationPair.fromSerializer(permanentValueSerializer()));
        
        // 캐시 이름별 설정 맵 생성
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // `products` 캐시 — 상품 목록 · 상세. 🐛 예전에는 만료 없이 영구였다 — 무효화를 하나 놓치면(상품 등록 · 색상 추가 · 별점 ·
        // 다른 파드의 할인 맵) 다음 쓰기까지 영원히 옛 값이었고, 공개 조회의 임의 slug 마다 키가 영구로 쌓였다. 이제 30분.
        // [JA] 以前は期限なしの永久キャッシュだった。無効化を一つ逃すと次の書き込みまで古いままで、任意の slug ごとにキーが永久に溜まった。
        cacheConfigurations.put("products", RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(jittered(PRODUCTS_TTL))
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(permanentValueSerializer())));
        
        // `search_cache` 캐시 설정: 30분 보관
        cacheConfigurations.put("search_cache", redisCacheConfiguration(Duration.ofMinutes(60)));

        

        // 필터 메뉴 넷 — 24시간(다시 계산은 값의 계산 시각으로 10분마다 뒤에서, FACETS_TTL)
        for (String facets : FACET_CACHES) {
            cacheConfigurations.put(facets, redisCacheConfiguration(FACETS_TTL));
        }

        // `product_cache` 캐시 설정: 영구 보관
        cacheConfigurations.put("product_cache", permanentCacheConfig);
        cacheConfigurations.put("products_cache", permanentCacheConfig);

        // allEntries 비우기는 SCAN 으로 — 🐛 기본은 KEYS 였다. 결제 차감 · 복원 한 건마다 캐시 다섯 개에 KEYS 가 돌았고, KEYS 는 공유
        // Redis(로그인 잠금 · 블랙리스트 · 유입 제한과 같이 쓴다)의 키 전체를 훑는 동안 Redis 를 막는다.
        // [JA] allEntries の消去は SCAN で。既定は KEYS で、共有 Redis 全体を走査する間ブロックしていた。
        // 잠그지 않는 작성기 + 키마다 한 번 계산(SingleFlightCache) — 잠그는 작성기는 캐시 이름 전체를 Redis 잠금 하나로 묶는다.
        // 감싸기는 getCache 에서 한다: 안쪽 맵에는 RedisCache 를 그대로 두어야 getCacheConfigurations 가 깨지지 않는다.
        // [JA] ロックしないライター + キーごとに一度だけ計算。包むのは getCache で — 内側のマップは RedisCache のまま。
        return new RedisCacheManager(
                RedisCacheWriter.nonLockingRedisCacheWriter(redisConnectionFactory, BatchStrategies.scan(500)),
                redisCacheConfiguration(Duration.ofMinutes(10)).disableCachingNullValues(),
                true,
                cacheConfigurations) {
            private final Map<String, Cache> singleFlight = new ConcurrentHashMap<>();

            @Override
            public Cache getCache(String name) {
                Cache cache = super.getCache(name);
                return cache == null ? null : singleFlight.computeIfAbsent(name, n -> new SingleFlightCache(cache));
            }
        };
    }

    /**
     * 캐시 오류는 조회를 막지 않는다 — 읽기 실패는 캐시 미스로, 쓰기 · 비우기 실패는 경고로(값은 TTL 안에 풀린다).
     * 🐛 예전에는 처리기가 없어 Redis 오류가 그대로 500 이었다 — Redis 하나가 멈추면 검색 · 상세 · 홈이 모두 멈췄다.
     * [JA] キャッシュの失敗は照会を止めない。以前は Redis の失敗がそのまま 500 で、検索 · 詳細 · ホームがすべて止まった。
     */
    @Override
    public org.springframework.cache.interceptor.CacheErrorHandler errorHandler() {
        return new LenientCacheErrorHandler();
    }

    static final class LenientCacheErrorHandler implements org.springframework.cache.interceptor.CacheErrorHandler {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LenientCacheErrorHandler.class);

        @Override
        public void handleCacheGetError(RuntimeException e, org.springframework.cache.Cache cache, Object key) {
            log.warn("캐시 읽기 실패 — 원본에서 읽는다. cache={} ({})", cache.getName(), e.getClass().getSimpleName());
        }

        @Override
        public void handleCachePutError(RuntimeException e, org.springframework.cache.Cache cache, Object key, Object value) {
            log.warn("캐시 쓰기 실패 — 응답은 그대로. cache={} ({})", cache.getName(), e.getClass().getSimpleName());
        }

        @Override
        public void handleCacheEvictError(RuntimeException e, org.springframework.cache.Cache cache, Object key) {
            log.warn("캐시 비우기 실패 — TTL 안에 풀린다. cache={} ({})", cache.getName(), e.getClass().getSimpleName());
        }

        @Override
        public void handleCacheClearError(RuntimeException e, org.springframework.cache.Cache cache) {
            log.warn("캐시 전체 비우기 실패 — TTL 안에 풀린다. cache={} ({})", cache.getName(), e.getClass().getSimpleName());
        }
    }

    private RedisCacheConfiguration redisCacheConfiguration(Duration duration) {
        return RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(jittered(duration))
                .serializeValuesWith(SerializationPair.fromSerializer(cacheValueSerializer()));
    }

    /**
     * {@code products} · {@code product_cache} · {@code products_cache} 와 RedisTemplate 의 값 직렬화기 — 기본
     * {@link GenericJackson2JsonRedisSerializer} 그대로(저장 모양이 같다)에 <b>모르는 필드 무시</b>만 더한다.
     *
     * <p>기본 직렬화기는 모르는 필드를 만나면 실패한다. 캐시는 배포를 넘어 남으므로, 상품 DTO 에 필드를 더한 배포의 롤링 중 옛 파드가
     * 새 파드가 쓴 값을 읽다(또는 필드를 지운 뒤 새 파드가 옛 값을 읽다) 상품 조회가 깨진다. 예전에는 DTO 두 개({@code ProductSizeDto} ·
     * {@code ProductInfoDto})에만 {@code @JsonIgnoreProperties} 를 붙여 막았다 — 직렬화기에서 한 번에 막는다(order-service
     * {@code RedisCacheConfig} 와 같다).
     *
     * <p>[JA] 既定の {@link GenericJackson2JsonRedisSerializer} そのまま(保存形式は同じ)に<b>未知のフィールドの無視</b>だけを足す。
     * キャッシュはデプロイをまたいで残るので、商品 DTO にフィールドを足したデプロイのローリング中、旧 Pod が新 Pod の書いた値を読んで
     * 商品照会が壊れる。以前は DTO 二つにだけ {@code @JsonIgnoreProperties} を付けていた — シリアライザで一度に防ぐ。
     */
    static GenericJackson2JsonRedisSerializer permanentValueSerializer() {
        return new GenericJackson2JsonRedisSerializer()
                .configure(mapper -> mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    /**
     * 캐시 값 직렬화기. 기본 {@link GenericJackson2JsonRedisSerializer} 와 동일하게 타입 정보를
     * 남기되, {@link PageJacksonModule} 을 추가해 {@code Page} 를 되읽을 수 있게 한다.
     * 이것이 없으면 검색 캐시가 히트하는 순간 역직렬화에 실패해 500 이 된다(docs/16 B-27).
     */
    static GenericJackson2JsonRedisSerializer cacheValueSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new PageJacksonModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 모르는 필드는 무시한다 — permanentValueSerializer 와 같은 이유. ／ [JA] 未知のフィールドは無視する(同じ理由)。
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // GenericJackson2JsonRedisSerializer 기본 동작과 동일하게 @class 로 타입을 남긴다.
        mapper.activateDefaultTypingAsProperty(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                "@class");
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
