package com.project.coupon_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.project.coupon_service.config.WelcomeCouponProperties;

/**
 * {@code @EnableScheduling} 은 멱등 원장 정리({@code ProcessedEventCleanup}) 때문에 켰다 — 예전에는 coupon 에 예약 작업이 없었다.
 *
 * <p>[JA] {@code @EnableScheduling} は冪等台帳の掃除({@code ProcessedEventCleanup})のために有効にした — 以前は coupon に予約ジョブがなかった。
 */
@EnableFeignClients
@EnableScheduling
@EnableConfigurationProperties(WelcomeCouponProperties.class)
@SpringBootApplication
public class CouponServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CouponServiceApplication.class, args);
	}

}
