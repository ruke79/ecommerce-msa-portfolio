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
 *
 * <p>[JA] user-service が送るべきメールを<b>要求</b>する。実際の送信は notification-service が行う。
 * <p><b>以前の実装の問題:</b> JavaMailSender.send() を同期で呼んでいた。<b>リトライがなかった</b> — SMTP が一度揺れると MailException がそのまま上がり
 * パスワード再設定要求自体が 500 で失敗した。トークンは既に DB に保存された後なので<b>ユーザーにはリンクがないのにトークンだけ残る</b>状態になった。
 * <b>同期だった</b> — メールサーバが遅いとその分 HTTP リクエストが掴まれた。<b>SimpleMailMessage だった</b> — 平文一行だけでエンコーディングを指定できず
 * 韓国語の件名がクライアントによって化けた。登録確認メールは既に HTML+UTF-8 に直していたのにこちらだけ残り、<b>同じサービスが送る二つのメールの見た目が違った。</b>
 * <p>今は文言が notification-service の MailTemplates 一か所にあり、リトライ・DLQ はそちらのアウトボックスが担う(docs/29)。
 */
@Slf4j
@Service
public class EmailService {

    private final OutgoingMailProducer mailProducer;

    public EmailService(OutgoingMailProducer mailProducer) {
        this.mailProducer = mailProducer;
    }

    // 비밀번호 재설정 메일은 아웃박스로 옮겼다(UserOutboxHelper.recordPasswordResetMail → RegistrationMailRelay, 4단계 ⑤) — 예전에는 커밋 뒤
    // 메일 일꾼에서 바로 발행해 Kafka 가 흔들리면 사라졌다. 남은 것은 로그인 확인 코드 하나 — 로그인 흐름이 그 자리에서 보냈는지 알아야 하고
    // (못 보내면 다른 길), 늦게 도착한 코드는 쓸모가 없어서 바로 보낸다.
    // [JA] パスワード再設定メールはアウトボックスへ移した。残りはログイン確認コードだけ — ログインの流れがその場で送れたか知る必要があり、遅れて届くコードは役に立たないので直接送る。

    /**
     * 위험 기반 로그인의 확인 코드 메일을 요청한다(docs/68 §19).
     *
     * <p>[JA] パスワード再設定メールを要求する。
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
