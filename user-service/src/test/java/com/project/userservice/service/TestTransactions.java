package com.project.userservice.service;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 단위 테스트용 트랜잭션 — 실제로 시작 · 커밋을 하고(동기화 · {@code isActualTransactionActive} 가 진짜처럼 움직인다) 몇 번 했는지 센다.
 * {@code @InjectMocks} 로 만든 서비스에 {@code @Spy TransactionTemplate tx = TestTransactions.template()} 로 넣는다.
 *
 * <p>[JA] 単体テスト用のトランザクション — 実際に開始 · コミットし、回数を数える。
 */
public final class TestTransactions {

    private TestTransactions() {
    }

    public static TransactionTemplate template() {
        return new TransactionTemplate(new Recording());
    }

    public static TransactionTemplate template(Recording manager) {
        return new TransactionTemplate(manager);
    }

    public static final class Recording extends AbstractPlatformTransactionManager {
        public int begins;
        public int commits;
        public int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
