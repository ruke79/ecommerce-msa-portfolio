package com.project.notification_service.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SMS 2단계 인증(및 번호 등록) 코드 1건.
 *
 * <p>이 엔티티의 설계는 대부분 <b>공격을 막기 위한 것</b>이다. 인증번호는 6자리라 경우의 수가
 * 100만뿐이고, 아무 제약이 없으면 자동화된 시도로 몇 분 안에 뚫린다.
 *
 * <ul>
 *   <li><b>코드를 평문으로 저장하지 않는다.</b> DB 를 읽을 수 있는 사람이 남의 인증을 통과할 수
 *       있으면 2단계 인증의 의미가 없다. 백업·로그·덤프까지 생각하면 더욱 그렇다.
 *       해시만 저장하고 검증은 해시 비교로 한다.</li>
 *   <li><b>시도 횟수를 센다.</b> 5회 틀리면 그 코드는 죽는다. 이게 없으면 100만 번 시도로
 *       반드시 뚫린다.</li>
 *   <li><b>만료가 있다.</b> 5분. 유출된 코드의 유효 시간을 줄인다.</li>
 *   <li><b>1회용이다.</b> 성공하면 소비 표시를 해서 재사용할 수 없게 한다 —
 *       재전송 공격(replay) 방어.</li>
 * </ul>
 *
 * <p>발급 자체의 속도 제한(같은 번호로 도배 발송)은 Redis 에서 처리한다. DB 카운터로 하면
 * 레플리카가 늘었을 때 경쟁 조건이 생기고, 무엇보다 <b>남의 번호로 문자 폭탄을 보내는 공격</b>은
 * 요금이 우리 쪽으로 청구되므로 빠르고 확실하게 막아야 한다(VerificationService).
 *
 * <p>[JA] SMS 二段階認証(および番号登録)のコード 1 件。
 * このエンティティの設計はほとんどが<b>攻撃を防ぐためのもの</b>である。認証番号は 6 桁なので場合の数が 100 万しかなく、
 * 何の制約もなければ自動化された試行で数分のうちに破られる。
 * ・<b>コードを平文で保存しない。</b> DB を読める人が他人の認証を通過できれば二段階認証の意味がない。バックアップ・ログ・ダンプまで考えればなおさらである。
 *   ハッシュだけを保存し、検証はハッシュの比較で行う。
 * ・<b>試行回数を数える。</b> 5 回間違えるとそのコードは死ぬ。これがないと 100 万回の試行で必ず破られる。
 * ・<b>期限がある。</b> 5 分。漏れたコードの有効時間を縮める。
 * ・<b>1 回限りである。</b> 成功したら消費済みにして再利用できなくする — リプレイ攻撃の防御。
 * 発行自体のレート制限(同じ番号への乱発)は Redis で処理する。DB のカウンターで行うとレプリカが増えたときに競合状態が生じ、
 * 何より<b>他人の番号に SMS 爆弾を送る攻撃</b>は料金が我々に請求されるので速く確実に防ぐ必要がある(VerificationService)。
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "phone_verification", schema = "notification",
       indexes = {
           @Index(name = "idx_phone_verification_lookup", columnList = "phone_e164, consumed, expires_at")
       })
public class PhoneVerification {

    /** 6자리 코드가 유효한 시간. 짧을수록 안전하지만 사용자가 문자를 확인할 시간은 줘야 한다. */
    // [JA] 6 桁のコードが有効な時間。短いほど安全だが、ユーザーが SMS を確認する時間は与えなければならない。
    public static final int TTL_MINUTES = 5;

    /** 이 횟수를 틀리면 코드를 폐기한다. */
    // [JA] この回数間違えるとコードを破棄する。
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "verification_id")
    private Long verificationId;

    /** 등록·인증 대상 번호 (E.164). */
    // [JA] 登録・認証対象の番号(E.164)。
    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    /**
     * 누가 요청했는지. 로그인 2FA 면 사용자 id, 최초 번호 등록이면 null 일 수 있다.
     *
     * <p>[JA] 誰が要求したか。ログイン 2FA ならユーザー id、最初の番号登録なら null の場合がある。
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 코드의 해시. <b>평문 코드는 어디에도 저장하지 않는다</b> — 문자로 나가고 나면
     * 우리 쪽에는 해시만 남는다.
     *
     * <p>[JA] コードのハッシュ。<b>平文のコードはどこにも保存しない</b> — SMS で出ていった後は我々の側にはハッシュだけが残る。
     */
    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "consumed", nullable = false)
    private boolean consumed;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 무엇을 위한 코드인가(V12). 번호 등록 코드와 로그인 2단계 인증 코드는 서로의 검증에 쓰이지 않는다 — 같은 번호로 둘이 나가도
     * "가장 최근 코드" 를 용도마다 따로 찾는다.
     * [JA] 何のためのコードか。番号登録とログイン二段階認証のコードは互いの検証に使えない。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 16)
    private Purpose purpose;

    public enum Purpose {
        /** 번호 등록 · 변경(보안 화면의 휴대폰 인증). */
        // [JA] 番号の登録 · 変更(セキュリティ画面の携帯電話認証)。
        PHONE,
        /** 로그인 2단계 인증 · 그 켜기/끄기 — user-service 가 내부 호출로만 요청한다. */
        // [JA] ログイン二段階認証 · その有効化/無効化 — user-service が内部呼び出しでのみ要求する。
        LOGIN_2FA
    }

    public PhoneVerification(String phoneE164, Long userId, String codeHash) {
        this(phoneE164, userId, codeHash, Purpose.PHONE);
    }

    public PhoneVerification(String phoneE164, Long userId, String codeHash, Purpose purpose) {
        this.purpose = purpose;
        this.phoneE164 = phoneE164;
        this.userId = userId;
        this.codeHash = codeHash;
        this.attempts = 0;
        this.consumed = false;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        this.expiresAt = this.createdAt.plusMinutes(TTL_MINUTES);
    }

    public boolean isExpired() {
        return LocalDateTime.now(ZoneOffset.UTC).isAfter(expiresAt);
    }

    public boolean isExhausted() {
        return attempts >= MAX_ATTEMPTS;
    }

    /** 검증에 쓸 수 있는 상태인가. 만료·소진·소비 중 하나라도 해당되면 못 쓴다. */
    // [JA] 検証に使える状態か。期限切れ・使い切り・消費済みのどれか一つでも当てはまれば使えない。
    public boolean isUsable() {
        return !consumed && !isExpired() && !isExhausted();
    }

    public void recordFailedAttempt() {
        this.attempts += 1;
    }

    public void consume() {
        this.consumed = true;
    }
}
