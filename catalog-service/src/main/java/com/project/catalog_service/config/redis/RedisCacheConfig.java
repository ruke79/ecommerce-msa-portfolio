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
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
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

@Configuration
@EnableCaching
@RequiredArgsConstructor
public class RedisCacheConfig {
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
                .builder();

        if (useSsl) {
            // ⚠️ 인증서 경로가 비어 있으면 SslOptions 를 아예 만들지 않는다 — JVM 기본 신뢰 저장소
            //    (공개 CA)로 검증한다. 관리형 Redis(Upstash 등)가 바로 이 경우다: 공개 CA 인증서에
            //    비밀번호 인증이고 클라이언트 인증서는 존재하지 않는다. 예전 코드는 빈 경로도
            //    무조건 열어서, TLS 를 켜는 순간 FileNotFoundException 으로 기동이 죽었다(docs/32 §4).
            ClientOptions.Builder clientOptions = ClientOptions.builder()
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

        // `products` 캐시 설정: 30분 보관 (원하는 보관 기간으로 변경 가능)
        cacheConfigurations.put("products", permanentCacheConfig);
        
        // `search_cache` 캐시 설정: 30분 보관
        cacheConfigurations.put("search_cache", redisCacheConfiguration(Duration.ofMinutes(60)));

        

        // `product_cache` 캐시 설정: 영구 보관
        cacheConfigurations.put("product_cache", permanentCacheConfig);
        cacheConfigurations.put("products_cache", permanentCacheConfig);

        return RedisCacheManager.builder(redisConnectionFactory)
            .initialCacheNames(cacheConfigurations.keySet())
            .withInitialCacheConfigurations(cacheConfigurations)
            .cacheDefaults(redisCacheConfiguration(Duration.ofMinutes(10)).disableCachingNullValues())
            .build();
    }

    private RedisCacheConfiguration redisCacheConfiguration(Duration duration) {
        return RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(duration)
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
