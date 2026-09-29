package com.project.eurekaserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * eureka-server 컨텍스트 로딩 테스트.
 *
 * <p>이 서비스는 외부 인프라가 필요 없다 — 예전에 항상 실패했던 이유는 TLS 키스토어 <b>환경변수</b>
 * 가 없으면 기동조차 못 했기 때문이다:
 *
 * <pre>
 *   KeyStoreException: KeyStore not exists: ServletContext resource [/${LOCAL_TRUST_STORE_PATH}]
 * </pre>
 *
 * <p>해석되지 않은 placeholder 가 그대로 경로가 되어 버린 형태다. 테스트에서는 TLS 를 끄고
 * 컨텍스트가 실제로 뜨는지만 확인한다 — 컨테이너가 필요 없으므로 'containers' 태그도 붙이지 않는다.
 */
@SpringBootTest
@DisplayName("eureka-server 기동")
class EurekaServerApplicationTests {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		// 자기 자신에게 등록/조회하지 않는 단독 모드. eureka.client.enabled 까지 끄면
		// 서버 자동설정이 요구하는 ApplicationInfoManager 빈이 사라져 컨텍스트가 뜨지 않는다.
		registry.add("eureka.client.register-with-eureka", () -> false);
		registry.add("eureka.client.fetch-registry", () -> false);
		// TLS 는 이 테스트의 관심사가 아니다. 켜 두면 키스토어 파일이 실제로 있어야 한다.
		registry.add("server.ssl.enabled", () -> false);
		registry.add("eureka.client.tls.enabled", () -> false);
		registry.add("LOCAL_KEY_STORE_PATH", () -> "");
		registry.add("LOCAL_TRUST_STORE_PATH", () -> "");
		registry.add("LOCAL_KEY_STORE_PASSWORD", () -> "");
		registry.add("LOCAL_KEY_PASSWORD", () -> "");
	}

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("애플리케이션 컨텍스트가 실제로 뜬다")
	void contextLoads() {
		assertThat(context).isNotNull();
	}
}
