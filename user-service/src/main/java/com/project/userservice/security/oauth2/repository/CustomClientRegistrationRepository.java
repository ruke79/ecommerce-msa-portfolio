package com.project.userservice.security.oauth2.repository;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import com.project.userservice.security.oauth2.SocialClientRegistration;

/**
 * 설정된 프로바이더만 담는 레지스트리.
 *
 * <p>⚠️ {@link InMemoryClientRegistrationRepository} 는 <b>빈 목록을 거부한다</b>
 * (IllegalArgumentException). 소셜 로그인을 하나도 설정하지 않은 배포 — 지금까지의 모든
 * 배포가 그렇다 — 에서 그대로 쓰면 user-service 가 기동조차 못 한다. 그래서 목록이
 * 비어 있으면 "아무것도 못 찾는" 레포지토리로 대체한다. oauth2Login 필터는 뜨지만
 * 어떤 provider 경로도 매칭되지 않으므로 무해하다.
 */
@Configuration
public class CustomClientRegistrationRepository {

    private final List<ClientRegistration> registrations;

    /**
     * ⚠️ 인스턴스를 하나로 고정한다.
     *
     * <p>예전에는 호출할 때마다 {@code new InMemoryClientRegistrationRepository(...)} 를
     * 돌려줬다. 호출자가 하나일 때는 문제가 없었지만, PKCE 리졸버가 추가되면서 이 메서드를
     * 부르는 곳이 둘이 됐다 — 인가 요청을 만드는 쪽(리졸버)과 콜백에서 토큰을 교환하는
     * 쪽(로그인 필터)이 <b>서로 다른 레지스트리를 보게 된다.</b> 지금 등록 내용이 정적이라
     * 결과는 같지만, 나중에 등록을 동적으로 바꾸는 순간 "인가는 됐는데 토큰 교환이
     * 실패한다"는 형태로 나타난다. 원인을 찾기 어려운 부류라 미리 막는다.
     */
    private final ClientRegistrationRepository repository;

    public CustomClientRegistrationRepository(SocialClientRegistration socialClientRegistration) {
        this.registrations = socialClientRegistration.configuredRegistrations();
        this.repository = registrations.isEmpty()
                ? registrationId -> null
                : new InMemoryClientRegistrationRepository(registrations);
    }

    public ClientRegistrationRepository clientRegistrationRepository() {
        return repository;
    }

    /** 프론트엔드가 버튼을 그릴 때 쓴다 — /api/auth/public/oauth2/providers */
    public List<String> enabledProviders() {
        return registrations.stream().map(ClientRegistration::getRegistrationId).toList();
    }
}
