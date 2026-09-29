package com.project.chat_service.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.chat_service.common.exception.handler.ErrorCode;
import com.project.chat_service.common.util.TokenHandler;
import com.project.chat_service.constants.SupportTopic;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.dto.SupportThreadDto;
import com.project.chat_service.service.SupportService;
import com.project.chat_service.service.SupportService.DeskView;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 고객센터 채팅 REST (docs/58). 메시지 보내기는 STOMP({@code /pub/chat/message}, roomId = {@code support-<id>})다.
 *
 * <p>신원·역할은 검증된 토큰에서 읽는다 — 친구 채팅 API({@code ChatApiController})와 같은 경로다. 요청 본문에는
 * 신원이 없다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/support")
public class SupportApiController {

    private final SupportService supportService;
    private final TokenHandler tokenHandler;

    // ── 고객 ────────────────────────────────────────────────────────────

    /** 열린 상담. 없으면 204 — 화면은 "문의하기" 를 보인다. */
    @GetMapping("/threads/mine")
    public ResponseEntity<SupportThreadDto> current(HttpServletRequest request) {
        return supportService.current(userId(request))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 문의하기 — 열린 상담이 있으면 그것을, 없으면 새로 연다. 본문은 선택이다({@code {"topic":"ORDER"}}) — 옛 화면은
     * 본문 없이 부르므로 없으면 "기타" 로 연다. 본문에 신원은 없다.
     */
    @PostMapping("/threads/mine")
    public SupportThreadDto open(@RequestBody(required = false) OpenRequest body, HttpServletRequest request) {
        return supportService.openOrGet(userId(request), SupportTopic.parse(body == null ? null : body.topic()));
    }

    /** 지난 상담. */
    @GetMapping("/threads/mine/history")
    public List<SupportThreadDto> history(HttpServletRequest request) {
        return supportService.history(userId(request));
    }

    // ── 공통(고객 본인·상담원) ───────────────────────────────────────────

    @GetMapping("/threads/{threadId}")
    public SupportThreadDto get(@PathVariable("threadId") Long threadId, HttpServletRequest request) {
        return supportService.get(threadId, userId(request), role(request));
    }

    @GetMapping("/threads/{threadId}/messages")
    public List<MessageDto> messages(@PathVariable("threadId") Long threadId,
            @RequestParam(name = "cursor", defaultValue = "-1") String cursor, HttpServletRequest request) {
        return supportService.messages(threadId, userId(request), role(request), cursor);
    }

    @PostMapping("/threads/{threadId}/close")
    public SupportThreadDto close(@PathVariable("threadId") Long threadId, HttpServletRequest request) {
        return supportService.close(threadId, userId(request), role(request));
    }

    /** 만족도 — 고객 본인이 종료된 상담에 한 번(docs/58 §11). */
    @PostMapping("/threads/{threadId}/rating")
    public SupportThreadDto rate(@PathVariable("threadId") Long threadId, @RequestBody RatingRequest body,
            HttpServletRequest request) {
        return supportService.rate(threadId, userId(request), role(request), body.score(), body.comment());
    }

    // ── 상담 데스크(상담원·관리자) ───────────────────────────────────────

    /** 데스크 목록. {@code topic} 을 주면 그 유형만(비우면 전부). */
    @GetMapping("/desk/threads")
    public List<SupportThreadDto> desk(@RequestParam(name = "view", defaultValue = "WAITING") DeskView view,
            @RequestParam(name = "topic", required = false) String topic, HttpServletRequest request) {
        SupportTopic filter = topic == null || topic.isBlank() ? null : SupportTopic.parse(topic);
        return supportService.desk(view, filter, userId(request), role(request));
    }

    @PostMapping("/desk/threads/{threadId}/claim")
    public SupportThreadDto claim(@PathVariable("threadId") Long threadId, HttpServletRequest request) {
        return supportService.claim(threadId, userId(request), role(request));
    }

    /** 🔒 상담원 내부 메모 — 고객 응답에는 실리지 않는다. */
    @PutMapping("/desk/threads/{threadId}/note")
    public SupportThreadDto note(@PathVariable("threadId") Long threadId, @RequestBody NoteRequest body,
            HttpServletRequest request) {
        return supportService.updateNote(threadId, userId(request), role(request), body.note());
    }

    /** 맡은 상담을 반납 — 다시 대기 목록으로. */
    @PostMapping("/desk/threads/{threadId}/release")
    public SupportThreadDto release(@PathVariable("threadId") Long threadId, HttpServletRequest request) {
        return supportService.release(threadId, userId(request), role(request));
    }

    /** 문의하기 본문(선택) — 유형만. */
    public record OpenRequest(String topic) {
    }

    /** 만족도 본문 — 1~5 점과 선택 의견(500자). */
    public record RatingRequest(Integer score, String comment) {
    }

    /** 내부 메모 본문(2000자). 비우면 지운다. */
    public record NoteRequest(String note) {
    }

    private Long userId(HttpServletRequest request) {
        Long id = tokenHandler.resolveVerifiedUserId(request.getHeader("Authorization"));
        if (id == null) {
            throw ErrorCode.unauthorizedTokenException();
        }
        return id;
    }

    private String role(HttpServletRequest request) {
        return tokenHandler.resolveVerifiedRole(request.getHeader("Authorization"));
    }
}
