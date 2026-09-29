package com.project.common.kafka;

import java.util.function.Function;

import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 트랜잭션 프로듀서로 <b>어디서 불려도</b> 보낼 수 있게 한다.
 *
 * <h3>🐛 환불하면 재고가 돌아가지 않았다</h3>
 *
 * <p>order-service 의 사가 보상 템플릿(재고·쿠폰 롤백·장바구니 롤백·장바구니 비우기)은
 * {@code transactional.id} 를 가진 <b>트랜잭션 프로듀서</b>다(docs/38 §1.17). 트랜잭션 프로듀서는
 * 트랜잭션 안에서만 보낼 수 있다.
 *
 * <ul>
 *   <li>결제 응답 리스너 안에서는 DB 트랜잭션이 열려 있어 Kafka 트랜잭션이 거기 붙는다 — 성공한다.</li>
 *   <li>환불·반품 입고·무통장 취소처럼 <b>HTTP 요청에서 DB 트랜잭션이 끝난 뒤</b> 보내는 경로에는
 *       트랜잭션이 없다 — {@code IllegalStateException: No transaction is in process} 로 실패했다.</li>
 * </ul>
 *
 * <p>운영 로그에 "재고 복원 발행 실패 — 수동 확인 필요" 로 남았다. 재고도, 전액 환불의 쿠폰도
 * 돌아가지 않았다.
 *
 * <p>여기서 가른다: 트랜잭션 프로듀서인데 진행 중인 트랜잭션이 없으면 <b>이 한 번의 발송만 감싼
 * 트랜잭션</b>({@code executeInTransaction})으로 보내고, 있으면 그 트랜잭션에 붙여 보낸다(예전과 같다).
 * 트랜잭션이 아닌 템플릿은 그냥 보낸다.
 *
 * <p>[JA] トランザクショナルプロデューサーで<b>どこから呼ばれても</b>送信できるようにする。
 *
 * <h3>🐛 返金しても在庫が戻らなかった</h3>
 *
 * <p>order-service の Saga 補償テンプレート(在庫・クーポンのロールバック・カートのロールバック・カートを空にする)は
 * {@code transactional.id} を持つ<b>トランザクショナルプロデューサー</b>である(docs/38 §1.17)。トランザクショナルプロデューサーは
 * トランザクション内でしか送信できない。
 *
 * <ul>
 *   <li>決済レスポンスのリスナー内では DB トランザクションが開いており、Kafka トランザクションがそこに乗る — 成功する。</li>
 *   <li>返金・返品入庫・銀行振込キャンセルのように<b>HTTP リクエストで DB トランザクションが終わった後に</b>送る経路には
 *       トランザクションがない — {@code IllegalStateException: No transaction is in process} で失敗していた。</li>
 * </ul>
 *
 * <p>本番ログには「재고 복원 발행 실패 — 수동 확인 필요」(在庫復元の発行失敗 — 手動確認が必要)と残っていた。在庫も、全額返金時のクーポンも
 * 戻らなかった。
 *
 * <p>ここで分岐する: トランザクショナルプロデューサーなのに進行中のトランザクションがなければ<b>この一回の送信だけを包む
 * トランザクション</b>({@code executeInTransaction})で送り、あればそのトランザクションに乗せて送る(従来どおり)。
 * トランザクショナルでないテンプレートはそのまま送る。
 */
public final class TransactionalSends {

    private TransactionalSends() {
    }

    public static <K, V, R> R run(KafkaTemplate<K, V> template, Function<KafkaOperations<K, V>, R> send) {
        if (template.isTransactional() && !template.inTransaction()) {
            return template.executeInTransaction(send::apply);
        }
        return send.apply(template);
    }
}
