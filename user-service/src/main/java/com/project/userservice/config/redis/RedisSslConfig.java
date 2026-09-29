package com.project.userservice.config.redis;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
@EnableRedisRepositories(basePackages = "com.project.userservice.repository.redis")
public class RedisSslConfig {

        @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password}")
    private String  password;

    @Value("${spring.data.redis.ssl.enabled}")
    private boolean useSsl;


    @Value("${spring.data.redis.ssl.cacert}")
    private String cacertFileLocation;

    @Value("${spring.data.redis.ssl.keystore}")
    private String keystoreFileLocation;

    // @Value("${spring.data.redis.ssl.truststore}")
    //private String truststoreFileLocation;

    @Value("${spring.data.redis.ssl.storePassword}")
    private String storePassword;

     private final ResourceLoader resourceLoader;

  @Bean
  RedisConnectionFactory redisConnectionFactory() throws IOException {
    RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration();
    redisStandaloneConfiguration.setHostName(host);
    redisStandaloneConfiguration.setPort(port);
    redisStandaloneConfiguration.setPassword(password);

    LettuceClientConfiguration.LettuceClientConfigurationBuilder lettuceClientConfigurationBuilder =
        LettuceClientConfiguration.builder();

    if (useSsl){
      SslOptions sslOptions = SslOptions.builder()
          .trustManager(resourceLoader.getResource("file:" + cacertFileLocation).getFile())
          //.truststore(resourceLoader.getResource("file:" + truststoreFileLocation).getFile(), storePassword)
          .keystore(resourceLoader.getResource("file:" + keystoreFileLocation).getFile(), storePassword.toCharArray())          
          .build();

      ClientOptions clientOptions = ClientOptions
          .builder()
          .sslOptions(sslOptions)
          .protocolVersion(ProtocolVersion.RESP3)
          .build();

      lettuceClientConfigurationBuilder
          .clientOptions(clientOptions)
          .useSsl(); //.disablePeerVerification();
    }

    LettuceClientConfiguration lettuceClientConfiguration = lettuceClientConfigurationBuilder.build();

    return new LettuceConnectionFactory(redisStandaloneConfiguration, lettuceClientConfiguration);
  }

   @Bean
   @Primary
   public RedisTemplate<String, Object> redisTemplate(@Qualifier("redisObjectMapper") ObjectMapper objectMapper) throws IOException {
       RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
       redisTemplate.setKeySerializer(new StringRedisSerializer());
       //redisTemplate.setValueSerializer(new StringRedisSerializer());
       redisTemplate.setValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
       redisTemplate.setConnectionFactory(redisConnectionFactory());
       return redisTemplate;
   }

    @Bean(name = "redisObjectMapper")
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());   // Java 8 date/time type `java.time.LocalDateTime` not supported by default
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return objectMapper;
    }
  

   @Bean
   public StringRedisTemplate stringRedisTemplate() throws IOException {
       final StringRedisTemplate stringRedisTemplate = new StringRedisTemplate();
       stringRedisTemplate.setKeySerializer(new StringRedisSerializer());
       stringRedisTemplate.setValueSerializer(new StringRedisSerializer());
       stringRedisTemplate.setConnectionFactory(redisConnectionFactory());
       return stringRedisTemplate;
   }
}
