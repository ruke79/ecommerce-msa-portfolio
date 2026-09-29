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
}
