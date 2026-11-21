package com.project.chat_service.common.config.interceptor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

import com.project.chat_service.common.exception.handler.ErrorCode;
import com.project.chat_service.common.util.TokenHandler;
import com.project.chat_service.service.ChatService;

import java.util.Objects;

/**
 * STOMP 프레임의 인증 · 권한(CONNECT · SUBSCRIBE · SEND). 빈은 {@code WebSocketConfiguration.myChannelInterceptor()} 하나다.
 *
 * <p>🐛 예전: {@code @Component} 도 붙어 있어 같은 인터셉터가 빈 둘로 떴다(인터셉터로 쓰이는 것은 하나). 지웠다.
 * [JA] 以前は @Component も付いていて、同じインターセプターが Bean 二つで起動していた。
 */
@Slf4j
public class StompHandler implements ChannelInterceptor {

    // prevent circular referenece
    @Autowired
    private TokenHandler tokenHandler;
    @Autowired
    private ChatService chatService;

    /** 세션에 담는 신원 — 검증된 토큰의 uid(member_id) 하나다. 이메일은 담지 않는다(docs/48 §6-⑥). */
    // [JA] セッションに入れる身元 — 検証済みトークンの uid(member_id)一つである。メールは入れない(docs/48 §6-⑥)。
    public static final String SESSION_ATTR_USER_ID = "userId";
    /** 세션에 담는 역할 — 검증된 토큰의 role 클레임. 상담 데스크 권한에 쓴다(docs/58). */
    // [JA] セッションに入れるロール — 検証済みトークンの role クレーム。相談デスク権限に使う(docs/58)。
    public static final String SESSION_ATTR_USER_ROLE = "userRole";

    /**
     * 구독할 수 있는 주소의 모양 — 방 하나. 🔒 기본 브로커는 구독 주소를 <b>패턴</b>으로 맞춘다(AntPathMatcher) —
     * {@code /sub/**} 를 구독하면 모든 방의 메시지가, 상담 메시지까지 온다. 와일드카드가 들어갈 수 없는 모양만
     * 받는다(docs/58).
     *
     * <p>[JA] 購読できる宛先の形 — ルーム一つ。🔒 シンプルブローカーは購読宛先を<b>パターン</b>で照合する(AntPathMatcher) — {@code /sub/**} を購読するとすべてのルームのメッセージが、相談メッセージまで届く。ワイルドカードが入り得ない形だけを受け付ける(docs/58)。
     */
    static final java.util.regex.Pattern ROOM_DESTINATION =
            java.util.regex.Pattern.compile("^/sub/chat/room/([A-Za-z0-9-]{1,64})$");

    /**
     * 🐛 {@code @Lazy} 가 없어 chat-service 가 기동하지 못했다(2026-09-26, docs/57 §17). 순환:
     * 브로커 템플릿 → clientInboundChannel → 이 인터셉터 → SupportService → SupportDeskNotifier → 브로커 템플릿.
     * 처음 쓸 때 받는다 — STOMP 프레임을 처리할 때는 컨텍스트가 이미 다 떠 있다. {@code WebSocketWiringTest} 가 막는다.
     *
     * <p>[JA] 🐛 {@code @Lazy} がなく chat-service が起動できなかった(2026-09-26, docs/57 §17)。循環:
     * ブローカーテンプレート → clientInboundChannel → このインターセプタ → SupportService → SupportDeskNotifier → ブローカーテンプレート。
     * 初回使用時に取得する — STOMP フレームを処理する時点ではコンテキストはすでに完全に立ち上がっている。{@code WebSocketWiringTest} が防ぐ。
     */
    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.project.chat_service.service.SupportService supportService;
    @Autowired
    private com.project.chat_service.service.SupportPresence supportPresence;
    /** 연결을 토큰 수명 · 로그인 해제에 묶는다(docs/68 §9). */
    // [JA] 接続をトークン寿命・ログアウトに結び付ける(docs/68 §9)。
    @Autowired
    private LiveSessionRegistry liveSessions;

    /** 세션에 담는 토큰 만료(epoch ms) — 이 뒤의 SUBSCRIBE · SEND 는 거절한다(닫기 예약이 늦어도 막는다). */
    // [JA] セッションに入れるトークン有効期限(epoch ms) — これ以降の SUBSCRIBE・SEND は拒否する(クローズ予約が遅れても防ぐ)。
    public static final String SESSION_ATTR_EXPIRES_AT = "tokenExpiresAt";
    /** 공개 데모 계정의 세션 — 보내기 속도 제한(docs/100). [JA] 公開デモアカウントのセッション — 送信の速度制限。 */
    public static final String SESSION_ATTR_DEMO = "demo";

