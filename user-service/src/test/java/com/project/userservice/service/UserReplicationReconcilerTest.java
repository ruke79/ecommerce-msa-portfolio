package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.project.userservice.message.UserOutboxHelper;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;

/**
 * 복제 대사 — 최근 가입·변경·탈퇴한 사용자의 이벤트를 다시 보낸다(docs/57 §14). 받는 쪽은 모두 멱등이다.
 */
@DisplayName("사용자 복제 대사")
class UserReplicationReconcilerTest {

    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 9, 19, 0, 0);

    private static User user(long id, LocalDateTime createdAt, LocalDateTime withdrawnAt) {
        User u = new User("u" + id, "u" + id + "@example.com", "pw");
        u.setUserId(id);
        ReflectionTestUtils.setField(u, "createdAt", createdAt);
        ReflectionTestUtils.setField(u, "withdrawnAt", withdrawnAt);
        return u;
    }

    @Test
    @DisplayName("🔴 최근 가입은 created+updated, 오래된 가입자의 변경은 updated, 탈퇴는 deleted 만")
    void emitsByKind() {
        UserRepository repo = mock(UserRepository.class);
        UserOutboxHelper outbox = mock(UserOutboxHelper.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        User fresh = user(1, SINCE.plusDays(2), null);
        User old = user(2, SINCE.minusDays(90), null);
        User gone = user(3, SINCE.minusDays(90), SINCE.plusDays(1));
        when(repo.findTouchedSince(eq(SINCE), eq(0L), any())).thenReturn(List.of(fresh, old, gone));

        UserReplicationReconciler.Result r = new UserReplicationReconciler(repo, outbox, tm).reconcile(SINCE);

        assertThat(r).isEqualTo(new UserReplicationReconciler.Result(1, 2, 1));
        verify(outbox).recordUserCreated(fresh);
        verify(outbox).recordUserUpdated(fresh);
        verify(outbox).recordUserUpdated(old);
        verify(outbox, never()).recordUserCreated(old);   // 오래된 가입자에게 가입 쿠폰을 다시 만들지 않는다
        verify(outbox).recordUserDeleted(3L);
        verify(outbox, never()).recordUserUpdated(gone);
    }

    @Test
    @DisplayName("페이지를 id 커서로 끝까지 — 덜 찬 페이지에서 멈춘다")
    void pagesByCursor() {
        UserRepository repo = mock(UserRepository.class);
        UserOutboxHelper outbox = mock(UserOutboxHelper.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        List<User> first = new java.util.ArrayList<>();
        for (long i = 1; i <= 200; i++) first.add(user(i, SINCE.minusDays(30), null));
        when(repo.findTouchedSince(eq(SINCE), eq(0L), any())).thenReturn(first);
        when(repo.findTouchedSince(eq(SINCE), eq(200L), any())).thenReturn(List.of(user(201, SINCE.minusDays(30), null)));

        UserReplicationReconciler.Result r = new UserReplicationReconciler(repo, outbox, tm).reconcile(SINCE);

        assertThat(r.updated()).isEqualTo(201);
        verify(repo, never()).findTouchedSince(eq(SINCE), eq(201L), any());
        verify(outbox, never()).recordUserDeleted(anyLong());
    }
}
