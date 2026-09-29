package com.project.chat_service.common.config.mongo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "spring.data.mongodb")
public class MongoProperties {
    String client;
    String name;
}
