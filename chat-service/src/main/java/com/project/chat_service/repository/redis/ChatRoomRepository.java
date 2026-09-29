package com.project.chat_service.repository.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Repository;

import com.project.chat_service.model.ChatRoom;
import com.project.common.dto.chat.ChatRoomDto;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import jakarta.annotation.Resource;
import java.net.MalformedURLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Repository
public class ChatRoomRepository {

    private static final String CHAT_ROOMS = "CHAT_ROOM";
    public static final String USER_COUNT = "USER_COUNT";
    public static final String ENTER_INFO = "ENTER_INFO";
    
    @Resource(name = "redisTemplate")
    private HashOperations<String, String, ChatRoom> hashOpsChatRoom;
    @Resource(name = "redisTemplate")
    private HashOperations<String, String, String> hashOpsEnterInfo;
    @Resource(name = "redisTemplate")
    private ValueOperations<String, String> valueOps;

    private final RedissonClient redissonClient;

    // 모든 채팅방 조회
    public List<ChatRoom> findAllRoom() {
        return hashOpsChatRoom.values(CHAT_ROOMS);
    }

    // 특정 채팅방 조회
    public ChatRoom findRoomById(String id) {
        return hashOpsChatRoom.get(CHAT_ROOMS, id);
    }

    // 채팅방 생성 : 서버간 채팅방 공유를 위해 redis hash에 저장한다.
    public ChatRoomDto saveChatRoom(ChatRoom chatRoom) throws MalformedURLException {        
        
        String lockKey = "chatroom:lock:" + chatRoom.getRoomUid();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean isLocked = lock.tryLock(10, 5, TimeUnit.SECONDS);
            if (isLocked) {
                try {
                    // 락을 획득한 후에만 채팅방 생성 로직 수행
                    // 중복 생성을 방지하기 위해 이미 존재하는지 다시 확인
                    if (findRoomById(chatRoom.getRoomUid()) == null) {
                        hashOpsChatRoom.put(CHAT_ROOMS, chatRoom.getRoomUid(), chatRoom);
                    }
                } finally {
                    lock.unlock();
                }
            } else {
                throw new IllegalStateException("Failed to acquire lock for chatroom creation.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring lock", e);
        }
        
        return ChatRoomDto.builder()
                .name(chatRoom.getRoomName())
                .roomId(chatRoom.getRoomUid())                
                .build();
    }

    // 유저가 입장한 채팅방ID와 유저 세션ID 맵핑 정보 저장
    public void setUserEnterInfo(String sessionId, String roomId) {
        hashOpsEnterInfo.put(ENTER_INFO, sessionId, roomId);
    }

    // 유저 세션으로 입장해 있는 채팅방 ID 조회
    public String getUserEnterRoomId(String sessionId) {
        return hashOpsEnterInfo.get(ENTER_INFO, sessionId);
    }

    // 유저 세션정보와 맵핑된 채팅방ID 삭제
    public void removeUserEnterInfo(String sessionId) {
        hashOpsEnterInfo.delete(ENTER_INFO, sessionId);
    }
}