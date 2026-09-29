package com.project.common.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Configuration
public class RedisConfig {

         // Global ObjectMapper 설정
    @Bean
    public ObjectMapper objectMapper() {

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());        
        return objectMapper;
    }

    @Bean
    public ObjectMapper cacheObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.project.")
                .allowIfSubType(ArrayList.class)
                .allowIfSubType(LinkedHashMap.class)
                .allowIfSubType(LinkedHashSet.class)
                .allowIfSubType(HashMap.class)
                .allowIfSubType(HashSet.class)
                .allowIfSubType(LocalDateTime.class)
                .allowIfSubType(BigDecimal.class)
                .allowIfSubType(Instant.class)
                .allowIfSubType(Optional.class)
                .build();
        objectMapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        return objectMapper;
    }
}