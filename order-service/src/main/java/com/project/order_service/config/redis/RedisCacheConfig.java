package com.project.order_service.config.redis;

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
        redisTemplate.setValueSerializer(cacheValueSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        // redisTemplate.setHashValueSerializer(new
        // Jackson2JsonRedisSerializer<>(Object.class));
        redisTemplate.setHashValueSerializer(cacheValueSerializer());

        return redisTemplate;
    }

   
    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory) {
        // 만료 기간이 없는 영구 캐시 설정
        RedisCacheConfiguration permanentCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .serializeValuesWith(SerializationPair.fromSerializer(cacheValueSerializer()));
        
        // 캐시 이름별 설정 맵 생성
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        
        cacheConfigurations.put("orderList", redisCacheConfiguration(Duration.ofMinutes(60)));
          // `ordersPage` 캐시 설정: 60분 보관
        cacheConfigurations.put("ordersPage", redisCacheConfiguration(Duration.ofMinutes(60)));

        cacheConfigurations.put("orders", redisCacheConfiguration(Duration.ofMinutes(60)));

        cacheConfigurations.put("ordersCursor", redisCacheConfiguration(Duration.ofMinutes(60)));


        

        // 🐛 결제됐는데 상세가 한동안 NOT_PROCESSED 였다. 사가는 상태를 바꾼 **트랜잭션 안에서**
        //    캐시를 비웠고, 커밋은 그 뒤(재고·장바구니 발행과 Kafka 트랜잭션 커밋까지 끝난 뒤)였다.
        //    그 틈에 확정을 기다리는 화면의 조회가 들어오면 아직 커밋 전인 옛 상태를 DB 에서 읽어
        //    **다시 캐시에 넣는다** — 비운 직후라 그대로 30분 남는다. 배포 직후 첫 주문은 트랜잭션
        //    프로듀서 초기화로 그 틈이 몇 초라 거의 매번 걸렸다.
        //    transactionAware: 트랜잭션 안의 비우기·넣기를 **커밋 뒤로** 미룬다. 트랜잭션 밖에서는
        //    예전처럼 바로 한다(docs/25 §11.12).
        return RedisCacheManager.builder(redisConnectionFactory)
            .initialCacheNames(cacheConfigurations.keySet())
            .withInitialCacheConfigurations(cacheConfigurations)
            .cacheDefaults(redisCacheConfiguration(Duration.ofMinutes(30)).disableCachingNullValues())
            .transactionAware()
            .build();
    }

    /**
     * 캐시 값 직렬화기 — 기본 {@link GenericJackson2JsonRedisSerializer}(타입 정보 {@code @class} 그대로)에 <b>날짜·시각</b>을 더한다.
     *
     * <p>🐛 기본 직렬화기는 {@code LocalDateTime} 을 쓰지 못한다("Java 8 date/time type not supported by default"). 주문 DTO 에는
     * 결제·배송·구매확정 시각, 입금 기한, 재고 예약 만료가 있는데, 값이 <b>있는</b> 주문을 캐시에 넣는 순간 예외가 나 주문 조회가
     * 400("요청을 처리하지 못했습니다")이 됐다. 재고 예약이 제대로 잡히기 시작하자(docs/59 §10) 새 주문마다 {@code stockHoldUntil}
     * 이 채워져 모든 새 주문의 상세가 열리지 않았다.
     *
     * <p>모르는 필드는 무시한다 — DTO 에서 필드를 지운 배포 직후, 캐시에 남은 옛 값이 역직렬화 실패로 같은 400 을 내지 않게.
     */
    static GenericJackson2JsonRedisSerializer cacheValueSerializer() {
        return new GenericJackson2JsonRedisSerializer().configure(mapper -> mapper
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    private RedisCacheConfiguration redisCacheConfiguration(Duration duration) {
        return RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(duration)
                .serializeValuesWith(SerializationPair.fromSerializer(cacheValueSerializer()));
    }
}
