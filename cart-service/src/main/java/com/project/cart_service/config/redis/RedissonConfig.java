package com.project.cart_service.config.redis;

import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.File;
import java.math.BigDecimal;
import java.net.URL;

import org.redisson.Redisson;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.function.Predicate;

@Configuration
@EnableCaching // 캐시 기능을 활성화합니다.
public class RedissonConfig {

    // Redis 서버의 비밀번호를 주입받습니다.
    @Value("${spring.data.redis.password}")
    private String password;

    @Value("${spring.data.redis.ssl.enabled}")
    private boolean useSsl;

    @Value("${spring.data.redis.ssl.truststore}")
    private String truststoreFileLocation;

    @Value("${spring.data.redis.ssl.keystore}")
    private String keystoreFileLocation;

    @Value("${spring.data.redis.ssl.storePassword}")
    private String storePassword;

    @Value("${spring.data.redis.host}")
    private String host;
    @Value("${spring.data.redis.port}")
    private int port;

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                // 스킴은 SSL 사용 여부를 따라가야 한다. rediss:// 로 고정돼 있으면
                // ssl.enabled=false 인 평문 Redis 에 TLS 핸드셰이크를 시도해
                // SslHandshakeTimeoutException 으로 기동이 실패한다(docs/14 B-14).
                .setAddress((useSsl ? "rediss://" : "redis://") + host + ":" + port)
                .setPassword(password);

        if (useSsl) {
            try {
                // ⚠️ 경로가 비어 있으면 **아무것도 설정하지 않는다** — JVM 기본 트러스트스토어를 쓴다.
                //    관리형 Redis(Upstash 등)는 공개 CA 로 발급된 인증서에 TLS + 비밀번호 인증이고,
                //    클라이언트 인증서는 아예 없다. 예전 코드는 빈 문자열을 그대로 File 로 감싸
                //    `new File("")` → 현재 작업 디렉터리 URL 을 트러스트스토어로 넘겼고,
                //    ssl.enabled 를 켜는 순간 keystore 로딩 실패로 기동이 죽었다(docs/32 §4).
                if (isSet(truststoreFileLocation)) {
                    URL truststoreUrl = new File(truststoreFileLocation).toURI().toURL();
                    config.useSingleServer().setSslTruststore(truststoreUrl);
                    if (isSet(storePassword)) {
                        config.useSingleServer().setSslTruststorePassword(storePassword);
                    }
                }
                if (isSet(keystoreFileLocation)) {
                    URL keystoreUrl = new File(keystoreFileLocation).toURI().toURL();
                    config.useSingleServer().setSslKeystore(keystoreUrl);
                    if (isSet(storePassword)) {
                        config.useSingleServer().setSslKeystorePassword(storePassword);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load SSL certificates for Redis.", e);
            }
        }
        return Redisson.create(config);
    }


    // ObjectMapper를 주입받아 RedisTemplate을 설정합니다.
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedissonClient redissonClient, 
     @Qualifier("cacheObjectMapper") ObjectMapper cacheObjectMapper) {       
        
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();

        // RedissonConnectionFactory를 사용하도록 설정합니다.
        redisTemplate.setConnectionFactory(new RedissonConnectionFactory(redissonClient));

        // ObjectMapper를 사용하는 Jackson2JsonRedisSerializer를 생성합니다.
        Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(cacheObjectMapper, Object.class);
        

        // Key와 Hash Key는 String으로 직렬화
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());

        // Value와 Hash Value는 설정된 ObjectMapper를 사용하는 JSON 직렬화기로 설정합니다.
        redisTemplate.setValueSerializer(jsonSerializer);
        redisTemplate.setHashValueSerializer(jsonSerializer);

        return redisTemplate;
    }

    @Bean
    public CacheManager cacheManager(RedissonConnectionFactory connectionFactory, 
     @Qualifier("cacheObjectMapper") ObjectMapper cacheObjectMapper) {
     
        Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(cacheObjectMapper,Object.class);
        

        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(60))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfig)
                .build();
    }

}
