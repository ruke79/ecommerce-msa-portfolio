package com.project.userservice.message;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.userservice.message.model.OutboxEvent;
import com.project.userservice.message.producer.UserCreatedProducer;
import com.project.userservice.message.producer.UserLifecycleProducer;
import com.project.userservice.message.repository.jpa.OutboxEventRepository;
import com.project.userservice.model.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 사용자 이벤트를 <b>아웃박스에 적는다</b> — 보내지는 않는다.
 *
 * <h3>왜 이 클래스가 생겼나</h3>
 *
 * <p>{@code users.outbox_event} 테이블과 엔티티는 <b>처음부터 있었다.</b> 그런데 쓰는
 * 곳이 없었다 — {@code UserServiceImpl} 에 이렇게 <b>주석 처리된 채로</b> 남아 있었다:
 *
 * <pre>{@code
 * //OutboxEvent event = createOutboxEvent(in);
 * //outboxEventRepository.save(event);
 * userCreatedProducer.publish(dto);
 * }</pre>
 *
 * <p>즉 아웃박스를 만들어 두고 <b>주석 한 줄 차이로</b> dual-write 를 쓰고 있었다. 그
 * 대가가 docs/47 이다: 계정은 커밋됐는데 이벤트는 안 갔고, 아무도 몰랐고, 그 사용자는
 * 몇 주 뒤 주문 버튼에서 400 을 받았다.
 *
 * <h3>⚠️ 반드시 호출자의 트랜잭션에 참여한다</h3>
 *
 * <p>{@code MANDATORY} 로 못 박는다. 트랜잭션 없이 불리면 <b>즉시 예외</b>다 — 그것이
 * 바로 dual-write 이고, 조용히 성립해 버리면 이 작업 전체가 무의미해진다. 새 트랜잭션을
 * 여는 {@code REQUIRES_NEW} 도 같은 이유로 안 된다.
 *
 * <p>이 행이 커밋되면 이벤트는 <b>반드시 나간다</b>(폴러가 계속 재시도한다). 커밋되지
 * 않으면 계정 활성화도 함께 롤백된다. 둘 중 하나만 일어나는 일이 없다.
 *
 * <p>[JA] ユーザーイベントを<b>アウトボックスに書く</b> — 送りはしない。
 * なぜこのクラスができたか: {@code users.outbox_event} テーブルとエンティティは<b>最初からあった。</b>ところが書く場所が
 * なかった — {@code UserServiceImpl} に<b>コメントアウトされたまま</b>残っていた(上の例)。
 * つまりアウトボックスを作っておいて<b>コメント一行の差で</b> dual-write を使っていた。その代償が docs/47 だ:
 * アカウントはコミットされたのにイベントは送られず、誰も気づかず、そのユーザーは数週間後に注文ボタンで 400 を受けた。
 * ⚠️ 必ず呼び出し側のトランザクションに参加する: {@code MANDATORY} で固定する。トランザクションなしで呼ばれると
 * <b>即座に例外</b>だ — それこそが dual-write で、黙って成り立ってしまうとこの作業全体が無意味になる。新しいトランザクションを
 * 開く {@code REQUIRES_NEW} も同じ理由で不可。
 * この行がコミットされればイベントは<b>必ず送られる</b>(ポーラーが再試行し続ける)。コミットされなければ
 * アカウント有効化も一緒にロールバックされる。片方だけが起きることはない。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserOutboxHelper {

    /** 아웃박스 행의 aggregate 종류. CDC EventRouter 로 옮길 때 라우팅 근거가 된다. */
    // [JA] アウトボックス行の aggregate 種別。CDC EventRouter に移すときルーティングの根拠になる。
    private static final String AGGREGATE_TYPE = "User";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;
    /**
     * 커밋되면 폴링을 기다리지 않고 바로 릴레이한다(docs/56 §9). 가입 확인 직후 채팅·쿠폰 화면으로 가면
     * 복제본(chat_member)과 가입 쿠폰이 아직 없던 최대 10초를 없앤다.
     *
     * <p>[JA] コミットされたらポーリングを待たずにすぐリレーする(docs/56 §9)。登録確認の直後にチャット・クーポン画面へ行くと
     * 写し(chat_member)と登録クーポンがまだなかった最大 10 秒をなくす。
     */
    private final com.project.common.outbox.OutboxRelayTrigger relayTrigger;
    /** 가입 확인 메일 릴레이를 커밋 직후 깨운다(RegistrationMailRelay). [JA] 登録確認メールのリレーをコミット直後に起こす。 */
    private final RegistrationMailTrigger registrationMailTrigger;

    /**
     * 가입 확정 이벤트를 적는다.
     *
     * <p>멱등성은 호출자가 갖는다 — {@code validateAndGetUser} 는 <b>이미 활성화된
     * 계정이면 그보다 먼저 빠져나간다.</b> 확인 링크를 두 번 눌러도 행은 하나다.
     *
     * <p>[JA] 登録確定イベントを書く。
     * 冪等性は呼び出し側が持つ — {@code validateAndGetUser} は<b>既に有効化されたアカウントならそれより先に抜ける。</b>
     * 確認リンクを二回押しても行は一つだ。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUserCreated(User user) {
        UserCreatedRequest dto = UserCreatedRequest.builder()
                .userId(user.getUserId())
                .image(user.getImage())
                // 이메일은 없다(docs/48 §6-⑥) — DTO·Avro(v2) 모두. 소비자 둘 중 coupon 은 id 만 쓰고, chat 은 신원을
                // uid 로 옮겼다. 쓰지 않는 개인정보를 여러 DB 로 퍼뜨리지 않는다(데이터 최소화).
                // [JA] メールはない(docs/48 §6-⑥) — DTO・Avro(v2)とも。二つのコンシューマのうち coupon は id だけを使い、chat は身元を
                //      uid に移した。使わない個人情報を複数の DB に広げない(データ最小化)。
                .nickname(user.getName())
                .build();

        String payload;
        try {
            payload = objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            // 여기서 던지면 인증 확정까지 롤백된다. 그게 맞다 — 보낼 수 없는 이벤트를
            // 적어 두고 성공했다고 하는 것보다, 사용자가 링크를 다시 누르는 편이 낫다.
            // [JA] ここで投げると認証確定までロールバックされる。それが正しい — 送れないイベントを
            //      書いておいて成功したと言うより、ユーザーがリンクをもう一度押すほうがよい。
            throw new IllegalStateException(
                    "user-created 페이로드를 만들지 못했다: userId=" + user.getUserId(), e);
        }

        outboxEventRepository.save(new OutboxEvent(
                String.valueOf(user.getUserId()),
                AGGREGATE_TYPE,
                UserCreatedProducer.TOPIC,
                payload)
                // 가입 확인 요청의 추적 문맥 — 릴레이가 되살려 보낸다(docs/51 §4e).
                // [JA] 登録確認リクエストの追跡コンテキスト — リレーが復元して送る(docs/51 §4e)。
                .withTraceContext(outboxTraceContext.capture()));
        // MANDATORY 라 언제나 트랜잭션 안이다 — 인증 확정이 커밋된 뒤에 돈다. 롤백되면 돌지 않는다.
        // [JA] MANDATORY なので常にトランザクション内だ — 認証確定がコミットされたあとに動く。ロールバックされれば動かない。
        relayTrigger.afterCommit();

        log.info("user-created 아웃박스 기록: userId={}", user.getUserId());
    }

    /**
     * 표시용 프로필(닉네임·이미지)이 바뀌었다 — 복제본(chat_member)이 따라오게 한다(docs/48 §6-⑤).
     *
     * <p>이메일은 싣지 않는다 — 받는 쪽이 쓰지 않는다(데이터 최소화). 값 전체를 싣는다(부분 변경이 아니라 최신 상태) —
     * 순서가 뒤집혀 옛 이벤트가 나중에 와도 {@code updatedAt} 으로 거를 수 있다.
     *
     * <p>[JA] 表示用プロフィール(ニックネーム・画像)が変わった — 写し(chat_member)が追従するようにする(docs/48 §6-⑤)。
     * メールは載せない — 受け手が使わない(データ最小化)。値全体を載せる(部分変更ではなく最新状態) —
     * 順序が逆転して古いイベントが後から来ても {@code updatedAt} でふるい落とせる。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUserUpdated(User user) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("userId", user.getUserId());
        event.put("nickname", user.getName());
        event.put("image", user.getImage());
        long at = System.currentTimeMillis();
        event.put("updatedAt", at);
        event.put("eventId", UserLifecycleProducer.UPDATED + ":" + user.getUserId() + ":" + at);
        record(user.getUserId(), UserLifecycleProducer.UPDATED, event);
    }

    /**
     * 탈퇴가 최종 처리됐다(원본은 익명화됐다) — 복제본도 식별정보를 지우게 한다(docs/48 §6-⑤).
     * 행을 지우라는 뜻이 아니다 — 채팅 기록이 누구 것이었는지(탈퇴한 사용자)는 남는다.
     *
     * <p>[JA] 退会が最終処理された(原本は匿名化された) — 写しも識別情報を消すようにする(docs/48 §6-⑤)。
     * 行を消せという意味ではない — チャット記録が誰のものだったか(退会したユーザー)は残る。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUserDeleted(Long userId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("userId", userId);
        event.put("eventId", UserLifecycleProducer.DELETED + ":" + userId);
        record(userId, UserLifecycleProducer.DELETED, event);
    }

    private void record(Long userId, String topic, Map<String, Object> event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(topic + " 페이로드를 만들지 못했다: userId=" + userId, e);
        }
        outboxEventRepository.save(new OutboxEvent(String.valueOf(userId), AGGREGATE_TYPE, topic, payload)
                .withTraceContext(outboxTraceContext.capture()));
        relayTrigger.afterCommit();
        log.info("{} 아웃박스 기록: userId={}", topic, userId);
    }

    /**
     * 가입 확인 메일을 보내야 한다고 적는다 — 부르는 쪽의 트랜잭션에 든다(가입이면 계정 저장과 한 트랜잭션, docs/93 ③ 2단계).
     * 행에는 <b>언어만</b> 싣는다 — 원문 토큰 · 이메일을 아웃박스 행에 남기지 않는다. 토큰 · 링크 · 받는 주소는
     * {@link RegistrationMailRelay} 가 보낼 때 만든다.
     * [JA] 登録確認メールを送るべきと書く — 呼び出し側のトランザクションに入る。行には言語だけを載せる(トークン · メールは残さない)。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRegistrationMail(User user, java.util.Locale locale) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locale", locale == null ? null : locale.toLanguageTag());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("가입 확인 메일 행을 만들지 못했다: userId=" + user.getUserId(), e);
        }
        outboxEventRepository.save(new OutboxEvent(String.valueOf(user.getUserId()), AGGREGATE_TYPE,
                RegistrationMailRelay.EVENT_TYPE, payload).withTraceContext(outboxTraceContext.capture()));
        registrationMailTrigger.afterCommit();
    }
}
