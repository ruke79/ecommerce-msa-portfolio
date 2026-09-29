// package com.project.order_service.config.redis;

// import org.redisson.api.RedissonClient;

// import java.io.File;
// import java.io.IOException;
// import java.math.BigDecimal;
// import java.net.URL;
// import java.time.Duration;
// import java.time.Instant;
// import java.time.LocalDateTime;
// import java.util.ArrayList;
// import java.util.HashMap;
// import java.util.HashSet;
// import java.util.LinkedHashMap;
// import java.util.LinkedHashSet;
// import java.util.Map;
// import java.util.Optional;

// import org.redisson.Redisson;
// import org.redisson.config.Config;
// import org.redisson.config.SingleServerConfig;
// import org.redisson.spring.data.connection.RedissonConnectionFactory;
// import org.springframework.beans.factory.annotation.Qualifier;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.cache.CacheManager;
// import org.springframework.cache.annotation.EnableCaching;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.data.redis.cache.RedisCacheConfiguration;
// import org.springframework.data.redis.cache.RedisCacheManager;
// import org.springframework.data.redis.core.RedisTemplate;
// import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
// import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
// import org.springframework.data.redis.serializer.RedisSerializationContext;
// import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
// import org.springframework.data.redis.serializer.StringRedisSerializer;

// import com.fasterxml.jackson.databind.ObjectMapper;
// import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
// import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
// import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

// import lombok.extern.slf4j.Slf4j;

// @Slf4j
// @EnableCaching
// @Configuration
// public class RedissonConfig {

//     // Redis 서버의 비밀번호를 주입받습니다.
//     @Value("${spring.data.redis.password}")
//     private String password;

//     @Value("${spring.data.redis.ssl.enabled}")
//     private boolean useSsl;

//     @Value("${spring.data.redis.ssl.truststore}")
//     private String truststoreFileLocation;

//     @Value("${spring.data.redis.ssl.keystore}")
//     private String keystoreFileLocation;

//     @Value("${spring.data.redis.ssl.storePassword}")
//     private String storePassword;

//     @Value("${spring.data.redis.host}")
//     private String host;
//     @Value("${spring.data.redis.port}")
//     private int port;

//     @Bean
//     public RedissonClient redissonClient() {
//         Config config = new Config();
//         SingleServerConfig serverConfig = config.useSingleServer();

//         // Redis 서버 주소 및 비밀번호 설정 (SSL 사용 여부와 관계없이 공통으로 적용)
//         serverConfig.setAddress("rediss://" + host + ":" + port);
//         serverConfig.setPassword(password);

//         if (useSsl) {
//             try {
//                 // File 객체를 URL 객체로 변환
//                 URL truststoreUrl = new File(truststoreFileLocation).toURI().toURL();
//                 URL keystoreUrl = new File(keystoreFileLocation).toURI().toURL();

//                 serverConfig.setSslTruststore(truststoreUrl);
//                 serverConfig.setSslKeystore(keystoreUrl);

//                 if (storePassword != null && !storePassword.isEmpty()) {
//                     serverConfig.setSslTruststorePassword(storePassword);
//                     serverConfig.setSslKeystorePassword(storePassword);
//                     log.debug("SSL truststore and keystore passwords are set.");
//                 } else {
//                     log.warn("SSL store password is not provided. Proceeding without it.");
//                 }

//                 log.info("Redisson SSL configuration applied successfully.");
//             } catch (IOException e) {
//                 // 예외 발생 시 더 구체적인 정보를 로그로 남김
//                 log.error("Failed to load SSL certificates for Redis. Check file paths and permissions.", e);
//                 throw new IllegalStateException("Failed to load SSL certificates for Redis.", e);
//             }
//         } else {
//             log.info("SSL is not enabled for Redisson connection.");
//         }
//         return Redisson.create(config);
//     }
     

//     // ObjectMapper를 주입받아 RedisTemplate을 설정합니다.
//     @Bean
//     public RedisTemplate<String, Object> redisTemplate(RedissonClient redissonClient, 
//      @Qualifier("cacheObjectMapper") ObjectMapper cacheObjectMapper) {       
        
//         RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();

//         // RedissonConnectionFactory를 사용하도록 설정합니다.
//         redisTemplate.setConnectionFactory(new RedissonConnectionFactory(redissonClient));

//         // ObjectMapper를 사용하는 Jackson2JsonRedisSerializer를 생성합니다.
//         Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(cacheObjectMapper, Object.class);
        

//         // Key와 Hash Key는 String으로 직렬화
//         redisTemplate.setKeySerializer(new StringRedisSerializer());
//         redisTemplate.setHashKeySerializer(new StringRedisSerializer());

//         // Value와 Hash Value는 설정된 ObjectMapper를 사용하는 JSON 직렬화기로 설정합니다.
//         redisTemplate.setValueSerializer(jsonSerializer);
//         redisTemplate.setHashValueSerializer(jsonSerializer);

//         return redisTemplate;
//     }

//     @Bean
//     public CacheManager cacheManager(RedissonConnectionFactory connectionFactory, 
//      @Qualifier("cacheObjectMapper") ObjectMapper cacheObjectMapper) {
     
//         Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(cacheObjectMapper,Object.class);

//           // 캐시 이름별 설정 맵 생성
//         Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

//         // `orderList` 캐시 설정: 60분 보관 (원하는 보관 기간으로 변경 가능)
//         cacheConfigurations.put("orderList", redisCacheConfiguration(Duration.ofMinutes(60), jsonSerializer));
        
//         // `ordersPage` 캐시 설정: 60분 보관
//         cacheConfigurations.put("ordersPage", redisCacheConfiguration(Duration.ofMinutes(60), jsonSerializer));

//         cacheConfigurations.put("orders", redisCacheConfiguration(Duration.ofMinutes(60), jsonSerializer));

//         cacheConfigurations.put("ordersCursor", redisCacheConfiguration(Duration.ofMinutes(60), jsonSerializer));

//         cacheConfigurations.put("customers", redisCacheConfiguration(Duration.ofMinutes(60), jsonSerializer));

      
//         return RedisCacheManager.builder(connectionFactory)
//             .initialCacheNames(cacheConfigurations.keySet())
//             .withInitialCacheConfigurations(cacheConfigurations)
//             .cacheDefaults(redisCacheConfiguration(Duration.ofMinutes(60), jsonSerializer).disableCachingNullValues())
//             .build();
//     }

//     private RedisCacheConfiguration redisCacheConfiguration(Duration duration,
//     Jackson2JsonRedisSerializer<Object> jsonSerializer ) {
//         return RedisCacheConfiguration
//                 .defaultCacheConfig()
//                 .entryTtl(duration)
//                 .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
//                 .serializeValuesWith(SerializationPair.fromSerializer(jsonSerializer));
//     }
// }
