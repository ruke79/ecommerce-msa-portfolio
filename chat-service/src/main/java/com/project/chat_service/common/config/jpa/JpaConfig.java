package com.project.chat_service.common.config.jpa;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.project.chat_service.repository.jpa")
public class JpaConfig {

    
  }
