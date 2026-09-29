package com.project.cart_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;


@EnableFeignClients
@SpringBootApplication
// TypeExcludeFilter 를 명시적으로 되살린다 — 없으면 @WebMvcTest 등 슬라이스 테스트가
// 전체 컴포넌트를 로딩하게 된다(docs/14 B-17).
@ComponentScan(
		basePackages = {"com.project.cart_service", "com.project.common"},
		excludeFilters = @ComponentScan.Filter(
				type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class CartServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CartServiceApplication.class, args);
	}

}
