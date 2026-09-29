package com.project.chat_service.repository.mongo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import com.project.chat_service.model.ChatMessage;



@Repository
public interface ChatMessageRepository extends MongoRepository<ChatMessage, String> {

    Optional<List<ChatMessage>> findByRoomId(String roomId);

    /**
     * 🐛 id 는 숫자를 담은 <b>문자열</b>이다(SequenceGeneratorService). 그냥 정렬 · 비교하면 사전순이라 {@code "9" > "10"} —
     * 자릿수가 바뀌는 순간 "최신 20개" 가 옛 메시지를 고르고, "더 보기" 가 메시지를 건너뛰거나 두 번 보였다(docs/58 §7).
     * 그래서 이 두 조회는 숫자 순서로 비교하는 collation({@code numericOrdering})으로 정렬 · 비교한다.
     */
    String NUMERIC_ID = "{ 'locale': 'en', 'numericOrdering': true }";

    /** 방의 최신 메시지부터(숫자 id 내림차순). */
    @Query(value = "{ 'roomId': ?0 }", sort = "{ '_id': -1 }", collation = NUMERIC_ID)
    Slice<ChatMessage> findLatestInRoom(String roomId, Pageable page);

    /** 커서(id)보다 오래된 메시지부터 — "더 보기". */
    @Query(value = "{ 'roomId': ?0, '_id': { '$lt': ?1 } }", sort = "{ '_id': -1 }", collation = NUMERIC_ID)
    Slice<ChatMessage> findOlderInRoom(String roomId, String cursorId, Pageable page);


}