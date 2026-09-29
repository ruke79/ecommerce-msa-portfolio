package com.project.pay_service.common.config.jpa;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = {
    "com.project.pay_service.repository.jpa",
    "com.project.pay_service.outbox.repository.jpa"
})
public class JpaConfig {

    
  }