    @Autowired(required = false)
    private com.project.chat_service.service.DemoChatLimiter demoChatLimiter;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        log.debug("STOMP Command: {}", command);

        if (StompCommand.CONNECT == command) {

            String accessToken = getAccessToken(accessor);

            if (accessToken == null) {
                log.warn("STOMP CONNECT rejected: missing access token");
                throw ErrorCode.unauthorizedTokenException();
            }
            if (!tokenHandler.verifyToken(accessToken)) {
                log.warn("STOMP CONNECT rejected: invalid access token");
                throw ErrorCode.unauthorizedTokenException();
            }

            // 이후 SUBSCRIBE/SEND 프레임에서는 클라이언트가 보낸 헤더·본문을 신뢰하지 않고 CONNECT 시점에 토큰으로
            // 검증된 uid 만을 사용자 식별자로 사용한다. uid 가 없는 토큰은 게이트웨이도 401 로 막는다 — 여기서도 막는다.
            // [JA] 以降の SUBSCRIBE/SEND フレームではクライアントが送ったヘッダ・本文を信頼せず、CONNECT 時点でトークンにより
            //      検証された uid だけをユーザー識別子として使う。uid のないトークンはゲートウェイも 401 で弾く — ここでも弾く。
            Long userId = tokenHandler.getUserId(accessToken);
            if (userId == null) {
                log.warn("STOMP CONNECT rejected: token has no uid claim");
                throw ErrorCode.unauthorizedTokenException();
            }
            // 🔒 로그인이 해제된 뒤의 옛 토큰으로는 다시 붙지 못한다(docs/68 §9) — 닫기만 하면 옛 토큰으로 곧장 다시 붙는다.
            //    [JA] ログイン解除後の古いトークンでは再接続できない — 閉じるだけでは古いトークンですぐ張り直される。
            if (!liveSessions.allowed(userId, tokenHandler.getIssuedAtEpochSec(accessToken))) {
                log.warn("STOMP CONNECT rejected: token issued before session revocation. userId={}", userId);
                throw ErrorCode.unauthorizedTokenException();
            }
            long expiresAt = tokenHandler.getExpiresAtEpochMs(accessToken);
            Objects.requireNonNull(accessor.getSessionAttributes()).put(SESSION_ATTR_USER_ID, userId);
            accessor.getSessionAttributes().put(SESSION_ATTR_EXPIRES_AT, expiresAt);
            // 🔴 연결은 토큰 만료에 닫힌다(close 4401) — 예전에는 CONNECT 뒤 끊길 때까지 살아 있었다.
            // [JA] 🔴 接続はトークン有効期限で閉じる(close 4401) — 以前は CONNECT 後、切断されるまで生き続けていた。
            liveSessions.bind(accessor.getSessionId(), userId, expiresAt);
            String role = tokenHandler.getRole(accessToken);
            if (role != null) {
                accessor.getSessionAttributes().put(SESSION_ATTR_USER_ROLE, role);
            }
            if (tokenHandler.isDemo(accessToken)) {
                accessor.getSessionAttributes().put(SESSION_ATTR_DEMO, Boolean.TRUE);
            }

        } else if (StompCommand.SUBSCRIBE == command) {

            String userSessionId = (String) message.getHeaders().get("simpSessionId");
            String roomId = getRoomId(accessor);
            Long userId = getAuthenticatedUserId(accessor);
            requireUnexpired(accessor);

            // 상담 데스크 알림(docs/58 §9) — 상담원·관리자만. 방이 아니므로 참여자 기록도 없다.
            // [JA] 相談デスク通知(docs/58 §9) — 相談員・管理者のみ。ルームではないので参加者記録もない。
            if (com.project.chat_service.service.SupportDeskNotifier.DESTINATION.equals(accessor.getDestination())) {
                if (!com.project.chat_service.constants.SupportRoles.isAgent(getAuthenticatedRole(accessor))) {
                    log.warn("SUBSCRIBE rejected: support desk requires agent role. userId={}", userId);
                    throw ErrorCode.unauthorizedTokenException();
                }
                return message;
            }

            if (roomId == null) {
                log.warn("SUBSCRIBE rejected: missing roomId");
                throw ErrorCode.unauthorizedTokenException();
            }

            // 🔒 권한은 헤더가 아니라 <b>실제 구독 주소</b>로 판정한다. roomId 헤더는 클라이언트가 정한다 — 헤더에는
            //    공개 방을, 주소에는 남의 상담을 적으면 헤더만 보는 검사는 통과한다.
            // [JA] 🔒 権限はヘッダではなく<b>実際の購読宛先</b>で判定する。roomId ヘッダはクライアントが決める — ヘッダには
            //      公開ルームを、宛先には他人の相談を書けばヘッダだけ見る検査は通ってしまう。
            java.util.regex.Matcher dest = ROOM_DESTINATION.matcher(String.valueOf(accessor.getDestination()));
            if (!dest.matches()) {
                log.warn("SUBSCRIBE rejected: destination not a single room: {}", accessor.getDestination());
                throw ErrorCode.unauthorizedTokenException();
            }
            String destinationRoom = dest.group(1);
            if (com.project.chat_service.model.SupportThread.isSupportRoom(destinationRoom)
                    || com.project.chat_service.model.SupportThread.isSupportRoom(roomId)) {
                if (!destinationRoom.equals(roomId)
                        || !supportService.canSubscribe(destinationRoom, userId, getAuthenticatedRole(accessor))) {
                    log.warn("SUBSCRIBE rejected: support room not accessible. room={}, userId={}", destinationRoom, userId);
                    throw ErrorCode.unauthorizedTokenException();
                }
                // 상담은 친구 채팅방의 참여자 표(participant_chat_room)에 적지 않는다 — 방 행이 없다.
                // 대신 "보고 있다" 를 적는다 — 보고 있는 고객에게는 답변 알림을 보내지 않는다(docs/58 §10).
                // [JA] 相談は友達チャットルームの参加者表(participant_chat_room)に書かない — ルーム行がない。
                //      代わりに「見ている」を記録する — 見ている顧客には回答通知を送らない(docs/58 §10)。
                supportPresence.subscribed(userSessionId, accessor.getSubscriptionId(), userId, destinationRoom);
                return message;
            }

            log.info("Subscribe : room={}, session={}", roomId, userSessionId);

            // 🐛 <b>여기서 던진 예외가 구독 자체를 죽였다.</b> 클라이언트에는 STOMP ERROR 의
            //    "Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]"
            //    만 보이고, 그 방은 영영 못 들어간다. 그런데 참여자 등록은 <b>기록</b>이지
            //    권한 검사가 아니다 — 구독 자체는 누구에게도 막혀 있지 않다. 기록에 실패하면
            //    남기고 넘어간다. 진짜 원인은 아래 로그(예외 타입·메시지)에 남는다.
            // [JA] 🐛 <b>ここで投げた例外が購読自体を殺していた。</b>クライアントには STOMP ERROR の
            //      "Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]"
            //      しか見えず、そのルームには永遠に入れない。しかし参加者登録は<b>記録</b>であって
            //      権限検査ではない — 購読自体は誰にも塞がれていない。記録に失敗したら
            //      ログを残して進む。本当の原因は下のログ(例外型・メッセージ)に残る。
            try {
                chatService.connect(roomId, userSessionId, userId);
            } catch (RuntimeException e) {
                log.error("참여자 등록 실패 — 구독은 그대로 진행한다. room={}, session={}",
                        roomId, userSessionId, e);
            }

        } else if (StompCommand.SEND == command) {

            // 🔒 클라이언트는 애플리케이션 주소(/pub/**)로만 보낸다. 브로커 주소(/sub/**)로 보낸 SEND 는 기본 브로커가
            //    <b>그대로 구독자에게 배달한다</b> — 컨트롤러의 발신자 덮어쓰기·상담 권한 검사를 건너뛰고 아무 방(남의
            //    상담 포함)에나 임의의 발신자·내용을 넣을 수 있었다(docs/58 §9).
            // [JA] 🔒 クライアントはアプリケーション宛先(/pub/**)にだけ送る。ブローカー宛先(/sub/**)に送った SEND はシンプルブローカーが
            //      <b>そのまま購読者に配送する</b> — コントローラの送信者上書き・相談権限検査を飛ばして任意のルーム(他人の
            //      相談を含む)に任意の送信者・内容を入れられた(docs/58 §9)。
            getAuthenticatedUserId(accessor);
            requireUnexpired(accessor);
            String destination = accessor.getDestination();
            if (destination == null || !destination.startsWith("/pub/")) {
                log.warn("SEND rejected: not an application destination: {}", destination);
                throw ErrorCode.unauthorizedTokenException();
            }
            // 공개 데모 계정은 보내기 수를 제한한다 — 넘으면 그 메시지만 버린다(연결은 끊지 않는다), docs/100.
            // [JA] 公開デモアカウントは送信数を制限する — 超えたらそのメッセージだけ捨てる(接続は切らない)。
            if (demoChatLimiter != null && accessor.getSessionAttributes() != null
                    && Boolean.TRUE.equals(accessor.getSessionAttributes().get(SESSION_ATTR_DEMO))
                    && !demoChatLimiter.allow(getAuthenticatedUserId(accessor))) {
                log.info("SEND dropped: demo account over chat limit. userId={}", getAuthenticatedUserId(accessor));
                return null;
            }

        } else if (StompCommand.UNSUBSCRIBE == command) {

            supportPresence.unsubscribed((String) message.getHeaders().get("simpSessionId"), accessor.getSubscriptionId());

        } else if (StompCommand.DISCONNECT == command) {

            String userSessionId = (String) message.getHeaders().get("simpSessionId");
            log.info("Disconnect : session={}", userSessionId);
            supportPresence.disconnected(userSessionId);
            chatService.disconnect(userSessionId);
        }

