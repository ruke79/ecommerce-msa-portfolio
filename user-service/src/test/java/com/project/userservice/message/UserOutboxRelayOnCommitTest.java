package com.project.userservice.message;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.outbox.OutboxRelayTrigger;
import com.project.common.outbox.OutboxTraceContext;
import com.project.userservice.message.model.OutboxEvent;
import com.project.userservice.message.repository.jpa.OutboxEventRepository;
import com.project.userservice.model.User;

/**
 * 사용자 이벤트 행을 쓰면 커밋 직후 릴레이를 예약한다 (docs/56 §9).
 *
 * <p>예전에는 행을 쓰고 끝이었다 — 가입 확인 직후 채팅에 들어가면 chat 복제본이 아직 없었고, 가입 쿠폰도
 * 다음 폴링(최대 10초)까지 발급되지 않았다. 세 종류(가입·변경·탈퇴) 모두 같은 헬퍼를 지난다.
 */
@DisplayName("user-service — 사용자 이벤트 아웃박스의 즉시 릴레이")
class UserOutboxRelayOnCommitTest {

    private OutboxRelayTrigger trigger;
    private UserOutboxHelper helper;

    @BeforeEach
    void setUp() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.save(any(OutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
        trigger = mock(OutboxRelayTrigger.class);
        helper = new UserOutboxHelper(repository, new ObjectMapper(), OutboxTraceContext.NOOP, trigger);
    }

    private static User user() {
        User u = User.builder().email("buyer@example.com").username("buyer").build();
        u.setUserId(42L);
        u.setName("이름");
        return u;
    }

    @Test
    @DisplayName("🔴 가입(user-created)을 적으면 릴레이를 예약한다")
    void created() {
        helper.recordUserCreated(user());

        verify(trigger).afterCommit();
    }

    @Test
    @DisplayName("프로필 변경·탈퇴도 예약한다 — 한 행에 한 번")
    void updatedAndDeleted() {
        helper.recordUserUpdated(user());
        helper.recordUserDeleted(42L);

        verify(trigger, times(2)).afterCommit();
    }
}
