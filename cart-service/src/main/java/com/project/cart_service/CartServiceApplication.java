package com.project.cart_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;


// @EnableScheduling 은 멱등 원장 정리(ProcessedEventCleanup) 때문에 — common 스캔에 기대지 않고 여기서 켠다.
// [JA] 冪等台帳の掃除のため — common のスキャンに頼らずここで有効にする。
@EnableFeignClients
@EnableScheduling
@SpringBootApplication
// TypeExcludeFilter 를 명시적으로 되살린다 — 없으면 @WebMvcTest 등 슬라이스 테스트가
// 전체 컴포넌트를 로딩하게 된다(docs/14 B-17).
@ComponentScan(
		basePackages = {"com.project.cart_service", "com.project.common"},
		// AutoConfigurationExcludeFilter 도 되살린다 — 없으면 common 의 자동 설정이 사용자 설정처럼 스캔되어 조건이 너무
		// 일찍 평가되고(@ConditionalOnBean(DataSource) 가 DataSource 보다 먼저) 통째로 빠졌다: 아웃박스 파티션 잡이 이 앱에서
		// 한 번도 돌지 않았다(OutboxPartitionComponentScanTest). 자동 설정은 imports 파일로만 들어온다.
		// [JA] AutoConfigurationExcludeFilter も戻す — ないと common の自動設定がユーザー設定としてスキャンされ、条件が早すぎる時点で
		//      評価されて丸ごと外れていた(パーティションジョブが一度も動いていない)。
		excludeFilters = {
				@ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
				@ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class) })
public class CartServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CartServiceApplication.class, args);
	}

}
