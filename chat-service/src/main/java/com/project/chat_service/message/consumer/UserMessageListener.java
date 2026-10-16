package com.project.chat_service.message.consumer;

import static org.springframework.data.mongodb.core.query.Query.query;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.project.chat_service.common.exception.UserDomainException;
import com.project.chat_service.model.ChatMember;
import com.project.chat_service.model.ChatMessage;
import com.project.chat_service.repository.jpa.ChatMemberRepository;
import com.project.common.message.dto.request.UserCreatedRequest;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserMessageListener {

    private final ChatMemberRepository chatMemberRepository;
    /** 탈퇴 시 대화 기록의 발신자 표시를 지우는 데 쓴다(userDeleted). */
    private final MongoTemplate mongoTemplate;

    
    @Transactional
    void userCreated(UserCreatedRequest createdUser) {

        // 접속 순간 즉석으로 만든 행(ChatService.createMissingMember)이 먼저 있을 수 있다 — 그 행의 닉네임은 임시값이다.
        // 예전에는 여기서 id 충돌을 삼키고 끝나 임시 닉네임이 영영 남았다. 원본의 값으로 채운다. 이미 프로필 변경을
        // 받았거나 탈퇴했으면 applyProfile 이 거른다(가입 이벤트가 가장 오래된 값이다 — 시각 0).
        java.util.Optional<ChatMember> existing = chatMemberRepository.findForUpdate(createdUser.getUserId());
        if (existing.isPresent()) {
            if (existing.get().applyProfile(createdUser.getNickname(), createdUser.getImage(), 0L)) {
                log.info("user-created: 즉석으로 만든 채팅 회원을 원본 프로필로 채웠다. id={}", createdUser.getUserId());
            }
            return;
        }

        try {
        ChatMember customer = chatMemberRepository.saveAndFlush(ChatMember.builder().
                                id(createdUser.getUserId()).        
                                imageUrl(createdUser.getImage()).                                
                                nickname(createdUser.getNickname()).
                                // 이메일은 복제하지 않는다 — chat 의 신원은 id 다(docs/48 §6-⑥). 이벤트에 실려 와도 버린다.
                                build());
        
        if (customer == null) {
            log.error("Member could not be created in chat database with id: {}", createdUser.getUserId());
            throw new UserDomainException("Member could not be created in order database with id " +
            createdUser.getUserId());
        }
        log.info("Customer is created in order database with id: {}", customer.getId());

         }
        catch(DataIntegrityViolationException err) {
            // 🔒 예외 문구는 남기지 않는다 — SQL 상세에 닉네임 · 이미지 주소 값이 실린다. 종류와 id 만.
            //    [JA] 例外の文言は残さない — SQL の詳細にニックネーム・画像 URL の値が載る。種類と id だけ。
            log.error("user-created 저장 실패: id={} ({})", createdUser.getUserId(), err.getClass().getSimpleName());
        }


    }

    /**
     * 원본의 프로필 변경을 복제본에 반영한다({@code user-updated}, docs/48 §6-⑤).
     *
     * <p>복제본이 없으면 건너뛴다 — 만들 수도 없다(이 이벤트에는 이메일이 없고, 채팅 신원은 이메일로 찾는다).
     * 없는 이유는 대개 가입 이벤트가 DLT 에 있는 것이고, 그것을 재처리하면 그때의 최신 값으로 생긴다.
     */
    @Transactional
    void userUpdated(long userId, String nickname, String image, long updatedAt) {
        chatMemberRepository.findForUpdate(userId).ifPresentOrElse(member -> {
            if (member.applyProfile(nickname, image, updatedAt)) {
                log.info("user-updated 반영: userId={}", userId);
            } else {
                log.info("user-updated 무시 — 이미 더 새 값이거나 탈퇴했다: userId={}", userId);
            }
        }, () -> log.warn("user-updated: 채팅 회원 복제본이 없다 — 건너뛴다. userId={}", userId));
    }

    /**
     * 원본이 탈퇴를 최종 처리했다 — 복제본의 식별정보를 지운다({@code user-deleted}). 행은 남긴다(채팅 기록이 가리킨다).
     */
    @Transactional
    void userDeleted(long userId) {
        chatMemberRepository.findForUpdate(userId).ifPresentOrElse(member -> {
            member.anonymize();
            log.info("user-deleted 반영(익명화): userId={}", userId);
        }, () -> log.info("user-deleted: 복제본이 없다 — 회원 행은 지울 것이 없다. userId={}", userId));
        scrubMessages(userId);
    }

    /**
     * 대화 기록(몽고)에 남은 탈퇴 회원의 흔적을 지운다 — 메시지마다 발신 시점의 <b>닉네임 사본</b>이 있다. 내용은
     * 남긴다 — 상대방의 대화이기도 하다. 입장·퇴장 안내는 문구 자체가 닉네임이라 함께 바꾼다. 여러 번 불려도 결과가
     * 같다. 옛 문서(v1)의 발신자 이메일은 지난 릴리스의 기동 백필이 senderId 로 바꿨다(docs/48 §6-⑥).
     */
    void scrubMessages(long userId) {
        Criteria who = Criteria.where("senderId").is(userId);
        String name = ChatMember.WITHDRAWN_NICKNAME;

        mongoTemplate.updateMulti(query(new Criteria().andOperator(who, Criteria.where("type").is("TEXT_ENTER"))),
                new Update().set("content", com.project.chat_service.service.ChatNotices.entered(name)), ChatMessage.class);
        mongoTemplate.updateMulti(query(new Criteria().andOperator(who, Criteria.where("type").is("TEXT_LEAVE"))),
                new Update().set("content", com.project.chat_service.service.ChatNotices.left(name)), ChatMessage.class);
        long n = mongoTemplate.updateMulti(query(who),
                new Update().set("nickname", name), ChatMessage.class)
                .getModifiedCount();
        log.info("user-deleted: 대화 기록의 발신자 표시를 지웠다. userId={} messages={}", userId, n);
    }
}
