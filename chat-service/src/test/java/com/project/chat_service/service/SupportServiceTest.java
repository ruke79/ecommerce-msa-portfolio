package com.project.chat_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.chat_service.common.exception.handler.CustomException;
import com.project.chat_service.common.exception.handler.ErrorCode;
import com.project.chat_service.constants.SupportThreadStatus;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.dto.SupportThreadDto;
import com.project.chat_service.message.producer.MessageProducer;
import com.project.chat_service.model.ChatMember;
import com.project.chat_service.model.SupportThread;
import com.project.chat_service.repository.jpa.ChatMemberRepository;
import com.project.chat_service.repository.jpa.SupportThreadRepository;
import com.project.common.constants.chat.MessageType;

/**
 * 고객센터 채팅 (docs/58) — 권한, 맡기, 메시지, 종료.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupportService — 고객센터 채팅")
class SupportServiceTest {

    static final Long CUSTOMER = 1001L;
    static final Long OTHER_CUSTOMER = 1002L;
    static final Long AGENT = 2001L;
    static final Long OTHER_AGENT = 2002L;
    static final String SUPPORT = "ROLE_SUPPORT";

    @Mock SupportThreadRepository threads;
    @Mock ChatMemberRepository members;
    @Mock ChatService chatService;
    @Mock MessageProducer messageProducer;
    @Mock SequenceGeneratorService sequenceGenerator;
    @Mock SupportDeskNotifier deskNotifier;

    SupportService service;
    final Instant now = Instant.parse("2026-09-25T01:00:00Z");

    @BeforeEach
    void setUp() {
        service = new SupportService(threads, members, chatService, messageProducer, sequenceGenerator, deskNotifier);
        service.setClock(Clock.fixed(now, ZoneOffset.UTC));
        when(sequenceGenerator.generateSequence(anyString())).thenReturn("1");
        when(members.findAllById(any())).thenReturn(List.of());
        when(chatService.ensureMember(any())).thenAnswer(i -> ChatMember.builder().id(i.getArgument(0)).nickname("민지").build());
    }

    private SupportThread thread(long id, SupportThreadStatus status, Long agentId) {
        SupportThread t = SupportThread.open(CUSTOMER, now);
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "status", status);
        ReflectionTestUtils.setField(t, "agentId", agentId);
        when(threads.findById(id)).thenReturn(Optional.of(t));
        return t;
    }

    private static MessageDto message(String roomId, Long sender, String content) {
        MessageDto m = MessageDto.builder().roomId(roomId).content(content).type(MessageType.TEXT_ENTER)
                .imageUrl("https://evil.example/x.png").build();
        m.setSenderId(sender);
        return m;
    }

    private static ErrorCode code(Throwable e) {
        return ((CustomException) e).getErrorCode();
    }

    // ── 권한 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔒 남의 상담은 404 — 있는지조차 알려 주지 않는다")
    void othersThreadIsNotFound() {
        thread(5, SupportThreadStatus.ACTIVE, AGENT);

        assertThatThrownBy(() -> service.get(5L, OTHER_CUSTOMER, "ROLE_USER"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_THREAD_NOT_FOUND));
        assertThatThrownBy(() -> service.messages(5L, OTHER_CUSTOMER, null, "-1"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_THREAD_NOT_FOUND));
        assertThat(service.canSubscribe("support-5", OTHER_CUSTOMER, null)).isFalse();
    }

    @Test
    @DisplayName("고객 본인과 상담원·관리자는 본다")
    void ownerAndAgentsCanRead() {
        thread(5, SupportThreadStatus.ACTIVE, AGENT);

        assertThat(service.canSubscribe("support-5", CUSTOMER, "ROLE_USER")).isTrue();
        assertThat(service.canSubscribe("support-5", OTHER_AGENT, SUPPORT)).isTrue();
        assertThat(service.canSubscribe("support-5", OTHER_AGENT, "ROLE_ADMIN")).isTrue();
    }

    @Test
    @DisplayName("번호가 올바르지 않은 상담 방은 없는 것으로 다룬다")
    void malformedRoomRejected() {
        assertThat(service.canSubscribe("support-abc", CUSTOMER, SUPPORT)).isFalse();
    }

    @Test
    @DisplayName("🔒 데스크는 상담원·관리자만")
    void deskAgentOnly() {
        assertThatThrownBy(() -> service.desk(SupportService.DeskView.WAITING, CUSTOMER, "ROLE_USER"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_AGENT_ONLY));
        assertThatThrownBy(() -> service.claim(5L, CUSTOMER, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_AGENT_ONLY));
    }

    // ── 문의 열기 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("열린 상담이 있으면 그것을 돌려준다 — 새로 열지 않는다")
    void reuseOpenThread() {
        SupportThread open = thread(5, SupportThreadStatus.WAITING, null);
        when(threads.findFirstByCustomerIdAndStatusNot(CUSTOMER, SupportThreadStatus.CLOSED)).thenReturn(Optional.of(open));

        SupportThreadDto dto = service.openOrGet(CUSTOMER);

        assertThat(dto.getRoomId()).isEqualTo("support-5");
        verify(threads, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("🔴 두 탭이 동시에 열어 유니크 인덱스에 걸리면 먼저 열린 것을 쓴다")
    void raceFallsBackToExisting() {
        SupportThread winner = thread(6, SupportThreadStatus.WAITING, null);
        when(threads.findFirstByCustomerIdAndStatusNot(CUSTOMER, SupportThreadStatus.CLOSED))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(threads.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("ux_support_thread_open_customer"));

        assertThat(service.openOrGet(CUSTOMER).getThreadId()).isEqualTo(6L);
    }

    // ── 맡기 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("대기 중인 상담을 맡으면 연결 안내가 팀 명의로 나간다")
    void claimAnnounces() {
        thread(5, SupportThreadStatus.WAITING, null);
        when(threads.claim(eq(5L), eq(AGENT), any())).thenAnswer(i -> {
            thread(5, SupportThreadStatus.ACTIVE, AGENT);
            return 1;
        });

        service.claim(5L, AGENT, SUPPORT);

        ArgumentCaptor<MessageDto> sent = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageProducer).send(eq("chat"), sent.capture());
        assertThat(sent.getValue().getType()).isEqualTo(MessageType.TEXT_ENTER);
        assertThat(sent.getValue().getRoomId()).isEqualTo("support-5");
        assertThat(sent.getValue().getContent()).contains("민지");
        assertThat(sent.getValue().getNickname()).isEqualTo("고객지원팀");
        assertThat(sent.getValue().getSenderId()).isNull();
    }

    @Test
    @DisplayName("🔴 두 상담원이 동시에 맡으면 늦은 쪽은 409 — 조건부 갱신이 0행")
    void claimRace() {
        thread(5, SupportThreadStatus.ACTIVE, AGENT);
        when(threads.claim(eq(5L), eq(OTHER_AGENT), any())).thenReturn(0);

        assertThatThrownBy(() -> service.claim(5L, OTHER_AGENT, SUPPORT))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_ALREADY_CLAIMED));
        verify(messageProducer, never()).send(any(), any());
    }

    @Test
    @DisplayName("이미 내가 맡은 상담은 다시 눌러도 성공 — 안내는 다시 나가지 않는다")
    void claimIdempotent() {
        thread(5, SupportThreadStatus.ACTIVE, AGENT);
        when(threads.claim(eq(5L), eq(AGENT), any())).thenReturn(0);

        assertThat(service.claim(5L, AGENT, SUPPORT).getAgentId()).isEqualTo(AGENT);
        verify(messageProducer, never()).send(any(), any());
    }

    // ── 메시지 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 고객 메시지 — 유형은 TEXT_TALK 으로 고정, 이미지 URL 은 버린다, '답변 필요' 가 켜진다")
    void customerMessage() {
        SupportThread t = thread(5, SupportThreadStatus.WAITING, null);
        MessageDto m = message("support-5", CUSTOMER, "배송이 안 와요");

        service.acceptMessage(m, "ROLE_USER");

        assertThat(m.getType()).isEqualTo(MessageType.TEXT_TALK);
        assertThat(m.getImageUrl()).isNull();
        assertThat(t.isAwaitingReply()).isTrue();
        assertThat(t.getLastMessagePreview()).isEqualTo("배송이 안 와요");
        assertThat(t.getLastMessageAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("맡은 상담원이 답하면 '답변 필요' 가 꺼진다")
    void agentReply() {
        SupportThread t = thread(5, SupportThreadStatus.ACTIVE, AGENT);
        ReflectionTestUtils.setField(t, "awaitingReply", true);

        service.acceptMessage(message("support-5", AGENT, "확인해 보겠습니다"), SUPPORT);

        assertThat(t.isAwaitingReply()).isFalse();
    }

    @Test
    @DisplayName("🔒 맡지 않은 상담원은 말할 수 없다 — 대기 중이거나 남이 맡은 상담")
    void unassignedAgentCannotSpeak() {
        thread(5, SupportThreadStatus.WAITING, null);
        thread(6, SupportThreadStatus.ACTIVE, AGENT);

        assertThatThrownBy(() -> service.acceptMessage(message("support-5", OTHER_AGENT, "안녕하세요"), SUPPORT))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_NOT_ASSIGNED));
        assertThatThrownBy(() -> service.acceptMessage(message("support-6", OTHER_AGENT, "안녕하세요"), SUPPORT))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_NOT_ASSIGNED));
    }

    @Test
    @DisplayName("🔒 남의 상담 방으로는 보낼 수 없다")
    void strangerCannotSend() {
        thread(5, SupportThreadStatus.ACTIVE, AGENT);

        assertThatThrownBy(() -> service.acceptMessage(message("support-5", OTHER_CUSTOMER, "hi"), "ROLE_USER"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_THREAD_NOT_FOUND));
    }

    @Test
    @DisplayName("종료된 상담·빈 메시지는 거절")
    void closedOrEmptyRejected() {
        thread(5, SupportThreadStatus.CLOSED, AGENT);
        thread(6, SupportThreadStatus.WAITING, null);

        assertThatThrownBy(() -> service.acceptMessage(message("support-5", CUSTOMER, "또 문의"), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_THREAD_CLOSED));
        assertThatThrownBy(() -> service.acceptMessage(message("support-6", CUSTOMER, "   "), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_EMPTY_MESSAGE));
    }

    @Test
    @DisplayName("목록 요약은 200자에서 자른다(컬럼 길이)")
    void previewTruncated() {
        SupportThread t = thread(5, SupportThreadStatus.WAITING, null);

        service.acceptMessage(message("support-5", CUSTOMER, "가".repeat(500)), null);

        assertThat(t.getLastMessagePreview()).hasSize(200).endsWith("…");
    }

    // ── 종료 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("고객이 닫으면 종료 안내가 한 번 — 두 번 눌러도 한 번")
    void closeOnce() {
        SupportThread t = thread(5, SupportThreadStatus.ACTIVE, AGENT);

        service.close(5L, CUSTOMER, null);
        service.close(5L, CUSTOMER, null);

        assertThat(t.getStatus()).isEqualTo(SupportThreadStatus.CLOSED);
        assertThat(t.getClosedAt()).isEqualTo(now);
        ArgumentCaptor<MessageDto> sent = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageProducer, times(1)).send(eq("chat"), sent.capture());
        assertThat(sent.getValue().getType()).isEqualTo(MessageType.TEXT_LEAVE);
    }

    @Test
    @DisplayName("🔒 남은 닫을 수 없다")
    void strangerCannotClose() {
        thread(5, SupportThreadStatus.ACTIVE, AGENT);

        assertThatThrownBy(() -> service.close(5L, OTHER_CUSTOMER, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.SUPPORT_THREAD_NOT_FOUND));
    }

    @Test
    @DisplayName("고객 화면에는 자기 이름을 싣지 않고, 데스크에는 싣는다")
    void customerNameOnlyForDesk() {
        SupportThread open = thread(5, SupportThreadStatus.WAITING, null);
        when(threads.findFirstByCustomerIdAndStatusNot(CUSTOMER, SupportThreadStatus.CLOSED)).thenReturn(Optional.of(open));
        when(threads.findDesk(eq(SupportThreadStatus.WAITING), any())).thenReturn(List.of(open));
        when(members.findAllById(any())).thenReturn(List.of(ChatMember.builder().id(CUSTOMER).nickname("홍길동").build()));

        assertThat(service.current(CUSTOMER).orElseThrow().getCustomerName()).isNull();
        assertThat(service.desk(SupportService.DeskView.WAITING, AGENT, SUPPORT).get(0).getCustomerName()).isEqualTo("홍길동");
    }

    // ── 데스크 실시간 알림(§9) ─────────────────────────────────────────

    @Test
    @DisplayName("🔴 새 문의가 열리면 데스크에 NEW_THREAD — 이미 열린 상담을 돌려줄 때는 알리지 않는다")
    void newThreadNotifies() {
        when(threads.findFirstByCustomerIdAndStatusNot(CUSTOMER, SupportThreadStatus.CLOSED)).thenReturn(Optional.empty());
        when(threads.saveAndFlush(any())).thenAnswer(i -> {
            SupportThread t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 7L);
            return t;
        });

        service.openOrGet(CUSTOMER);

        verify(deskNotifier).notify(7L, SupportDeskNotifier.Kind.NEW_THREAD, CUSTOMER);

        SupportThread open = thread(8, SupportThreadStatus.WAITING, null);
        when(threads.findFirstByCustomerIdAndStatusNot(CUSTOMER, SupportThreadStatus.CLOSED)).thenReturn(Optional.of(open));
        service.openOrGet(CUSTOMER);
        verify(deskNotifier, never()).notify(eq(8L), any(), any());
    }

    @Test
    @DisplayName("맡으면 CLAIMED, 닫으면 CLOSED — 실패한 맡기는 알리지 않는다")
    void claimAndCloseNotify() {
        thread(5, SupportThreadStatus.WAITING, null);
        when(threads.claim(eq(5L), eq(AGENT), any())).thenAnswer(i -> {
            thread(5, SupportThreadStatus.ACTIVE, AGENT);
            return 1;
        });
        service.claim(5L, AGENT, SUPPORT);
        verify(deskNotifier).notify(5L, SupportDeskNotifier.Kind.CLAIMED, AGENT);

        service.close(5L, CUSTOMER, null);
        verify(deskNotifier).notify(5L, SupportDeskNotifier.Kind.CLOSED, CUSTOMER);

        thread(6, SupportThreadStatus.ACTIVE, AGENT);
        when(threads.claim(eq(6L), eq(OTHER_AGENT), any())).thenReturn(0);
        assertThatThrownBy(() -> service.claim(6L, OTHER_AGENT, SUPPORT)).isInstanceOf(CustomException.class);
        verify(deskNotifier, never()).notify(eq(6L), eq(SupportDeskNotifier.Kind.CLAIMED), any());
    }
}
