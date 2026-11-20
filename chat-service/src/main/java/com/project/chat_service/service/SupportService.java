package com.project.chat_service.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.project.chat_service.common.exception.handler.CustomException;
import com.project.chat_service.common.exception.handler.ErrorCode;
import com.project.chat_service.common.util.KafkaUtil;
import com.project.chat_service.constants.SupportRoles;
import com.project.chat_service.constants.SupportThreadStatus;
import com.project.chat_service.constants.SupportTopic;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.dto.SupportThreadDto;
import com.project.chat_service.message.producer.MessageProducer;
import com.project.chat_service.model.ChatMember;
import com.project.chat_service.model.SupportThread;
import com.project.chat_service.repository.jpa.ChatMemberRepository;
import com.project.chat_service.repository.jpa.SupportThreadRepository;
import com.project.common.constants.chat.MessageType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 고객센터 채팅 — 고객 ↔ 고객지원팀 (docs/58).
 *
 * <h3>권한</h3>
 *
 * <ul>
 *   <li>고객은 <b>자기</b> 스레드만. 남의 것은 403 이 아니라 404 — 번호를 바꿔 가며 있는지 떠보지 못하게.</li>
 *   <li>상담원({@code ROLE_SUPPORT})·관리자는 모든 스레드를 <b>볼</b> 수 있다. <b>말하는</b> 것은 맡은 상담원만
 *       — 대기 중인 문의에 누가 먼저 답했는지 모호해지지 않게, 맡기(claim)를 먼저 한다.</li>
 *   <li>신원·역할은 검증된 토큰에서만 온다(REST 는 Authorization, STOMP 는 CONNECT 때 세션에 둔 값).</li>
 * </ul>
 *
 * <h3>메시지</h3>
 *
 * <p>친구 채팅과 같은 파이프라인이다 — Kafka {@code chat} → 저장(MongoDB) → {@code /sub/chat/room/support-<id>}
 * 브로드캐스트. 여기서는 보내기 전에 권한을 보고 스레드의 목록 요약을 갱신한다. 유형은 {@code TEXT_TALK} 으로
 * 고정한다 — 클라이언트가 {@code TEXT_ENTER} 를 보내 "상담원이 연결되었습니다" 같은 안내를 흉내 내지 못하게.
 *
 * <p>[JA] カスタマーセンターチャット — 顧客 ↔ カスタマーサポートチーム(docs/58)。
 * <p><b>権限</b>: 顧客は<b>自分の</b>スレッドだけ。他人のものは 403 ではなく 404 — 番号を変えながら存在を探れないように。相談員({@code ROLE_SUPPORT})・管理者はすべてのスレッドを<b>見る</b>ことができる。<b>発言する</b>のは担当の相談員だけ — 待機中の問い合わせに誰が先に答えたか曖昧にならないよう、担当(claim)を先に行う。身元・ロールは検証済みトークンからだけ来る(REST は Authorization、STOMP は CONNECT 時にセッションに置いた値)。
 * <p><b>メッセージ</b>: 友達チャットと同じパイプラインである — Kafka {@code chat} → 保存(MongoDB) → {@code /sub/chat/room/support-<id>} ブロードキャスト。ここでは送る前に権限を見てスレッドの一覧要約を更新する。種別は {@code TEXT_TALK} に固定する — クライアントが {@code TEXT_ENTER} を送って「相談員が接続されました」のような案内を真似できないように。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportService {

    /** 안내 메시지의 발신자 이름. */
    // [JA] 案内メッセージの送信者名。
    static final String TEAM_NAME = ChatNotices.SUPPORT_TEAM;
    private static final int LIST_LIMIT = 50;
    private static final int HISTORY_LIMIT = 10;

    private final SupportThreadRepository threads;
    private final ChatMemberRepository members;
    private final ChatService chatService;
    private final MessageProducer messageProducer;
    private final SequenceGeneratorService sequenceGenerator;
    private final SupportDeskNotifier deskNotifier;
    private Clock clock = Clock.systemUTC();

    /** 테스트가 시각을 고정한다. */
    // [JA] テストが時刻を固定する。
    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ── 고객 ────────────────────────────────────────────────────────────
    // [JA] ── 顧客 ──

    /**
     * 열린 상담을 돌려준다. 없으면 연다.
     *
     * <p>트랜잭션을 걸지 않는다 — 두 탭이 동시에 열면 부분 유니크 인덱스가 둘째를 막는데, 같은 트랜잭션이면 그
     * 예외로 트랜잭션이 롤백 전용이 되어 다시 읽을 수 없다. 저장은 저장소의 트랜잭션 하나로 끝난다.
     *
     * <p>[JA] 開いている相談を返す。なければ開く。
     * <p>トランザクションをかけない — 二つのタブが同時に開くと部分ユニークインデックスが 2 つ目を止めるが、同じトランザクションだとその例外でトランザクションがロールバック専用になり読み直せない。保存はリポジトリのトランザクション一つで終わる。
     */
    public SupportThreadDto openOrGet(Long customerId) {
        return openOrGet(customerId, SupportTopic.OTHER);
    }

    /**
     * 문의 유형을 골라 연다(docs/58 §11). 이미 열린 상담이 있으면 그것을 돌려주고 유형은 바꾸지 않는다 — 두 탭에서
     * 다른 유형을 골라도 상담은 하나다.
     *
     * <p>[JA] 問い合わせ種別を選んで開く(docs/58 §11)。すでに開いている相談があればそれを返し種別は変えない — 二つのタブで違う種別を選んでも相談は一つである。
     */
    public SupportThreadDto openOrGet(Long customerId, SupportTopic topic) {
        Optional<SupportThread> open = threads.findFirstByCustomerIdAndStatusNot(customerId, SupportThreadStatus.CLOSED);
        if (open.isPresent()) {
            return toDto(open.get(), false);
        }
        chatService.ensureMember(customerId);
        try {
            SupportThread created = threads.saveAndFlush(SupportThread.open(customerId, topic, Instant.now(clock)));
            log.info("상담 열림: threadId={} customerId={} topic={}", created.getId(), customerId, created.getTopic());
            deskNotifier.notify(created.getId(), SupportDeskNotifier.Kind.NEW_THREAD, customerId);
            return toDto(created, false);
        } catch (DataIntegrityViolationException raced) {
            // 다른 탭이 먼저 열었다 — 그것을 쓴다.
            // [JA] 別のタブが先に開いた — それを使う。
            return threads.findFirstByCustomerIdAndStatusNot(customerId, SupportThreadStatus.CLOSED)
                    .map(t -> toDto(t, false))
                    .orElseThrow(() -> raced);
        }
    }

    /** 열린 상담. 없으면 비어 있다 — 화면은 "문의하기" 버튼을 보인다. */
    // [JA] 開いている相談。なければ空 — 画面は「問い合わせる」ボタンを表示する。
    @Transactional(readOnly = true)
    public Optional<SupportThreadDto> current(Long customerId) {
        return threads.findFirstByCustomerIdAndStatusNot(customerId, SupportThreadStatus.CLOSED)
                .map(t -> toDto(t, false));
    }

    /** 지난 상담(종료) — 최근 것부터 {@value #HISTORY_LIMIT}개. */
    // [JA] 過去の相談(終了) — 新しいものから {@value #HISTORY_LIMIT} 件。
    @Transactional(readOnly = true)
    public List<SupportThreadDto> history(Long customerId) {
        return toDtos(threads.findByCustomerIdAndStatusOrderByClosedAtDesc(
                customerId, SupportThreadStatus.CLOSED, PageRequest.of(0, HISTORY_LIMIT)), false);
    }

    // ── 공통 ────────────────────────────────────────────────────────────
    // [JA] ── 共通 ──

    /** 스레드 하나 — 고객 본인이거나 상담원이어야 한다. */
    // [JA] スレッド一つ — 顧客本人か相談員でなければならない。
    @Transactional(readOnly = true)
    public SupportThreadDto get(Long threadId, Long userId, String role) {
        SupportThread t = requireAccess(threadId, userId, role);
        if (t.isCustomer(userId)) {
            return toDto(t, false);
        }
        // 데스크의 상담 하나 — 고객 정보 칸에 "지난 상담 N건" 을 보인다(목록에서는 N번 세지 않는다).
        // [JA] デスクの相談一つ — 顧客情報欄に「過去の相談 N 件」を表示する(一覧では N 回数えない)。
        long past = threads.countByCustomerIdAndStatus(t.getCustomerId(), SupportThreadStatus.CLOSED);
        return toDtoBuilder(t, true).customerPastThreads(past).build();
    }

    /**
     * 만족도를 남긴다 — <b>고객 본인</b>이, <b>종료된</b> 상담에, <b>한 번</b>(docs/58 §11). 상담원은 평가할 수 없다
     * (남의 상담처럼 404 — 평가 창구가 있다는 것도 알 필요 없다).
     *
     * <p>[JA] 満足度を残す — <b>顧客本人</b>が、<b>終了した</b>相談に、<b>一度</b>(docs/58 §11)。相談員は評価できない(他人の相談と同様に 404 — 評価窓口があることも知る必要はない)。
     */
    @Transactional
    public SupportThreadDto rate(Long threadId, Long userId, String role, Integer score, String comment) {
        SupportThread t = requireAccess(threadId, userId, role);
        if (!t.isCustomer(userId)) {
            throw new CustomException(ErrorCode.SUPPORT_THREAD_NOT_FOUND);
        }
        if (t.isOpen()) {
            throw new CustomException(ErrorCode.SUPPORT_NOT_CLOSED);
        }
        if (t.isRated()) {
            throw new CustomException(ErrorCode.SUPPORT_ALREADY_RATED);
        }
        if (score == null || score < 1 || score > 5
                || (comment != null && comment.strip().length() > SupportThread.RATING_COMMENT_LENGTH)) {
            throw new CustomException(ErrorCode.SUPPORT_INVALID_RATING);
        }
        t.rate(score, comment, Instant.now(clock));
        log.info("상담 평가: threadId={} score={}", threadId, score);   // 의견 본문은 로그에 남기지 않는다 ／ [JA] 意見の本文はログに残さない
        return toDto(t, false);
    }

    /** 대화 기록 — 친구 채팅과 같은 커서 규칙(-1 이면 최신 20개). */
    // [JA] 会話記録 — 友達チャットと同じカーソル規則(-1 なら最新 20 件)。
    @Transactional(readOnly = true)
    public List<MessageDto> messages(Long threadId, Long userId, String role, String cursor) {
        SupportThread t = requireAccess(threadId, userId, role);
        return chatService.getRoomMessagesByCurser(t.roomId(), cursor == null ? "-1" : cursor);
    }

    /** 상담을 끝낸다 — 고객 본인이나 상담원. 이미 끝났으면 그대로 둔다(두 번 눌러도 안내가 두 번 나가지 않는다). */
    // [JA] 相談を終える — 顧客本人か相談員。すでに終わっていればそのまま(二度押しても案内が二度出ない)。
    @Transactional
    public SupportThreadDto close(Long threadId, Long userId, String role) {
        SupportThread t = requireAccess(threadId, userId, role);
        if (t.isOpen()) {
            t.close(Instant.now(clock));
            String closed = t.isCustomer(userId) ? ChatNotices.CLOSED_BY_CUSTOMER : ChatNotices.CLOSED_BY_AGENT;
            afterCommit(() -> {
                announce(t.roomId(), MessageType.TEXT_LEAVE, closed);
                deskNotifier.notify(t.getId(), SupportDeskNotifier.Kind.CLOSED, userId);
            });
            log.info("상담 종료: threadId={} by={}", t.getId(), t.isCustomer(userId) ? "customer" : "agent");
        }
        return toDto(t, !t.isCustomer(userId));
    }

    /**
     * STOMP 로 들어온 메시지를 보내도 되는지 보고, 스레드의 목록 요약을 갱신한다. 통과하면 호출자가
     * {@code ChatService.sendMessage} 로 보낸다.
     *
     * @param message 발신자({@code senderId})는 이미 세션의 uid 로 덮어써져 있어야 한다
     *
     * <p>[JA] STOMP で入ってきたメッセージを送ってよいか確認し、スレッドの一覧要約を更新する。通れば呼び出し元が {@code ChatService.sendMessage} で送る。
     * 引数 message: 送信者({@code senderId})はすでにセッションの uid で上書きされていなければならない
     */
    @Transactional
    public void acceptMessage(MessageDto message, String role) {
        Long senderId = message.getSenderId();
        SupportThread t = requireAccess(SupportThread.threadIdOf(message.getRoomId()), senderId, role);
        if (!t.isOpen()) {
            throw new CustomException(ErrorCode.SUPPORT_THREAD_CLOSED);
        }
        if (message.getContent() == null || message.getContent().isBlank()) {
            throw new CustomException(ErrorCode.SUPPORT_EMPTY_MESSAGE);
        }
        boolean fromCustomer = t.isCustomer(senderId);
        if (!fromCustomer && !senderId.equals(t.getAgentId())) {
            // 상담원이지만 맡은 사람이 아니다(대기 중이거나 남이 맡았다).
            // [JA] 相談員だが担当者ではない(待機中か他人が担当した)。
            throw new CustomException(ErrorCode.SUPPORT_NOT_ASSIGNED);
        }
        message.setType(MessageType.TEXT_TALK);
        message.setImageUrl(null);   // 상담에는 이미지가 없다 — 임의 URL 을 상대 화면에 그리게 하지 않는다 ／ [JA] 相談には画像がない — 任意の URL を相手の画面に描かせない
        t.recordMessage(message.getContent(), fromCustomer, Instant.now(clock));
    }

    // ── 상담 데스크 ─────────────────────────────────────────────────────
    // [JA] ── 相談デスク ──

    /** 데스크 목록의 보기. */
    // [JA] デスク一覧の表示。
    public enum DeskView { WAITING, MINE, ACTIVE, CLOSED }

    @Transactional(readOnly = true)
    public List<SupportThreadDto> desk(DeskView view, Long agentId, String role) {
        return desk(view, null, agentId, role);
    }

    /** 데스크 목록 — 유형(topic)을 주면 그 유형만. "내 담당" 은 유형과 상관없이 모두(놓치면 안 된다). */
    // [JA] デスク一覧 — 種別(topic)を与えればその種別だけ。「自分の担当」は種別に関係なくすべて(見逃してはならない)。
    @Transactional(readOnly = true)
    public List<SupportThreadDto> desk(DeskView view, SupportTopic topic, Long agentId, String role) {
        requireAgent(role);
        PageRequest page = PageRequest.of(0, LIST_LIMIT);
        if (topic != null && view != DeskView.MINE) {
            SupportThreadStatus status = switch (view) {
                case WAITING -> SupportThreadStatus.WAITING;
                case CLOSED -> SupportThreadStatus.CLOSED;
                default -> SupportThreadStatus.ACTIVE;
            };
            return toDtos(threads.findDeskByTopic(status, topic, page), true);
        }
        List<SupportThread> list = switch (view) {
            case WAITING -> threads.findDesk(SupportThreadStatus.WAITING, page);
            case MINE -> threads.findMine(agentId, SupportThreadStatus.ACTIVE, page);
            case ACTIVE -> threads.findDesk(SupportThreadStatus.ACTIVE, page);
            case CLOSED -> threads.findDesk(SupportThreadStatus.CLOSED, page);
        };
        return toDtos(list, true);
    }

    /**
     * 대기 중인 상담을 맡는다. 조건부 갱신이라 두 상담원이 동시에 눌러도 한 명만 성공한다. 이미 내가 맡은 것이면
     * 그대로 성공(두 번 눌러도 된다).
     *
     * <p>[JA] 待機中の相談を担当する。条件付き更新なので二人の相談員が同時に押しても一人だけ成功する。すでに自分が担当したものならそのまま成功(二度押してもよい)。
     */
    @Transactional
    public SupportThreadDto claim(Long threadId, Long agentId, String role) {
        requireAgent(role);
        ChatMember agent = chatService.ensureMember(agentId);
        int updated = threads.claim(threadId, agentId, Instant.now(clock));
        SupportThread t = threads.findById(threadId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUPPORT_THREAD_NOT_FOUND));
        if (updated == 0) {
            if (!t.isOpen()) {
                throw new CustomException(ErrorCode.SUPPORT_THREAD_CLOSED);
            }
            if (!agentId.equals(t.getAgentId())) {
                throw new CustomException(ErrorCode.SUPPORT_ALREADY_CLAIMED);
            }
            return toDto(t, true);   // 이미 내 것 ／ [JA] すでに自分のもの
        }
        String name = agent.getNickname();
        afterCommit(() -> {
            announce(t.roomId(), MessageType.TEXT_ENTER, ChatNotices.agentJoined(name));
            deskNotifier.notify(t.getId(), SupportDeskNotifier.Kind.CLAIMED, agentId);
        });
        log.info("상담 배정: threadId={} agentId={}", threadId, agentId);
        return toDto(t, true);
    }

    /**
     * 상담원의 내부 메모 — 🔒 상담원끼리만 본다. 고객 응답({@link #toDtos} 의 forDesk=false)에는 싣지 않는다.
     * 맡은 사람이 아니어도 쓸 수 있다 — 반납 · 교대 때 다음 상담원에게 남기는 글이다.
     *
     * <p>[JA] 相談員の内部メモ — 🔒 相談員同士だけが見る。顧客への応答({@link #toDtos} の forDesk=false)には載せない。
     * 担当者でなくても書ける — 返却・交代のときに次の相談員に残す文である。
     */
    @Transactional
    public SupportThreadDto updateNote(Long threadId, Long agentId, String role, String note) {
        requireAgent(role);
        if (note != null && note.strip().length() > SupportThread.NOTE_LENGTH) {
            throw new CustomException(ErrorCode.SUPPORT_NOTE_TOO_LONG);
        }
        SupportThread t = threads.findById(threadId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUPPORT_THREAD_NOT_FOUND));
        t.updateNote(note, Instant.now(clock));
        log.info("상담 메모 갱신: threadId={} by={}", threadId, agentId);   // 메모 본문은 로그에 남기지 않는다 ／ [JA] メモ本文はログに残さない
        return toDto(t, true);
    }

    /**
     * 맡은 상담을 반납한다 — 다시 대기 목록으로(docs/58 §11). 교대 · 다른 팀으로 넘기기 · 잘못 맡았을 때.
     * 맡은 상담원만(관리자는 누구의 것이든 — 자리를 비운 상담원의 것을 풀어 준다). 고객에게는 "상담원이 바뀝니다" 안내가 간다.
     *
     * <p>[JA] 担当した相談を返却する — 再び待機リストへ(docs/58 §11)。交代・別チームへの引き継ぎ・誤って担当したとき。
     * 担当の相談員だけ(管理者は誰のものでも — 席を外した相談員のものを解放する)。顧客には「相談員が変わります」の案内が行く。
     */
    @Transactional
    public SupportThreadDto release(Long threadId, Long agentId, String role) {
        requireAgent(role);
        SupportThread t = threads.findById(threadId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUPPORT_THREAD_NOT_FOUND));
        if (!t.isOpen()) {
            throw new CustomException(ErrorCode.SUPPORT_THREAD_CLOSED);
        }
        if (t.getStatus() != SupportThreadStatus.ACTIVE
                || (!agentId.equals(t.getAgentId()) && !SupportRoles.isAdmin(role))) {
            throw new CustomException(ErrorCode.SUPPORT_NOT_YOURS);
        }
        t.release();
        afterCommit(() -> {
            announce(t.roomId(), MessageType.TEXT_LEAVE, ChatNotices.RELEASED);
            deskNotifier.notify(t.getId(), SupportDeskNotifier.Kind.RELEASED, agentId);
        });
        log.info("상담 반납: threadId={} by={}", threadId, agentId);
        return toDto(t, true);
    }

    // ── 권한 ────────────────────────────────────────────────────────────
    // [JA] ── 権限 ──

    /** 고객 본인이거나 상담원이면 스레드, 아니면 404. */
    // [JA] 顧客本人か相談員ならスレッド、そうでなければ 404。
    SupportThread requireAccess(Long threadId, Long userId, String role) {
        if (threadId == null || userId == null) {
            throw new CustomException(ErrorCode.SUPPORT_THREAD_NOT_FOUND);
        }
        SupportThread t = threads.findById(threadId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUPPORT_THREAD_NOT_FOUND));
        if (t.isCustomer(userId) || SupportRoles.isAgent(role)) {
            return t;
        }
        throw new CustomException(ErrorCode.SUPPORT_THREAD_NOT_FOUND);
    }

    /** 구독 권한 — STOMP SUBSCRIBE 가 부른다. */
    // [JA] 購読権限 — STOMP SUBSCRIBE が呼ぶ。
    @Transactional(readOnly = true)
    public boolean canSubscribe(String roomId, Long userId, String role) {
        try {
            requireAccess(SupportThread.threadIdOf(roomId), userId, role);
            return true;
        } catch (CustomException e) {
            return false;
        }
    }

    private static void requireAgent(String role) {
        if (!SupportRoles.isAgent(role)) {
            throw new CustomException(ErrorCode.SUPPORT_AGENT_ONLY);
        }
    }

    // ── 안내 메시지 ─────────────────────────────────────────────────────
    // [JA] ── 案内メッセージ ──

    /** 팀 명의의 안내(연결·종료). 발신자 id 가 없다 — 화면은 가운데 안내로 그린다. */
    // [JA] チーム名義の案内(接続・終了)。送信者 id がない — 画面は中央の案内として描く。
    private void announce(String roomId, MessageType type, String content) {
        MessageDto notice = MessageDto.builder().type(type).roomId(roomId).content(content).build();
        notice.setId(sequenceGenerator.generateSequence(MessageDto.SEQUENCE_NAME));
        notice.setSendTimeAndSender(java.time.Instant.now(), TEAM_NAME, null, 0);
        messageProducer.send(KafkaUtil.KAFKA_TOPIC, notice);
    }

    /** 커밋된 뒤에 — 롤백된 배정·종료의 안내가 나가면 화면과 DB 가 어긋난다. */
    // [JA] コミットされた後に — ロールバックされた割り当て・終了の案内が出ると画面と DB が食い違う。
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // ── 변환 ────────────────────────────────────────────────────────────
    // [JA] ── 変換 ──

    private SupportThreadDto toDto(SupportThread t, boolean forDesk) {
        return toDtos(List.of(t), forDesk).get(0);
    }

    private SupportThreadDto.SupportThreadDtoBuilder toDtoBuilder(SupportThread t, boolean forDesk) {
        return builders(List.of(t), forDesk).get(0);
    }

    /** 이름은 한 번에 읽는다 — 목록 50개에 회원 조회 100번이 나가지 않게. */
    // [JA] 名前は一度に読む — 一覧 50 件に会員照会が 100 回出ないように。
    private List<SupportThreadDto> toDtos(List<SupportThread> list, boolean forDesk) {
        return builders(list, forDesk).stream().map(SupportThreadDto.SupportThreadDtoBuilder::build).toList();
    }

    /**
     * 🔒 forDesk=false(고객 응답)에는 상담원 내부 메모를 <b>싣지 않는다</b>. 대기 순번은 고객 화면에만 —
     * 데스크 목록 50개마다 세지 않는다.
     *
     * <p>[JA] 🔒 forDesk=false(顧客への応答)には相談員の内部メモを<b>載せない</b>。待ち順番は顧客画面にだけ — デスク一覧 50 件ごとに数えない。
     */
    private List<SupportThreadDto.SupportThreadDtoBuilder> builders(List<SupportThread> list, boolean forDesk) {
        Set<Long> ids = list.stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getCustomerId(), t.getAgentId()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = ids.isEmpty() ? Map.of()
                : members.findAllById(ids).stream()
                        .collect(Collectors.toMap(ChatMember::getId, m -> String.valueOf(m.getNickname()), (a, b) -> a));
        Function<Long, String> name = id -> id == null ? null : names.getOrDefault(id, ChatService.UNKNOWN_NICKNAME);
        return list.stream().map(t -> SupportThreadDto.builder()
                .threadId(t.getId())
                .roomId(t.roomId())
                .status(t.getStatus())
                .customerId(t.getCustomerId())
                .customerName(forDesk ? name.apply(t.getCustomerId()) : null)
                .agentId(t.getAgentId())
                .agentName(name.apply(t.getAgentId()))
                .awaitingReply(t.isAwaitingReply())
                .lastMessagePreview(t.getLastMessagePreview())
                .lastMessageAt(t.getLastMessageAt())
                .createdAt(t.getCreatedAt())
                .assignedAt(t.getAssignedAt())
                .closedAt(t.getClosedAt())
                .topic(t.getTopic())
                .queuePosition(!forDesk && t.getStatus() == SupportThreadStatus.WAITING
                        ? threads.countWaitingBefore(t.getCreatedAt()) + 1 : null)
                .rating(t.getRating() == null ? null : t.getRating().intValue())
                .ratingComment(t.getRatingComment())
                .ratedAt(t.getRatedAt())
                .internalNote(forDesk ? t.getInternalNote() : null)
                .noteUpdatedAt(forDesk ? t.getNoteUpdatedAt() : null)).toList();
    }
}
