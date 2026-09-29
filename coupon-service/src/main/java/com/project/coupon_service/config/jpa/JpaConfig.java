package com.project.coupon_service.config.jpa;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.project.coupon_service.repository.jpa")
public class JpaConfig {

    
  }