        return message;
    }

    /**
     * CONNECT 시점에 토큰 검증 후 세션에 저장해 둔 인증된 사용자 id 를 반환한다.
     * 클라이언트가 SUBSCRIBE/SEND 프레임에 실어 보내는 헤더/바디 값은 위조 가능하므로 사용하지 않는다.
     *
     * <p>[JA] CONNECT 時点でトークン検証後にセッションへ保存しておいた認証済みユーザー id を返す。
     * クライアントが SUBSCRIBE/SEND フレームに載せて送るヘッダ/本文の値は偽造可能なので使わない。
     */
    private Long getAuthenticatedUserId(StompHeaderAccessor accessor) {
        Object userId = accessor.getSessionAttributes() == null
                ? null
                : accessor.getSessionAttributes().get(SESSION_ATTR_USER_ID);
        if (!(userId instanceof Long id)) {
            log.warn("Rejected STOMP frame with no authenticated session identity");
            throw ErrorCode.unauthorizedTokenException();
        }
        return id;
    }

    /**
     * CONNECT 때 적어 둔 토큰 만료가 지났으면 거절한다. 닫기 예약(LiveSessionRegistry)이 주된 장치이고 이것은 두 번째 겹이다 —
     * 예약이 몇 ms 늦어도 만료된 토큰으로 구독 · 전송하지 못한다. 만료가 없으면(CONNECT 를 거치지 않은 세션) 거절한다.
     *
     * <p>[JA] CONNECT 時に記録したトークン有効期限が過ぎていれば拒否する。クローズ予約(LiveSessionRegistry)が主な仕組みでこれは二重目の層である —
     * 予約が数 ms 遅れても期限切れトークンで購読・送信できない。有効期限がなければ(CONNECT を経ていないセッション)拒否する。
     */
    private void requireUnexpired(StompHeaderAccessor accessor) {
        Object expiresAt = accessor.getSessionAttributes() == null
                ? null
                : accessor.getSessionAttributes().get(SESSION_ATTR_EXPIRES_AT);
        if (!(expiresAt instanceof Long at) || at <= System.currentTimeMillis()) {
            log.warn("Rejected STOMP frame: token expired or unknown");
            throw ErrorCode.unauthorizedTokenException();
        }
    }

    private String getAuthenticatedRole(StompHeaderAccessor accessor) {
        Object role = accessor.getSessionAttributes() == null
                ? null
                : accessor.getSessionAttributes().get(SESSION_ATTR_USER_ROLE);
        return role instanceof String r ? r : null;
    }

    private String getAccessToken(StompHeaderAccessor accessor) {
        return accessor.getFirstNativeHeader("Authorization");
    }

    private String getRoomId(StompHeaderAccessor accessor) {
        return accessor.getFirstNativeHeader("roomId");
    }

}
