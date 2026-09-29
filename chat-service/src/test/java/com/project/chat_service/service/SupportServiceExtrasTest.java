package com.project.chat_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.chat_service.common.exception.handler.CustomException;
import com.project.chat_service.common.exception.handler.ErrorCode;
import com.project.chat_service.constants.SupportThreadStatus;
import com.project.chat_service.constants.SupportTopic;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.dto.SupportThreadDto;
import com.project.chat_service.message.producer.MessageProducer;
import com.project.chat_service.model.ChatMember;
import com.project.chat_service.model.SupportThread;
import com.project.chat_service.repository.jpa.ChatMemberRepository;
import com.project.chat_service.repository.jpa.SupportThreadRepository;

/**
 * 상담 개편(docs/58 §11) — 문의 유형 · 대기 순번 · 만족도 · 내부 메모 · 반납.
 *
 * <p>권한 규칙은 기존 상담과 같다: 남의 것은 404, 상담원 기능은 상담원만. 새로 생긴 경계 두 가지를 특히 본다 —
 * 🔒 내부 메모가 <b>고객 응답에 절대 실리지 않는지</b>, 그리고 평가가 본인 · 종료 · 한 번인지.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupportServiceExtrasTest {

    static final Long CUSTOMER = 1001L;
    static final Long OTHER_CUSTOMER = 1002L;
    static final Long AGENT = 2001L;
    static final Long OTHER_AGENT = 2002L;
    static final String SUPPORT = "ROLE_SUPPORT";
    static final String ADMIN = "ROLE_ADMIN";

    @Mock SupportThreadRepository threads;
    @Mock ChatMemberRepository members;
    @Mock ChatService chatService;
    @Mock MessageProducer messageProducer;
    @Mock SequenceGeneratorService sequenceGenerator;
    @Mock SupportDeskNotifier deskNotifier;

    SupportService service;
    final Instant now = Instant.parse("2026-09-27T01:00:00Z");

    @BeforeEach
    void setUp() {
        service = new SupportService(threads, members, chatService, messageProducer, sequenceGenerator, deskNotifier);
        service.setClock(Clock.fixed(now, ZoneOffset.UTC));
        when(sequenceGenerator.generateSequence(anyString())).thenReturn("1");
        when(members.findAllById(any())).thenReturn(List.of());
        when(chatService.ensureMember(any())).thenAnswer(i -> ChatMember.builder().id(i.getArgument(0)).nickname("민지").build());
    }

    private SupportThread thread(long id, SupportThreadStatus status, Long agentId) {
        SupportThread t = SupportThread.open(CUSTOMER, SupportTopic.DELIVERY, now.minusSeconds(600));
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "status", status);
        ReflectionTestUtils.setField(t, "agentId", agentId);
        when(threads.findById(id)).thenReturn(Optional.of(t));
        return t;
    }

    private static ErrorCode code(Throwable e) {
        return ((CustomException) e).getErrorCode();
    }

    // ── 문의 유형 · 대기 순번 ─────────────────────────────────────────

    @Test
    @DisplayName("문의 유형을 골라 연다 — 모르는 값은 OTHER, 이미 열린 상담이 있으면 유형을 바꾸지 않는다")
    void opensWithTopic() {
        when(threads.findFirstByCustomerIdAndStatusNot(CUSTOMER, SupportThreadStatus.CLOSED)).thenReturn(Optional.empty());
        when(threads.saveAndFlush(any())).thenAnswer(i -> {
            SupportThread t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 9L);
            return t;
        });

        SupportThreadDto dto = service.openOrGet(CUSTOMER, SupportTopic.PAYMENT);
        assertThat(dto.getTopic()).isEqualTo(SupportTopic.PAYMENT);

        assertThat(SupportTopic.parse("delivery")).isEqualTo(SupportTopic.DELIVERY);
        assertThat(SupportTopic.parse("<script>")).isEqualTo(SupportTopic.OTHER);
        assertThat(SupportTopic.parse(null)).isEqualTo(SupportTopic.OTHER);

        SupportThread existing = thread(3, SupportThreadStatus.WAITING, null);
        when(threads.findFirstByCustomerIdAndStatusNot(CUSTOMER, SupportThreadStatus.CLOSED)).thenReturn(Optional.of(existing));
        assertThat(service.openOrGet(CUSTOMER, SupportTopic.PAYMENT).getTopic())
                .as("이미 열린 상담(DELIVERY)을 그대로 — 두 탭이 유형을 덮어쓰지 않는다")
                .isEqualTo(SupportTopic.DELIVERY);
    }

    @Test
    @DisplayName("대기 순번 — 대기 중인 고객 화면에만, 나보다 먼저 연 대기 문의 수 + 1")
    void queuePositionForWaitingCustomer() {
        SupportThread t = thread(5, SupportThreadStatus.WAITING, null);
        when(threads.countWaitingBefore(t.getCreatedAt())).thenReturn(2L);

        assertThat(service.get(5L, CUSTOMER, "ROLE_USER").getQueuePosition()).isEqualTo(3L);
        assertThat(service.get(5L, AGENT, SUPPORT).getQueuePosition()).as("데스크에는 싣지 않는다").isNull();

        ReflectionTestUtils.setField(t, "status", SupportThreadStatus.ACTIVE);
        ReflectionTestUtils.setField(t, "agentId", AGENT);
        assertThat(service.get(5L, CUSTOMER, "ROLE_USER").getQueuePosition()).as("상담 중이면 순번이 없다").isNull();
    }

    @Test
    @DisplayName("데스크 유형 필터 — WAITING·ACTIVE·CLOSED 는 유형으로 거르고, '내 담당' 은 유형과 상관없이 모두")
    void deskFiltersByTopic() {
        service.desk(SupportService.DeskView.WAITING, SupportTopic.ORDER, AGENT, SUPPORT);
        verify(threads).findDeskByTopic(eq(SupportThreadStatus.WAITING), eq(SupportTopic.ORDER), any());

        service.desk(SupportService.DeskView.MINE, SupportTopic.ORDER, AGENT, SUPPORT);
        verify(threads).findMine(eq(AGENT), eq(SupportThreadStatus.ACTIVE), any());
        verify(threads, never()).findDeskByTopic(eq(SupportThreadStatus.ACTIVE), any(), any());

        assertThatThrownBy(() -> service.desk(SupportService.DeskView.WAITING, SupportTopic.ORDER, CUSTOMER, "ROLE_USER"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_AGENT_ONLY));
    }

    // ── 만족도 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("만족도 — 본인이, 종료된 상담에, 한 번. 점수·의견을 저장한다")
    void customerRatesClosedThreadOnce() {
        thread(7, SupportThreadStatus.CLOSED, AGENT);

        SupportThreadDto dto = service.rate(7L, CUSTOMER, "ROLE_USER", 5, "  빨리 해결됐어요  ");
        assertThat(dto.getRating()).isEqualTo(5);
        assertThat(dto.getRatingComment()).isEqualTo("빨리 해결됐어요");
        assertThat(dto.getRatedAt()).isEqualTo(now);

        assertThatThrownBy(() -> service.rate(7L, CUSTOMER, "ROLE_USER", 1, null))
                .as("두 번은 못 한다")
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_ALREADY_RATED));
    }

    @Test
    @DisplayName("만족도 — 열린 상담 · 범위 밖 점수 · 긴 의견은 거절, 상담원 · 남은 404")
    void ratingGuards() {
        thread(7, SupportThreadStatus.ACTIVE, AGENT);
        assertThatThrownBy(() -> service.rate(7L, CUSTOMER, "ROLE_USER", 5, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_NOT_CLOSED));

        thread(8, SupportThreadStatus.CLOSED, AGENT);
        for (Integer bad : new Integer[] {null, 0, 6, -1}) {
            assertThatThrownBy(() -> service.rate(8L, CUSTOMER, "ROLE_USER", bad, null))
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_INVALID_RATING));
        }
        assertThatThrownBy(() -> service.rate(8L, CUSTOMER, "ROLE_USER", 4, "가".repeat(501)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_INVALID_RATING));

        assertThatThrownBy(() -> service.rate(8L, AGENT, SUPPORT, 5, null))
                .as("상담원은 평가하지 않는다 — 404")
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_THREAD_NOT_FOUND));
        assertThatThrownBy(() -> service.rate(8L, OTHER_CUSTOMER, "ROLE_USER", 5, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_THREAD_NOT_FOUND));
    }

    // ── 내부 메모 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("🔒 내부 메모는 데스크 응답에만 — 고객 응답(get · current · history · rate)과 그 JSON 에는 없다")
    void internalNoteNeverReachesCustomer() throws Exception {
        SupportThread t = thread(5, SupportThreadStatus.ACTIVE, AGENT);

        SupportThreadDto desk = service.updateNote(5L, OTHER_AGENT, SUPPORT, "  환불 규정 안내함. 재문의 가능성  ");
        assertThat(desk.getInternalNote()).isEqualTo("환불 규정 안내함. 재문의 가능성");
        assertThat(desk.getNoteUpdatedAt()).isEqualTo(now);

        SupportThreadDto forCustomer = service.get(5L, CUSTOMER, "ROLE_USER");
        assertThat(forCustomer.getInternalNote()).isNull();
        assertThat(forCustomer.getNoteUpdatedAt()).isNull();

        when(threads.findFirstByCustomerIdAndStatusNot(CUSTOMER, SupportThreadStatus.CLOSED)).thenReturn(Optional.of(t));
        assertThat(service.current(CUSTOMER).orElseThrow().getInternalNote()).isNull();

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(forCustomer);
        assertThat(json).doesNotContain("internalNote").doesNotContain("환불 규정");

        ReflectionTestUtils.setField(t, "status", SupportThreadStatus.CLOSED);
        when(threads.findByCustomerIdAndStatusOrderByClosedAtDesc(eq(CUSTOMER), eq(SupportThreadStatus.CLOSED), any()))
                .thenReturn(List.of(t));
        assertThat(service.history(CUSTOMER)).allSatisfy(h -> assertThat(h.getInternalNote()).isNull());
        assertThat(service.rate(5L, CUSTOMER, "ROLE_USER", 4, null).getInternalNote()).isNull();
    }

    @Test
    @DisplayName("내부 메모 — 상담원만, 2000자까지, 비우면 지운다")
    void noteGuards() {
        thread(5, SupportThreadStatus.ACTIVE, AGENT);

        assertThatThrownBy(() -> service.updateNote(5L, CUSTOMER, "ROLE_USER", "메모"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_AGENT_ONLY));
        assertThatThrownBy(() -> service.updateNote(5L, AGENT, SUPPORT, "a".repeat(2001)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_NOTE_TOO_LONG));
        assertThat(service.updateNote(5L, AGENT, SUPPORT, "   ").getInternalNote()).isNull();
    }

    // ── 반납 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("반납 — 맡은 상담원이 하면 대기로 돌아가고, 고객에게 '상담원이 바뀝니다' 안내 · 데스크에 RELEASED")
    void assignedAgentReleases() {
        SupportThread t = thread(5, SupportThreadStatus.ACTIVE, AGENT);
        ReflectionTestUtils.setField(t, "awaitingReply", true);

        SupportThreadDto dto = service.release(5L, AGENT, SUPPORT);

        assertThat(dto.getStatus()).isEqualTo(SupportThreadStatus.WAITING);
        assertThat(dto.getAgentId()).isNull();
        assertThat(t.getAssignedAt()).isNull();
        assertThat(t.isAwaitingReply()).as("다음 상담원이 답해야 하는지 알게 둔다").isTrue();

        ArgumentCaptor<MessageDto> sent = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageProducer).send(anyString(), sent.capture());
        assertThat(sent.getValue().getContent()).isEqualTo(ChatNotices.RELEASED);
        assertThat(sent.getValue().getSenderId()).as("팀 명의 안내 — 발신자 없음").isNull();
        verify(deskNotifier).notify(5L, SupportDeskNotifier.Kind.RELEASED, AGENT);
    }

    @Test
    @DisplayName("반납 — 남이 맡은 것 · 대기 중 · 종료는 거절, 관리자는 남의 것도 풀 수 있다")
    void releaseGuards() {
        thread(5, SupportThreadStatus.ACTIVE, AGENT);
        assertThatThrownBy(() -> service.release(5L, OTHER_AGENT, SUPPORT))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_NOT_YOURS));
        assertThatThrownBy(() -> service.release(5L, CUSTOMER, "ROLE_USER"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_AGENT_ONLY));

        thread(6, SupportThreadStatus.WAITING, null);
        assertThatThrownBy(() -> service.release(6L, AGENT, SUPPORT))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_NOT_YOURS));

        thread(7, SupportThreadStatus.CLOSED, AGENT);
        assertThatThrownBy(() -> service.release(7L, AGENT, SUPPORT))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_THREAD_CLOSED));

        thread(8, SupportThreadStatus.ACTIVE, AGENT);
        assertThat(service.release(8L, OTHER_AGENT, ADMIN).getStatus()).isEqualTo(SupportThreadStatus.WAITING);
    }

    @Test
    @DisplayName("반납한 상담은 다시 맡을 수 있다 — 대기로 돌아간 상태가 V7 CHECK(대기면 담당자 없음)를 지킨다")
    void releasedThreadSatisfiesWaitingInvariant() {
        SupportThread t = thread(5, SupportThreadStatus.ACTIVE, AGENT);
        t.release();
        assertThat(t.getStatus()).isEqualTo(SupportThreadStatus.WAITING);
        assertThat(t.getAgentId()).isNull();
    }

    @Test
    @DisplayName("데스크의 상담 하나 — 이 고객의 지난 상담 수를 싣는다(목록에는 싣지 않는다)")
    void deskDetailHasPastThreadCount() {
        thread(5, SupportThreadStatus.ACTIVE, AGENT);
        when(threads.countByCustomerIdAndStatus(CUSTOMER, SupportThreadStatus.CLOSED)).thenReturn(4L);

        assertThat(service.get(5L, AGENT, SUPPORT).getCustomerPastThreads()).isEqualTo(4L);
        assertThat(service.get(5L, CUSTOMER, "ROLE_USER").getCustomerPastThreads()).isNull();
    }
}
