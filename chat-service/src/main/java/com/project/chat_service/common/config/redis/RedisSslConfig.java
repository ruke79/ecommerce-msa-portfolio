// package com.project.chat_service.common.config.redis;

// import java.io.IOException;

// import org.springframework.beans.factory.annotation.Qualifier;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.context.annotation.Primary;
// import org.springframework.core.io.ResourceLoader;
// import org.springframework.data.redis.connection.RedisConnectionFactory;
// import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
// import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
// import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
// import org.springframework.data.redis.core.RedisTemplate;
// import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
// import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
// import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
// import org.springframework.data.redis.serializer.StringRedisSerializer;

// import com.fasterxml.jackson.databind.DeserializationFeature;
// import com.fasterxml.jackson.databind.ObjectMapper;
// import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

// import io.lettuce.core.ClientOptions;
// import io.lettuce.core.SslOptions;
// import io.lettuce.core.protocol.ProtocolVersion;
// import lombok.RequiredArgsConstructor;
// import lombok.extern.slf4j.Slf4j;

// @Slf4j
// @Configuration
// @RequiredArgsConstructor
// @EnableRedisRepositories(basePackages = "com.project.chat_service.repository.redis")
// public class RedisSslConfig {

//         @Value("${spring.data.redis.host}")
//     private String host;

//     @Value("${spring.data.redis.port}")
//     private int port;

//     @Value("${spring.data.redis.password}")
//     private String  password;

//     @Value("${spring.data.redis.ssl.enabled}")
//     private boolean useSsl;


//     @Value("${spring.data.redis.ssl.cacert}")
//     private String cacertFileLocation;

//     @Value("${spring.data.redis.ssl.keystore}")
//     private String keyFileLocation;

//     @Value("${spring.data.redis.ssl.truststore}")
//     private String certFileLocation;

//     @Value("${spring.data.redis.ssl.storePassword}")
//     private String storePassword;

//      private final ResourceLoader resourceLoader;

//   @Bean
//   RedisConnectionFactory redisConnectionFactory() throws IOException {
//     RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration();
//     redisStandaloneConfiguration.setHostName(host);
//     redisStandaloneConfiguration.setPort(port);
//     redisStandaloneConfiguration.setPassword(password);

//     LettuceClientConfiguration.LettuceClientConfigurationBuilder lettuceClientConfigurationBuilder =
//         LettuceClientConfiguration.builder();

//     if (useSsl){
//       SslOptions sslOptions = SslOptions.builder()
//           .trustManager(resourceLoader.getResource("file:" + cacertFileLocation).getFile())
//           .truststore(resourceLoader.getResource("file:" + certFileLocation).getFile(), storePassword)
//           .keystore(resourceLoader.getResource("file:" + keyFileLocation).getFile(), storePassword.toCharArray())          
//           .build();

    

//       ClientOptions clientOptions = ClientOptions
//           .builder()
//           .sslOptions(sslOptions)
//           .protocolVersion(ProtocolVersion.RESP3)
//           .build();

//       lettuceClientConfigurationBuilder
//           .clientOptions(clientOptions)
//           .useSsl().disablePeerVerification();
//     }

//     LettuceClientConfiguration lettuceClientConfiguration = lettuceClientConfigurationBuilder.build();

//     log.info("Redis Setting");

//     return new LettuceConnectionFactory(redisStandaloneConfiguration, lettuceClientConfiguration);
//   }

          
//     @Bean
//     public RedisTemplate<String, Object> redisTemplate() throws IOException{
//         RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
//         redisTemplate.setConnectionFactory(redisConnectionFactory());
//         redisTemplate.setKeySerializer(new StringRedisSerializer());
//         redisTemplate.setValueSerializer(new Jackson2JsonRedisSerializer<>(String.class));
//         return redisTemplate;
//     }
// }
