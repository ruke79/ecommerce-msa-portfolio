package com.project.chat_service.common.config.redis;

import org.redisson.api.RedissonClient;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;

import org.redisson.Redisson;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableRedisRepositories(basePackages = "com.project.chat_service.repository.redis")
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
        SingleServerConfig serverConfig = config.useSingleServer();

        // 스킴은 SSL 사용 여부를 따라가야 한다. 예전에는 rediss:// 로 고정돼 있어
        // ssl.enabled=false 인 평문 Redis 에도 TLS 핸드셰이크를 시도했고,
        // "SSL is not enabled for Redisson connection." 를 찍은 직후
        // SslHandshakeTimeoutException 으로 기동이 실패했다(docs/14 B-14).
        serverConfig.setAddress((useSsl ? "rediss://" : "redis://") + host + ":" + port);
        serverConfig.setPassword(password);

        if (useSsl) {
            try {
                // ⚠️ 경로가 비어 있으면 **아무것도 설정하지 않는다** — JVM 기본 트러스트스토어를 쓴다.
                //    관리형 Redis(Upstash 등)는 공개 CA 인증서 + 비밀번호 인증이고 클라이언트
                //    인증서가 없다. 예전 코드는 빈 문자열을 그대로 `new File("")` 로 감싸
                //    현재 작업 디렉터리를 스토어로 넘겼고, SSL 을 켜는 순간 죽었다(docs/32 §4).
                if (isSet(truststoreFileLocation)) {
                    serverConfig.setSslTruststore(new File(truststoreFileLocation).toURI().toURL());
                    if (isSet(storePassword)) {
                        serverConfig.setSslTruststorePassword(storePassword);
                    }
                }
                if (isSet(keystoreFileLocation)) {
                    serverConfig.setSslKeystore(new File(keystoreFileLocation).toURI().toURL());
                    if (isSet(storePassword)) {
                        serverConfig.setSslKeystorePassword(storePassword);
                    }
                }

                log.info("Redisson SSL configuration applied (truststore={}, keystore={}).",
                        isSet(truststoreFileLocation) ? "custom" : "JVM default",
                        isSet(keystoreFileLocation) ? "custom" : "none");
            } catch (IOException e) {
                // 예외 발생 시 더 구체적인 정보를 로그로 남김
                log.error("Failed to load SSL certificates for Redis. Check file paths and permissions.", e);
                throw new IllegalStateException("Failed to load SSL certificates for Redis.", e);
            }
        } else {
            log.info("SSL is not enabled for Redisson connection.");
        }
        return Redisson.create(config);
    }
     

    // ObjectMapper를 주입받아 RedisTemplate을 설정합니다.
    // @Bean
    // public RedisTemplate<String, Object> redisTemplate(RedissonClient redissonClient, 
    //  @Qualifier("cacheObjectMapper") ObjectMapper cacheObjectMapper) {       
        
    //     RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();

    //     // RedissonConnectionFactory를 사용하도록 설정합니다.
    //     redisTemplate.setConnectionFactory(new RedissonConnectionFactory(redissonClient));

    //     // ObjectMapper를 사용하는 Jackson2JsonRedisSerializer를 생성합니다.
    //     Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(cacheObjectMapper, Object.class);
        

    //     // Key와 Hash Key는 String으로 직렬화
    //     redisTemplate.setKeySerializer(new StringRedisSerializer());
    //     redisTemplate.setHashKeySerializer(new StringRedisSerializer());

    //     // Value와 Hash Value는 설정된 ObjectMapper를 사용하는 JSON 직렬화기로 설정합니다.
    //     redisTemplate.setValueSerializer(jsonSerializer);
    //     redisTemplate.setHashValueSerializer(jsonSerializer);

    //     return redisTemplate;
    // }

    // @Bean
    // public CacheManager cacheManager(RedissonConnectionFactory connectionFactory, 
    //  @Qualifier("cacheObjectMapper") ObjectMapper cacheObjectMapper) {
     
    //     Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(cacheObjectMapper,Object.class);
        

    //     RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
    //             .entryTtl(Duration.ofMinutes(60))
    //             .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
    //             .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

    //     return RedisCacheManager.builder(connectionFactory)
    //             .cacheDefaults(cacheConfig)
    //             .build();
    // }
}
