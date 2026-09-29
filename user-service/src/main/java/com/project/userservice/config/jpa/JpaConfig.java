package com.project.userservice.config.jpa;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = {
    "com.project.userservice.repository.jpa",
    "com.project.userservice.message.repository.jpa"    
})
public class JpaConfig {

    
  }
