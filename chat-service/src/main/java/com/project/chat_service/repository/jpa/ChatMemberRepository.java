package com.project.chat_service.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.project.chat_service.model.ChatMember;

/**
 * 채팅 회원 — <b>member_id(= user-service userId)로 찾는다.</b> 신원은 검증된 토큰의 {@code uid} 다.
 *
 * <p>예전에는 이메일로 찾았다({@code findByEmail}). 이메일은 바뀌는 값이고, 이메일을 키로 쓰면 그 값이 메시지·세션·
 * 화면 판별까지 따라다니며 방의 상대방에게 전달됐다(docs/48 §6-⑥). 이제 회원 행에 이메일이 없다(V5).
 */
@Repository
public interface ChatMemberRepository extends JpaRepository<ChatMember, Long> {

    /**
     * 회원 행을 쓰기 잠금으로 읽는다(SELECT … FOR UPDATE) — 프로필 이벤트를 반영할 때.
     *
     * <p>🐛 user-updated 와 user-deleted 는 <b>다른 토픽</b>이라 동시에 처리될 수 있다. 둘 다 그냥 읽으면, 탈퇴가 익명화를 커밋하는
     * 사이에 프로필 변경이 옛 행을 읽고 {@code applyProfile} 의 시각 검사를 통과해 <b>탈퇴한 회원의 이름을 되살렸다</b>.
     * 잠그면 늦은 쪽은 앞선 쪽의 커밋을 기다렸다가 새 값을 읽고, 시각 검사가 의도대로 거른다.
     * [JA] user-updated と user-deleted は別トピックで同時に処理され得る。ロックしないと退会の匿名化を変更が上書きしていた。
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from ChatMember m where m.id = :id")
    java.util.Optional<ChatMember> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
