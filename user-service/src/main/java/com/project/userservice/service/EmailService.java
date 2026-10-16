package com.project.userservice.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.project.common.message.dto.request.OutgoingMailRequest;
import com.project.userservice.message.producer.OutgoingMailProducer;

import lombok.extern.slf4j.Slf4j;

/**
 * user-service 가 보내야 하는 메일을 <b>요청</b>한다. 실제 발송은 notification-service 가 한다.
 *
 * <p><b>이전 구현의 문제.</b> {@code JavaMailSender.send()} 를 동기로 호출했다.
 *
 * <ul>
 *   <li><b>재시도가 없었다.</b> SMTP 가 한 번 흔들리면 {@code MailException} 이 그대로 올라가
 *       비밀번호 재설정 요청 자체가 500 으로 실패했다. 토큰은 이미 DB 에 저장된 뒤라,
 *       <b>사용자에게는 링크가 없는데 토큰만 남는</b> 상태가 됐다.</li>
 *   <li><b>동기였다.</b> 메일 서버가 느리면 그 시간만큼 HTTP 요청이 붙잡혔다.</li>
 *   <li><b>{@code SimpleMailMessage} 였다.</b> 평문 한 줄뿐이고 인코딩을 지정할 수 없어
 *       한국어 제목이 클라이언트에 따라 깨졌다. 가입 확인 메일은 이미 HTML+UTF-8 로 고쳤는데
 *       이쪽만 남아 있어, <b>같은 서비스가 보내는 두 메일의 모양이 서로 달랐다.</b></li>
 * </ul>
 *
 * <p>이제 문구는 notification-service 의 {@code MailTemplates} 한 곳에 있고, 재시도·DLQ 는
 * 그쪽 아웃박스가 맡는다(docs/29).
 */
@Slf4j
@Service
public class EmailService {

    private final OutgoingMailProducer mailProducer;

    /** 메일 요청을 내보내는 일꾼 — 가입 확인 메일과 같은 것(AsyncMailConfig). */
    private final java.util.concurrent.Executor mailExecutor;

    public EmailService(OutgoingMailProducer mailProducer,
            @org.springframework.beans.factory.annotation.Qualifier("mailExecutor") java.util.concurrent.Executor mailExecutor) {
        this.mailProducer = mailProducer;
        this.mailExecutor = mailExecutor;
    }

    /**
     * 트랜잭션 안이면 <b>커밋 뒤에</b>, 요청 스레드가 아니라 메일 일꾼에서 돌린다.
     *
     * <p>🐛 비밀번호 찾기가 DB 트랜잭션 안에서 Kafka 발행을 <b>동기로 기다렸다</b>(acks=all, 최대 5초). 공개 경로라 Kafka 가 느리면 요청마다
     * 커넥션을 수 초씩 쥐어 풀이 바닥났고, 가입된 주소만 그 시간이 걸려 <b>응답 시간으로 가입 여부</b>가 갈렸다("어느 주소든 같은 응답" 이
     * 목적인 엔드포인트다). 롤백될 토큰의 메일을 먼저 보내지도 않는다.
     */
    // [JA] トランザクション中ならコミット後に、リクエストスレッドではなくメール用ワーカーで実行する。以前はトランザクション内で Kafka を
    //      同期で待ち(最大 5 秒)、プールを枯らし、登録済みのアドレスだけ時間がかかって応答時間で登録の有無が分かった。
    void afterCommit(Runnable task) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            mailExecutor.execute(task);
                        }
                    });
        } else {
            mailExecutor.execute(task);
        }
    }

    /**
     * 비밀번호 재설정 메일을 요청한다.
     *
     * <p>발행에 실패해도 예외를 던지지 않는다. 던지면 호출자의 트랜잭션이 롤백되어 방금 저장한
     * 재설정 토큰까지 사라지는데, 그건 <b>사용자가 다시 요청하면 되는 상황</b>을
     * <b>요청 자체가 실패하는 상황</b>으로 바꾸는 것이다. 실패는 ERROR 로 남겨 운영자가 본다.
     *
     * @param userId 수신자 사용자 id. 문구 언어를 사용자 설정에서 고르는 데 쓴다
     */
    public void sendPasswordResetEmail(Long userId, String to, String resetUrl) {
        afterCommit(() -> publishPasswordReset(userId, to, resetUrl));
    }

    private void publishPasswordReset(Long userId, String to, String resetUrl) {
        boolean published = mailProducer.publish(OutgoingMailRequest.builder()
                // ⚠️ 멱등 키에 토큰을 넣지 않는다 — 그 값이 로그와 DB 컬럼에 그대로 남는다.
                //    재설정은 사용자가 요청할 때마다 새 메일이 나가야 하므로 시각을 섞는다.
                .sourceEventId("password-reset:" + userId + ":" + System.nanoTime())
                .toEmail(to)
                .recipientUserId(userId)
                .kind("PASSWORD_RESET")
                .params(Map.of("resetUrl", resetUrl))
                .build());

        if (!published) {
            // ⚠️ 링크는 남기지 않는다 — 재설정 링크는 그 자체로 비밀번호를 바꿀 수 있다.
            log.error("비밀번호 재설정 메일 요청을 발행하지 못했습니다: userId={}", userId);
        }
    }
    /**
     * 위험 기반 로그인의 확인 코드 메일을 요청한다(docs/68 §19).
     *
     * <p>코드는 재설정 링크와 같은 자격증명이다 — 로그에 남기지 않고, 메일 행의 파라미터는 발송 뒤 지워진다(notification-service MailDispatcher).
     * 발행에 실패하면 false — 호출자는 확인을 걸지 않고 다른 길(로그인 실패 안내)로 간다.
     */
    // [JA] リスクベースログインの確認コードメールを要求する。コードは資格情報なのでログに残さない。
    public boolean sendLoginChallengeEmail(Long userId, String to, String code) {
        boolean published = mailProducer.publish(OutgoingMailRequest.builder()
                .sourceEventId("login-challenge:" + userId + ":" + System.nanoTime())
                .toEmail(to)
                .recipientUserId(userId)
                .kind("LOGIN_CHALLENGE")
                .params(Map.of("code", code))
                .build());
        if (!published) {
            log.error("로그인 확인 코드 메일 요청을 발행하지 못했습니다: userId={}", userId);
        }
        return published;
    }
}
