package com.project.chat_service.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.chat_service.model.ChatMember;
import com.project.chat_service.repository.jpa.ChatMemberRepository;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 회원 조회. 신원은 <b>member_id(= 검증된 토큰의 uid)</b>다(docs/48 §6-⑥).
 *
 * <p>[JA] チャット会員の照会。身元は<b>member_id(= 検証済みトークンの uid)</b>である(docs/48 §6-⑥)。
 */
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class ChatMemberService {

    private final ChatMemberRepository chatMemberRepository;

    /** 회원 행. 아직 복제되지 않았으면 null(ChatService 가 즉석에서 만든다). */
    // [JA] 会員行。まだ複製されていなければ null(ChatService が即席で作る)。
    public ChatMember findById(Long memberId) {
        return memberId == null ? null : chatMemberRepository.findById(memberId).orElse(null);
    }
}
