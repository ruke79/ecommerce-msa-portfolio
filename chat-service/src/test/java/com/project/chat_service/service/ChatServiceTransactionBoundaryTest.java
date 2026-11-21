package com.project.chat_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

/**
 * ChatService 의 트랜잭션 경계 — Kafka · Redis · Mongo · 잠금 대기만 하는 메서드는 DB 트랜잭션 밖, JPA 를 쓰는 메서드만 안(docs/93 ③ 3단계).
 *
 * <p>🐛 2026-11-21 까지 클래스 전체에 {@code @Transactional} 이 있어 메시지 하나마다 DB 커넥션을 쥔 채 Kafka 로 보냈다(max.block 5초) —
 * 브로커가 흔들리면 커넥션 풀이 바닥날 수 있었다. 판정은 Spring 이 실제로 쓰는 {@link AnnotationTransactionAttributeSource} 에 묻는다
 * (클래스 단위 애노테이션까지 똑같이 따진다).
 *
 * <p>[JA] ChatService のトランザクション境界 — Kafka · Redis · Mongo · ロック待ちだけのメソッドは DB トランザクションの外、JPA を使う
 *      メソッドだけ中。判定は Spring が実際に使う AnnotationTransactionAttributeSource に聞く(クラス単位の注釈も同じに数える)。
 */
@DisplayName("ChatService — 트랜잭션은 JPA 를 쓰는 메서드에만")
class ChatServiceTransactionBoundaryTest {

    private static final AnnotationTransactionAttributeSource SOURCE = new AnnotationTransactionAttributeSource();

    private static boolean transactional(String name) {
        List<Method> methods = Arrays.stream(ChatService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name)).toList();
        assertThat(methods).as(name + " 이 없다 — 이름이 바뀌었으면 이 시험을 고친다").isNotEmpty();
        return methods.stream().anyMatch(m -> SOURCE.getTransactionAttribute(m, ChatService.class) != null);
    }

    @Test
    @DisplayName("🔴 Kafka 발행 · Redis · Mongo · 잠금을 기다리는 메서드는 트랜잭션 밖 — 커넥션을 쥔 채 바깥을 기다리지 않는다")
    void outboundOnlyMethodsRunOutsideTransactions() {
        for (String name : List.of("sendMessage", "sendEnterMessage", "sendLeaveMessage", "disconnect", "saveMessage",
                "getRoomMessagesByCurser", "createChatRoom")) {
            assertThat(transactional(name)).as(name + " 은 DB 트랜잭션 밖이어야 한다").isFalse();
        }
    }

    @Test
    @DisplayName("JPA 를 쓰는 메서드는 그대로 트랜잭션 안 — 참여자 등록 · 회원 즉석 생성 · 방 조회")
    void jpaMethodsStayTransactional() {
        for (String name : List.of("connect", "ensureMember", "saveChatRoom", "roomExists", "fildAllRooms")) {
            assertThat(transactional(name)).as(name + " 은 트랜잭션 안이어야 한다").isTrue();
        }
    }
}
