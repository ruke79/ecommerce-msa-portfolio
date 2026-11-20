package com.project.chat_service.service;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.project.chat_service.message.fanout.StompFanout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 상담 데스크 실시간 알림 (docs/58 §9).
 *
 * <p>상담원이 구독하는 {@value #DESTINATION} 로 "무엇이 바뀌었다" 만 보낸다 — 스레드 번호와 종류뿐, <b>본문은 없다.</b>
 * 화면은 이것을 받고 목록을 다시 읽는다. 본문을 실으면 이 주소를 구독할 수 있는 사람이 곧 모든 상담의 대화를
 * 받는 사람이 되는데, 그 판정을 두 곳(REST 와 여기)에서 맞춰야 한다. 번호만 보내면 판정은 REST 한 곳이다.
 *
 * <p>구독은 상담원·관리자만 된다(StompHandler). 상담원이 어느 파드에 붙어 있어도 받도록 모든 파드로 퍼뜨린다(StompFanout —
 * Redis pub/sub, docs/21 §2).
 *
 * <p>[JA] 相談デスクのリアルタイム通知(docs/58 §9)。
 * <p>相談員が購読する {@value #DESTINATION} に「何が変わった」だけを送る — スレッド番号と種類だけで、<b>本文はない。</b>画面はこれを受けて一覧を読み直す。本文を載せるとこの宛先を購読できる人がそのまますべての相談の会話を受け取る人になり、その判定を二か所(REST とここ)で揃えなければならない。番号だけ送れば判定は REST 一か所である。
 * <p>購読できるのは相談員・管理者だけである(StompHandler)。相談員がどの Pod に接続していても受け取れるようすべての Pod に広げる(StompFanout — Redis pub/sub, docs/21 §2)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportDeskNotifier {

    public static final String DESTINATION = "/sub/support/desk";

    /** 알림의 종류. */
    // [JA] 通知の種類。
    public enum Kind {
        /** 고객이 새로 문의했다(대기 목록에 하나 늘었다). */
        // [JA] 顧客が新しく問い合わせた(待機リストに一つ増えた)。
        NEW_THREAD,
        /** 상담에 메시지가 왔다. */
        // [JA] 相談にメッセージが来た。
        MESSAGE,
        /** 누가 맡았다(대기 목록에서 빠졌다). */
        // [JA] 誰かが担当した(待機リストから抜けた)。
        CLAIMED,
        /** 끝났다. */
        // [JA] 終わった。
        CLOSED,
        /** 상담원이 반납했다(대기 목록에 다시 올라왔다). */
        // [JA] 相談員が返却した(待機リストに再び上がった)。
        RELEASED
    }

    /** 데스크로 가는 알림 — 번호와 종류뿐. id 들은 문자열(TSID 가 JS Number 에서 뭉개지지 않게). */
    // [JA] デスクへ行く通知 — 番号と種類だけ。id 類は文字列(TSID が JS Number で潰れないように)。
    public record DeskEvent(
            @JsonSerialize(using = ToStringSerializer.class) Long threadId,
            Kind kind,
            @JsonSerialize(using = ToStringSerializer.class) Long senderId,
            Instant at) {
    }

    private final StompFanout fanout;

    public void notify(Long threadId, Kind kind, Long senderId) {
        if (threadId == null) {
            return;
        }
        try {
            fanout.send(DESTINATION, new DeskEvent(threadId, kind, senderId, Instant.now()));
        } catch (RuntimeException e) {
            // 알림은 부가 기능이다 — 실패해도 상담 자체(저장·배정·종료)를 되돌리지 않는다. 데스크는 주기적으로도 다시 읽는다.
            // [JA] 通知は付加機能である — 失敗しても相談自体(保存・割り当て・終了)を取り消さない。デスクは定期的にも読み直す。
            log.warn("상담 데스크 알림 실패: threadId={} kind={} ({})", threadId, kind, e.toString());
        }
    }
}
