package com.project.chat_service.message.consumer;




import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.project.chat_service.common.util.KafkaUtil;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.message.fanout.StompFanout;
import com.project.chat_service.service.ChatService;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageConsumer {

    private final StompFanout fanout;
    private final ChatService chatService;
    private final com.project.chat_service.service.SupportDeskNotifier deskNotifier;
    private final com.project.chat_service.service.SupportReplyNotifier replyNotifier;


    /**
     * 뿌리기 · 알림을 하는 레코드의 나이 상한(기본 10분). 그보다 오래된 레코드는 <b>저장만</b> 한다.
     *
     * <p>이 그룹(chat)의 오프셋 기본값은 earliest 다(docs/89 §2-1) — 그룹이 처음 뜨거나(새 Kafka 클러스터) 오프셋이 만료되면 토픽 보존 안의
     * 옛 메시지를 다시 읽는다. 저장은 멱등이다(_id 는 보낸 쪽이 정한 순번 — 같은 문서를 다시 쓴다). 하지만 지금 방에 있는 사람에게 며칠 전
     * 메시지를 다시 뿌리거나 상담 데스크 · 고객에게 다시 알리면 안 된다. 🐛 예전에는 latest 라 그 사이의 메시지가 <b>저장되지 않고</b> 이력에서
     * 사라졌다.
     * [JA] 配信 · 通知をするレコードの年齢上限(既定 10 分)。それより古いレコードは保存だけ。グループは earliest なので、初回 · オフセット
     *      失効時に古いメッセージを読み直す — 保存は冪等だが、今ルームにいる人へ数日前のメッセージを再配信 · 再通知してはいけない。
     */
    @org.springframework.beans.factory.annotation.Value("${chat.consumer.live-window:PT10M}")
    private java.time.Duration liveWindow;

    @KafkaListener(topics = KafkaUtil.KAFKA_TOPIC, containerFactory = "kafkaListenerContainerFactory")
    public void receiveMessage(MessageDto message,
            @org.springframework.messaging.handler.annotation.Header(org.springframework.kafka.support.KafkaHeaders.RECEIVED_TIMESTAMP)
            long recordTimestamp) {
        java.time.Duration window = liveWindow == null ? java.time.Duration.ofMinutes(10) : liveWindow;
        if (System.currentTimeMillis() - recordTimestamp > window.toMillis()) {
            chatService.saveMessage(message);
            log.info("오래된 채팅 레코드 — 저장만 하고 뿌리지 않는다: roomId={}, messageId={}", message.getRoomId(), message.getId());
            return;
        }
        receiveMessage(message);
    }

    /** 지금 메시지 — 저장 · 뿌리기 · 상담 알림. */
    // [JA] 現在のメッセージ — 保存・配信・相談通知。
    public void receiveMessage(MessageDto message) {
        // 🔒 메시지 전체를 찍지 않는다 — 예전엔 본문 · 닉네임까지 INFO 로 남겼다(상담 대화 포함). 식별자만.
        // [JA] 🔒 メッセージ全体を出力しない — 以前は本文・ニックネームまで INFO で残していた(相談の会話を含む)。識別子だけ。
        log.info("채팅 방으로 메시지 전송: roomId={}, messageId={}", message.getRoomId(), message.getId());

        // 영속화는 **소비자 측**에서 한다(docs/16 B-24).
        // 예전에는 ChatService.saveMessage 를 호출하는 곳이 저장소 전체에 하나도 없어
        // 실시간 전달만 되고 **어떤 메시지도 저장되지 않았다** — 대화 이력 조회 API 와
        // 커서 페이지네이션이 영원히 빈 결과를 돌려주는 상태였다.
        //
        // 생산자 측(sendMessage)이 아니라 여기서 저장하는 이유:
        //   - 컨슈머 그룹 덕에 인스턴스를 늘려도 메시지당 정확히 한 번만 저장된다. 뿌리기는 모든 파드로(StompFanout — Redis pub/sub,
        //     docs/21 §2): 그룹은 메시지를 파드 하나에만 주므로, 그 파드의 구독자에게만 보내면 다른 파드에 붙은 사람은 못 받는다.
        //   - 저장 실패 시 Kafka 재시도/DLQ 경로를 그대로 탈 수 있다(브로드캐스트만 성공하고
        //     조용히 유실되는 상황을 만들지 않는다).
        //
        // 저장을 먼저 하고 브로드캐스트한다 — 구독자가 받은 메시지는 이미 이력에 있어야 한다.
        // [JA] 永続化は**コンシューマ側**で行う(docs/16 B-24)。
        //      以前は ChatService.saveMessage を呼ぶ箇所がリポジトリ全体に一つもなく、
        //      リアルタイム配信だけされて**どのメッセージも保存されていなかった** — 会話履歴照会 API と
        //      カーソルページネーションが永遠に空の結果を返す状態だった。
        //      プロデューサ側(sendMessage)ではなくここで保存する理由:
        //      - コンシューマグループのおかげでインスタンスを増やしてもメッセージごとにちょうど一回だけ保存される。配信はすべての Pod へ(StompFanout — Redis pub/sub、
        //      docs/21 §2): グループはメッセージを Pod 一つにしか渡さないので、その Pod の購読者にだけ送ると別の Pod に接続した人は受け取れない。
        //      - 保存失敗時に Kafka のリトライ/DLQ 経路をそのまま使える(ブロードキャストだけ成功して
        //      黙って失われる状況を作らない)。
        //      保存を先に行ってからブロードキャストする — 購読者が受け取ったメッセージはすでに履歴になければならない。
        chatService.saveMessage(message);

        // 메시지객체 내부의 채팅방번호를 참조하여, 해당 채팅방 구독자에게 메시지를 발송한다.
        // [JA] メッセージオブジェクト内のチャットルーム番号を参照し、該当チャットルームの購読者にメッセージを送信する。
        fanout.send("/sub/chat/room/" + message.getRoomId(), message);

        // 상담 메시지면 데스크에도 알린다 — 저장된 뒤라 상담원이 열면 그 메시지가 기록에 있다(docs/58 §9).
        // 연결·종료 안내(TEXT_ENTER·LEAVE)는 SupportService 가 CLAIMED·CLOSED 로 따로 알린다.
        // [JA] 相談メッセージならデスクにも知らせる — 保存された後なので相談員が開けばそのメッセージが記録にある(docs/58 §9)。
        //      接続・終了の案内(TEXT_ENTER・LEAVE)は SupportService が CLAIMED・CLOSED として別に知らせる。
        if (message.getType() == com.project.common.constants.chat.MessageType.TEXT_TALK) {
            Long threadId = com.project.chat_service.model.SupportThread.threadIdOf(message.getRoomId());
            if (threadId != null && threadId > 0) {
                deskNotifier.notify(threadId, com.project.chat_service.service.SupportDeskNotifier.Kind.MESSAGE,
                        message.getSenderId());
                // 상담원의 답이면 고객에게 알린다(보고 있지 않을 때만 — SupportReplyNotifier).
                // [JA] 相談員の回答なら顧客に知らせる(見ていないときだけ — SupportReplyNotifier)。
                replyNotifier.onMessage(message);
            }
        }
    }
}
