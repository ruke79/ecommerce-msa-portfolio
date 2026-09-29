package com.project.userservice.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.project.common.message.dto.request.OutgoingMailRequest;
import com.project.userservice.message.producer.OutgoingMailProducer;
import com.project.userservice.model.User;
import com.project.userservice.service.UserService;

/**
 * 가입 확인 메일 요청이 <b>실제로 발행되는지</b>를 Spring 의 이벤트 기반 위에서 확인한다.
 *
 * <p>이 테스트가 있는 이유는 하나다. {@code @TransactionalEventListener} 는 발행 시점에 활성
 * 트랜잭션이 없으면 리스너를 <b>조용히 건너뛴다</b>. 컨트롤러는 트랜잭션 밖에서 발행하므로,
 * {@code fallbackExecution = true} 가 빠지는 순간 가입 확인 메일이 다시 한 통도 나가지 않는다.
 * 그리고 그 실패는 <b>아무 로그도 남기지 않는다</b> — 리스너가 실행되지 않으니 성공도 실패도
 * 기록되지 않는다. 실제 배포에서 {@code outgoing-mail} 오프셋 0 으로 발견한 버그다(docs/38 §1.4).
 *
 * <p>그래서 애노테이션을 문자열로 검사하지 않고 <b>스프링 이벤트 인프라를 실제로 태운다</b>.
 * 트랜잭션 매니저를 두지 않았으므로 발행 시점에 트랜잭션이 없고, 그게 운영에서의 조건과 같다.
 * {@code @Async} 는 이 컨텍스트에 {@code @EnableAsync} 가 없어 동기로 실행된다.
 */
@SpringJUnitConfig(RegistrationListenerDeliveryTest.Config.class)
@TestPropertySource(properties = "app.public-url=https://shop.example.com/")
@DisplayName("가입 확인 메일 — 트랜잭션 밖에서 발행해도 리스너가 돈다")
class RegistrationListenerDeliveryTest {

    @Configuration
    static class Config {
        @Bean
        RegistrationListener registrationListener() {
            return new RegistrationListener();
        }

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        OutgoingMailProducer outgoingMailProducer() {
            return mock(OutgoingMailProducer.class);
        }

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private OutgoingMailProducer mailProducer;

    @Autowired
    private UserService userService;

    /**
     * 스프링 테스트 컨텍스트는 <b>테스트 메서드 사이에 캐시된다.</b> 같은 목 인스턴스가 재사용되므로
     * 호출 기록이 누적되고, 두 번째 테스트의 {@code verify} 가 "2번 호출됨"으로 깨진다.
     */
    @BeforeEach
    void resetMocks() {
        Mockito.reset(mailProducer, userService);
    }

    private static User newUser() {
        return new User("tester", "tester@example.com", "encoded-password");
    }

    @Test
    @DisplayName("트랜잭션이 없어도 outgoing-mail 요청이 발행된다")
    void publishesMailRequestWithoutAnActiveTransaction() {
        when(mailProducer.publish(any(OutgoingMailRequest.class))).thenReturn(true);

        publisher.publishEvent(new OnRegistrationCompleteEvent(newUser(), Locale.KOREAN, "https://ignored"));

        ArgumentCaptor<OutgoingMailRequest> sent = ArgumentCaptor.forClass(OutgoingMailRequest.class);
        verify(mailProducer).publish(sent.capture());

        OutgoingMailRequest request = sent.getValue();
        assertThat(request.getToEmail()).isEqualTo("tester@example.com");
        assertThat(request.getKind()).isEqualTo("REGISTRATION_CONFIRM");
        assertThat(request.getLocale()).isEqualTo("ko");
    }

    @Test
    @DisplayName("확인 토큰을 먼저 만들고, 링크는 app.public-url 을 따른다")
    void createsTokenAndBuildsLinkFromConfiguredPublicUrl() {
        when(mailProducer.publish(any(OutgoingMailRequest.class))).thenReturn(true);

        publisher.publishEvent(new OnRegistrationCompleteEvent(newUser(), Locale.KOREAN, "https://ignored"));

        // 토큰이 없으면 링크를 눌러도 활성화되지 않는다 — 발행보다 먼저 만들어져야 한다.
        verify(userService).createVerificationTokenForUser(any(User.class), any(String.class));

        ArgumentCaptor<OutgoingMailRequest> sent = ArgumentCaptor.forClass(OutgoingMailRequest.class);
        verify(mailProducer).publish(sent.capture());

        String url = sent.getValue().getParams().get("confirmationUrl");
        // 설정값 끝의 슬래시가 중복되면 안 된다. 이벤트의 appUrl 은 쓰이지 않는다.
        assertThat(url).startsWith("https://shop.example.com/user-service/registrationConfirm?token=");
        assertThat(url).doesNotContain("ignored").doesNotContain("//user-service");
    }
}
