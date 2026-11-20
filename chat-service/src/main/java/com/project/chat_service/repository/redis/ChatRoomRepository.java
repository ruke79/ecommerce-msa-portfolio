package com.project.chat_service.repository.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.stereotype.Repository;



import jakarta.annotation.Resource;

@RequiredArgsConstructor
@Repository
public class ChatRoomRepository {

    public static final String USER_COUNT = "USER_COUNT";
    public static final String ENTER_INFO = "ENTER_INFO";
    
    @Resource(name = "redisTemplate")
    private HashOperations<String, String, String> hashOpsEnterInfo;


    // 방 자체는 Redis 에 두지 않는다 — 진실은 DB 하나다(ChatService.fildAllRooms). 예전의 방 해시(CHAT_ROOM)는
    // 쓰기만 하고 읽는 곳이 없어 없앴다. 남은 키는 `DEL CHAT_ROOM` 으로 지워도 된다(docs/62 §18).
    // [JA] ルーム自体は Redis に置かない — 真実は DB 一つである(ChatService.fildAllRooms)。以前のルームハッシュ(CHAT_ROOM)は
    //      書くだけで読む箇所がなかったので削除した。残ったキーは `DEL CHAT_ROOM` で消してよい(docs/62 §18)。

    // 유저가 입장한 채팅방ID와 유저 세션ID 맵핑 정보 저장
    // [JA] ユーザーが入室したチャットルーム ID とユーザーセッション ID のマッピング情報を保存
    public void setUserEnterInfo(String sessionId, String roomId) {
        hashOpsEnterInfo.put(ENTER_INFO, sessionId, roomId);
    }

    // 유저 세션으로 입장해 있는 채팅방 ID 조회
    // [JA] ユーザーセッションで入室しているチャットルーム ID を照会
    public String getUserEnterRoomId(String sessionId) {
        return hashOpsEnterInfo.get(ENTER_INFO, sessionId);
    }

    // 유저 세션정보와 맵핑된 채팅방ID 삭제
    // [JA] ユーザーセッション情報とマッピングされたチャットルーム ID を削除
    public void removeUserEnterInfo(String sessionId) {
        hashOpsEnterInfo.delete(ENTER_INFO, sessionId);
    }
}