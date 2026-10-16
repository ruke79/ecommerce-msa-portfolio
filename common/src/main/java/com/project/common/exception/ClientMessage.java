package com.project.common.exception;

import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 예외를 응답 본문의 문구로 바꾼다 — <b>우리가 사람에게 보이려고 쓴 문구만</b> 내보낸다 (docs/57 §1).
 *
 * <h3>왜</h3>
 *
 * <p>컨트롤러 약 60곳이 {@code catch (RuntimeException e)} 로 받아 {@code e.getMessage()} 를 그대로 본문에
 * 실었고, 공통 예외 처리기의 500 도 {@code ex.getLocalizedMessage()} 를 실었다. 서비스가 던진
 * "사용 기간이 지난 쿠폰입니다." 같은 문구는 그래서 화면에 닿았다 — 그런데 같은 {@code catch} 가 JDBC·JPA·
 * Feign·Redis 의 예외도 받는다. 그 메시지에는 SQL, 테이블·컬럼·제약 이름, 입력값, 내부 호스트가 들어 있다.
 * JVM 의 NPE 문구("Cannot invoke ... because ... is null")는 코드 구조를 그대로 보여 준다(docs/47).
 *
 * <h3>규칙 — 허용 목록</h3>
 *
 * <ul>
 *   <li>{@code com.project.} 아래의 예외(도메인 예외)와, 우리 코드가 문구를 담아 던지는 표준 예외 셋
 *       ({@link RuntimeException}·{@link IllegalArgumentException}·{@link NoSuchElementException}) — <b>정확히 그
 *       클래스</b>일 때만 문구를 내보낸다. 하위 클래스는 아니다: 라이브러리 예외 대부분이 이 셋의 하위 클래스다.</li>
 *   <li>{@link IllegalStateException} 은 <b>뺐다</b>(docs/57 §15) — 우리 코드가 그것을 "SHA-256 을 쓸 수 없다", "시퀀스가 10번
 *       충돌했다", "ROLE_USER 시드가 없다" 같은 운영자용 문구에 쓴다. 사람에게 보일 상태 충돌은
 *       {@code StateConflictException}(도메인 예외)으로 던진다.</li>
 *   <li>다른 예외를 감싸기만 한 것({@code new RuntimeException(e)} — 메시지가 원인의 {@code toString()})은
 *       감싼 쪽이 허용 목록이어도 <b>원인의 문구</b>이므로 내보내지 않는다.</li>
 *   <li>나머지는 {@code fallback}. 원래 문구는 로그에 남는다 — 부르는 쪽이 로그를 쓴다.</li>
 * </ul>
 *
 * <p>거부 목록(JDBC·JPA·…를 막는다)으로 하지 않은 이유: 목록에 없는 라이브러리가 하나 추가되면 새는
 * 쪽으로 틀린다. 허용 목록은 틀려도 일반 문구가 나갈 뿐이다.
 *
 * <p>[JA] 例外をレスポンスボディの文言に変換する — <b>我々が人に見せるために書いた文言だけ</b>を出す (docs/57 §1)。
 *
 * <h3>なぜ</h3>
 *
 * <p>約 60 か所のコントローラーが {@code catch (RuntimeException e)} で受けて {@code e.getMessage()} をそのままボディに
 * 載せており、共通例外ハンドラーの 500 も {@code ex.getLocalizedMessage()} を載せていた。サービスが投げた
 * 「사용 기간이 지난 쿠폰입니다.」(有効期限切れのクーポンです。)のような文言はそのおかげで画面に届いていた — しかし同じ {@code catch} が
 * JDBC・JPA・Feign・Redis の例外も受け取る。そのメッセージには SQL、テーブル・カラム・制約名、入力値、内部ホストが含まれている。
 * JVM の NPE の文言("Cannot invoke ... because ... is null")はコード構造をそのまま晒してしまう(docs/47)。
 *
 * <h3>ルール — 許可リスト</h3>
 *
 * <ul>
 *   <li>{@code com.project.} 配下の例外(ドメイン例外)と、我々のコードが文言を込めて投げる標準例外三つ
 *       ({@link RuntimeException}・{@link IllegalArgumentException}・{@link NoSuchElementException}) — <b>まさにその
 *       クラス</b>である場合だけ文言を出す。サブクラスは対象外である: ライブラリ例外の大半がこの三つのサブクラスだからである。</li>
 *   <li>{@link IllegalStateException} は<b>外した</b>(docs/57 §15) — 我々のコードはそれを「SHA-256 が使えない」「シーケンスが 10 回
 *       衝突した」「ROLE_USER のシードがない」といったオペレーター向けの文言に使っている。人に見せる状態の衝突は
 *       {@code StateConflictException}(ドメイン例外)で投げる。</li>
 *   <li>別の例外を包んだだけのもの({@code new RuntimeException(e)} — メッセージが原因の {@code toString()})は、
 *       包んだ側が許可リストに入っていても<b>原因の文言</b>なので出さない。</li>
 *   <li>それ以外は {@code fallback}。元の文言はログに残る — 呼び出し側がログを書く。</li>
 * </ul>
 *
 * <p>拒否リスト(JDBC・JPA・… を塞ぐ)にしなかった理由: リストにないライブラリが一つ追加されると、漏れる
 * 側に倒れてしまう。許可リストなら間違えても一般的な文言が出るだけである。
 */
public final class ClientMessage {

    /** 문구를 내보낼 수 없을 때의 기본 문구. */
    // [JA] 文言を出せないときのデフォルト文言。
    public static final String DEFAULT = "요청을 처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.";

    private static final Set<Class<?>> PLAIN = Set.of(
            RuntimeException.class,
            IllegalArgumentException.class,
            NoSuchElementException.class);

    private static final String OWN_PACKAGE = "com.project.";

    private ClientMessage() {
    }

    /** {@link #of(Throwable, String)} 에 {@link #DEFAULT}. */
    // [JA] {@link #of(Throwable, String)} に {@link #DEFAULT} を渡したもの。
    public static String of(Throwable e) {
        return of(e, DEFAULT);
    }

    /**
     * @param fallback 내보낼 수 없을 때 대신 쓸 문구
     *                 [JA] 出せないときに代わりに使う文言
     * @return 사람에게 보여도 되는 문구
     *         [JA] 人に見せてもよい文言
     */
    public static String of(Throwable e, String fallback) {
        return isSafe(e) ? e.getMessage() : fallback;
    }

    /** 이 예외의 문구를 응답에 실어도 되는가. */
    // [JA] この例外の文言をレスポンスに載せてよいか。
    public static boolean isSafe(Throwable e) {
        if (e == null) {
            return false;
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        Throwable cause = e.getCause();
        if (cause != null && message.equals(cause.toString())) {
            return false;   // 감싸기만 했다 — 문구는 원인의 것이다 ／ [JA] 包んだだけである — 文言は原因のものである
        }
        if (e instanceof NotForClients) {
            return false;   // 운영자용이라고 표시한 예외 — 패키지 규칙보다 먼저 ／ [JA] オペレーター向けと表示した例外
        }
        Class<?> type = e.getClass();
        return PLAIN.contains(type) || type.getName().startsWith(OWN_PACKAGE);
    }
}
