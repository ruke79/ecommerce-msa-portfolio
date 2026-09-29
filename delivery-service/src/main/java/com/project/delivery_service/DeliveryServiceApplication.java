package com.project.delivery_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling   // TrackingPoller
@EnableFeignClients // OrderServiceClient — 결제 완료 뒤 주문의 배송 단면을 읽는다
public class DeliveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryServiceApplication.class, args);
    }
}
