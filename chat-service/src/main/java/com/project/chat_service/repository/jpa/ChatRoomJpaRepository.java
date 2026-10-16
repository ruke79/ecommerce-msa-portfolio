package com.project.chat_service.repository.jpa;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.project.chat_service.model.ChatRoom;

/**
 * 채팅방의 <b>DB</b> 쪽. 이름이 비슷한
 * {@link com.project.chat_service.repository.redis.ChatRoomRepository} 는 Redis 쪽이다.
 *
 * <p>🐛 <b>둘을 섞어 쓴 것이 "특정 방에서만 구독이 실패" 의 원인이었다.</b> 참여자 행
 * ({@code ParticipantChatRoom})을 만들 때 방 객체를 <b>Redis 에서 읽어</b> 연관으로 걸었는데,
 * 그것은 역직렬화된 <b>분리(detached) 객체</b>다 — JPA 가 관리하지 않는다. 그 객체로 연관을
 * 만들어 저장하면 식별자 상태에 따라 flush 단계에서 터지고, 그 예외가 SUBSCRIBE 프레임을
 * 죽여 클라이언트에는 {@code Failed to send message to ExecutorSubscribableChannel
 * [clientInboundChannel]} 만 보인다.
 *
 * <p>DB 에 쓸 연관은 DB 에서 읽은 것이어야 한다. Redis 는 방 <b>목록</b>의 캐시로만 쓴다.
 */
@Repository
public interface ChatRoomJpaRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByRoomUid(String roomUid);

    boolean existsByRoomUid(String roomUid);

    /** 목록. 만든 순서가 곧 화면 순서다 — 정렬이 없으면 DB 마음대로다. */
    java.util.List<ChatRoom> findAllByOrderByIdAsc();
}
