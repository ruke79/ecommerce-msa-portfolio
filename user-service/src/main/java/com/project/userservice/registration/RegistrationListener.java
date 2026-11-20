package com.project.userservice.registration;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.project.common.message.dto.request.OutgoingMailRequest;
import com.project.userservice.message.producer.OutgoingMailProducer;
import com.project.userservice.model.User;
import com.project.userservice.service.UserService;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원가입 확인 메일 요청.
 *
 * <p><b>더 이상 여기서 메일을 보내지 않는다.</b> 확인 토큰을 만들고 {@code outgoing-mail} 토픽에
 * "이 사람에게 가입 확인 메일을 보내라"는 명령을 넣는다. 실제 발송·재시도·DLQ 는
 * notification-service 가 맡는다(docs/29).
 *
 * <p><b>왜 바꿨나.</b> 이전 구현은 {@code JavaMailSender} 를 직접 호출하고 실패하면 지수 백오프로
 * 세 번 재시도했다. 그 세 번이 모두 실패하면 <b>그 메일은 영영 사라졌다</b> — 남는 것은 ERROR
 * 로그 한 줄이고, 사용자는 계정을 활성화할 방법이 없었다. 메일 문안과 SMTP 설정이 이 파일 안에
 * 있어 다른 서비스가 메일을 보내려면 그대로 복사해야 했던 것도 문제였다.
 *
 * <p>유지한 것도 있다. <b>커밋 이후에 비동기로</b> 발행한다 —
 * <ul>
 *   <li>{@code AFTER_COMMIT}: 사용자 저장이 확정된 뒤에만 메일이 나간다. 그러지 않으면
 *       <b>가입은 롤백됐는데 확인 메일은 이미 도착한</b> 상태가 가능하다.</li>
 *   <li>{@code @Async}: 발행이 회원가입 HTTP 응답을 붙잡지 않는다. Kafka 발행은 SMTP 왕복보다
 *       훨씬 빠르지만 브로커가 흔들리면 최대 5초를 기다린다({@code MAX_BLOCK_MS}) — 그 5초를
 *       사용자가 기다릴 이유는 없다.</li>
 * </ul>
 *
 * <p>⚠️ <b>토큰은 여전히 로그에 남기지 않는다.</b> 확인 토큰은 그 자체로 계정을 활성화할 수 있는
 * 자격증명이다. DB 에는 SHA-256 해시만 저장하고, Kafka 메시지의 {@code toString} 도 링크를
 * 가린다({@link OutgoingMailRequest#toString()}).
 *
 * <p>[JA] 会員登録確認メールの要求。
 * もうここではメールを送らない。確認トークンを作り、{@code outgoing-mail} トピックに「この人に登録確認メールを送れ」
 * という命令を入れる。実際の送信・リトライ・DLQ は notification-service が担う(docs/29)。
 * なぜ変えたか: 以前の実装は {@code JavaMailSender} を直接呼び、失敗すると指数バックオフで 3 回リトライした。その
 * 3 回がすべて失敗するとそのメールは永久に消えた — 残るのは ERROR ログ 1 行で、ユーザーはアカウントを有効化する
 * 手段がなかった。メール文面と SMTP 設定がこのファイルの中にあり、他のサービスがメールを送るにはそのままコピーする
 * しかなかったことも問題だった。
 * 保ったものもある。コミット後に非同期で発行する — {@code AFTER_COMMIT}: ユーザーの保存が確定した後にだけメールが
 * 出る。そうしないと「登録はロールバックされたのに確認メールはもう届いた」状態がありうる。{@code @Async}: 発行が
 * 会員登録の HTTP 応答を引き止めない。Kafka 発行は SMTP の往復よりずっと速いが、ブローカーが揺れると最大 5 秒待つ
 * ({@code MAX_BLOCK_MS}) — その 5 秒をユーザーが待つ理由はない。
 * ⚠️ トークンは依然としてログに残さない。確認トークンはそれ自体でアカウントを有効化できる資格情報である。DB には
 * SHA-256 ハッシュだけを保存し、Kafka メッセージの {@code toString} もリンクを隠す({@link OutgoingMailRequest#toString()})。
 */
@Slf4j
@Component
public class RegistrationListener {

    @Autowired
    private UserService service;

    @Autowired
    private OutgoingMailProducer mailProducer;

    /**
     * 회원가입 확인 메일에 담을 백엔드 공개 주소.
     *
     * <p>예전에는 특정 배포 환경의 호스트명이 코드에 그대로 박혀 있었다. 그 결과
     * <b>어느 환경에서 실행하든</b> — 로컬이든 스테이징이든 — 확인 링크가 그 한 호스트를 가리켰고,
     * 그 호스트가 내려가면 신규 가입자는 계정을 활성화할 방법이 없었다.
     *
     * <p>[JA] 会員登録確認メールに入れるバックエンドの公開アドレス。
     * 以前は特定のデプロイ環境のホスト名がコードにそのまま埋め込まれていた。その結果どの環境で実行しても — ローカルでも
     * ステージングでも — 確認リンクがその一つのホストを指し、そのホストが落ちると新規登録者はアカウントを有効化する
     * 手段がなかった。
     */
    @Value("${app.public-url:https://localhost:8000}")
    private String publicUrl;

    /**
     * 커밋 이후에만 실행된다. 롤백되면 메일 요청은 나가지 않는다.
     *
     * <p>발행에 실패해도 예외를 올리지 않는다 — 사용자는 이미 가입에 성공했고, 메일이 오지 않으면
     * 재발송으로 복구한다. 여기서 예외가 올라가면 성공한 가입이 실패한 것처럼 보인다.
     *
     * <p>[JA] コミット後にのみ実行される。ロールバックされるとメール要求は出ない。
     * 発行に失敗しても例外を上げない — ユーザーはすでに登録に成功しており、メールが来なければ再送で復旧する。
     * ここで例外が上がると、成功した登録が失敗したように見える。
     */
    /**
     * ⚠️ {@code fallbackExecution = true} 가 <b>없으면 이 리스너는 한 번도 호출되지 않는다.</b>
     *
     * <p>{@code @TransactionalEventListener} 는 발행 시점에 <b>활성 트랜잭션이 있을 때만</b>
     * 동작한다. 없으면 Spring 은 DEBUG 로그 한 줄만 남기고 조용히 건너뛴다. 그런데 이 이벤트를
     * 발행하는 두 곳은 모두 트랜잭션 밖이다:
     * <ul>
     *   <li>{@code AuthController#registerUserAccount} — 직전의
     *       {@code registerNewUserAccount()} 가 반환하면서 이미 커밋됐다.</li>
     *   <li>{@code AuthController#resendVerificationEmail} — 쓰기 자체가 없다.</li>
     * </ul>
     * 그래서 가입 확인 메일이 <b>한 번도 나간 적이 없었다.</b> 실제 배포에서 확인했다:
     * {@code outgoing-mail} 토픽의 오프셋이 0 이고 {@code notification.mail_message} 도 비어
     * 있었다. 실패 로그조차 없다 — 리스너가 실행되지 않으니 성공도 실패도 남지 않는다.
     *
     * <p><b>왜 발행을 트랜잭션 안으로 옮기지 않았나.</b> 그쪽이 교과서적이지만 여기서는 맞지 않는다.
     * 재발송 경로에는 애초에 쓸 트랜잭션이 없어서, 애노테이션을 만족시키려고 트랜잭션을
     * 만들어야 한다. 가입 경로는 이미 커밋된 <b>뒤에</b> 발행하므로 "커밋 이후에만 보낸다"는
     * 보장이 이미 성립한다 — 서비스가 예외를 던지면 발행 줄에 도달하지도 않는다. 즉 옮겨서
     * 얻는 것이 없다. 이 플래그는 순수하게 덧붙이는 설정이라, 나중에 누가 발행을 트랜잭션
     * 안으로 옮겨도 {@code AFTER_COMMIT} 이 그대로 적용된다.
     *
     * <p>[JA] ⚠️ {@code fallbackExecution = true} がなければ、このリスナーは一度も呼ばれない。
     * {@code @TransactionalEventListener} は発行時点で有効なトランザクションがあるときだけ動く。なければ Spring は DEBUG
     * ログ 1 行だけ残して静かに飛ばす。ところがこのイベントを発行する 2 か所はどちらもトランザクションの外である:
     * {@code AuthController#registerUserAccount} — 直前の {@code registerNewUserAccount()} が戻るときにすでにコミット済み。
     * {@code AuthController#resendVerificationEmail} — 書き込み自体がない。
     * そのため登録確認メールは一度も出たことがなかった。実際のデプロイで確認した: {@code outgoing-mail} トピックの
     * オフセットが 0 で {@code notification.mail_message} も空だった。失敗ログすらない — リスナーが実行されないので
     * 成功も失敗も残らない。
     * なぜ発行をトランザクション内に移さなかったか: その方が教科書的だがここでは合わない。再送経路にはそもそも使う
     * トランザクションがなく、アノテーションを満たすためにトランザクションを作らなければならない。登録経路はすでに
     * コミットされた後に発行するので「コミット後にだけ送る」保証はすでに成り立つ — サービスが例外を投げれば発行の行に
     * 到達すらしない。つまり移して得るものがない。このフラグは純粋に付け足す設定なので、後で誰かが発行をトランザクション
     * 内に移しても {@code AFTER_COMMIT} がそのまま適用される。
     */
    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRegistrationComplete(final OnRegistrationCompleteEvent event) {
        final User user = event.getUser();
        final String rawToken = UUID.randomUUID().toString();

        // 토큰은 여기서 만들어 DB 에 해시로 저장한다. 원문은 메일 링크로만 쓰인다.
        // [JA] トークンはここで作り DB にハッシュで保存する。原文はメールのリンクにだけ使われる。
        service.createVerificationTokenForUser(user, rawToken);

        final String confirmationUrl = publicUrl.replaceAll("/+$", "")
                + "/user-service/registrationConfirm?token=" + rawToken;

        boolean published = mailProducer.publish(OutgoingMailRequest.builder()
                // ⚠️ 멱등 키에 토큰을 쓰지 않는다. 토큰을 쓰면 그 값이 키로서 로그와 DB 의
                //    source_event_id 컬럼에 그대로 남는다 — 해시로만 저장하는 의미가 사라진다.
                //    재발송은 매번 새 메일이 나가야 하므로 사용자 id + 발급 시각으로 만든다.
                // [JA] ⚠️ 冪等キーにトークンを使わない。トークンを使うとその値がキーとしてログと DB の
                //      source_event_id カラムにそのまま残る — ハッシュでだけ保存する意味がなくなる。
                //      再送は毎回新しいメールが出なければならないので、ユーザー id + 発行時刻で作る。
                .sourceEventId("registration-confirm:" + user.getUserId() + ":" + System.nanoTime())
                .toEmail(user.getEmail())
                .recipientUserId(user.getUserId())
                .kind("REGISTRATION_CONFIRM")
                .params(Map.of("confirmationUrl", confirmationUrl))
                .locale(localeTag(event))
                .build());

        if (published) {
            log.info("가입 확인 메일 요청 발행: userId={}", user.getUserId());
        } else {
            // 토큰은 DB 에 남아 있으므로 사용자는 재발송으로 복구할 수 있다.
            // ⚠️ 토큰 값은 남기지 않는다 — 누구인지만 남긴다.
            // [JA] トークンは DB に残っているので、ユーザーは再送で復旧できる。
            //      ⚠️ トークン値は残さない — 誰かだけを残す。
            log.error("가입 확인 메일 요청을 발행하지 못했습니다 — 재발송이 필요합니다: userId={}",
                    user.getUserId());
        }
    }

    /**
     * 문구 언어. {@code Locale#toLanguageTag} 는 {@code ja-JP} 형태를 낸다 —
     * notification-service 의 {@code MailTemplates#regionOf} 가 그 형태를 받는다.
     *
     * <p>[JA] 文言の言語。{@code Locale#toLanguageTag} は {@code ja-JP} の形を出す —
     * notification-service の {@code MailTemplates#regionOf} がその形を受け取る。
     */
    private String localeTag(final OnRegistrationCompleteEvent event) {
        return event.getLocale() == null ? null : event.getLocale().toLanguageTag();
    }
}
