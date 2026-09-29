package com.project.coupon_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

import com.project.coupon_service.config.WelcomeCouponProperties;

@EnableFeignClients
@EnableConfigurationProperties(WelcomeCouponProperties.class)
@SpringBootApplication
public class CouponServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CouponServiceApplication.class, args);
	}

}
