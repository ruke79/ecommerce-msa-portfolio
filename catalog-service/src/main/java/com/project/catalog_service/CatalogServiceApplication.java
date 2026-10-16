package com.project.catalog_service;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} 은 기획전 때문에 켰다({@code EventScheduler}). "금요일 0시부터"를
 * 사람이 그 시각에 버튼을 눌러 운영하면 반드시 놓치고, 시작 시점에 알림도 나가야 한다.
 * 멱등 원장 정리({@code ProcessedEventCleanup})도 이것으로 돈다.
 */
@SpringBootApplication
@EnableScheduling
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class CatalogServiceApplication {
	 

	public static void main(String[] args) {
		SpringApplication.run(CatalogServiceApplication.class, args);

	}

	   

}
