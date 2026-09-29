package com.project.chat_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

// @SpringBootApplication(exclude = {
// 	MongoAutoConfiguration.class, 
// 	MongoDataAutoConfiguration.class
//   })
//@EnableWebMvc
@EnableFeignClients
@EnableJpaAuditing
@SpringBootApplication
// 명시적 @ComponentScan 은 @SpringBootApplication 이 기본으로 달아 주는 TypeExcludeFilter 를
// 대체해 버린다. 그 결과 @WebMvcTest 슬라이스가 동작하지 않아 웹 계층만 띄워야 할 테스트가
// Kafka 컨슈머·JPA 리포지토리까지 전부 로딩하다 'No bean named entityManagerFactory' 로
// 실패했다(docs/14 B-17). 필터를 되돌려 주면 com.project.common 스캔은 유지하면서
// 슬라이스 테스트도 정상 동작한다.
@ComponentScan(
		basePackages = {"com.project.chat_service", "com.project.common"},
		excludeFilters = @ComponentScan.Filter(
				type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class ChatServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChatServerApplication.class, args);
	}

}
