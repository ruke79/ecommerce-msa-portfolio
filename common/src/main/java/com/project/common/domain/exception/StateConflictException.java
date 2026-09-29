package com.project.common.domain.exception;

/**
 * 요청은 맞지만 <b>지금 상태에서는</b> 할 수 없다 — 응답은 409, 문구는 사람에게 그대로 보인다(docs/57 §15).
 *
 * <p>예전에는 이런 자리에 {@link IllegalStateException} 을 썼다. 그런데 같은 예외를 "SHA-256 을 쓸 수 없다", "시퀀스가 10번
 * 충돌했다" 같은 <b>운영자용</b> 문구에도 썼고, 응답 문구 허용 목록({@code ClientMessage})은 둘을 가르지 못해 운영자용 문구까지
 * 손님에게 보였다. 사람에게 보일 상태 충돌은 이 예외로, 운영자용은 {@code IllegalStateException} 으로 — 그리고 허용 목록에서
 * {@code IllegalStateException} 을 뺐다.
 *
 * <p>[JA] リクエスト自体は正しいが<b>今の状態では</b>実行できない — レスポンスは 409、文言は人にそのまま見せる(docs/57 §15)。
 *
 * <p>以前はこうした箇所に {@link IllegalStateException} を使っていた。ところが同じ例外を「SHA-256 が使えない」「シーケンスが 10 回
 * 衝突した」といった<b>オペレーター向け</b>の文言にも使っており、レスポンス文言の許可リスト({@code ClientMessage})は両者を区別できず、
 * オペレーター向けの文言まで顧客に見えていた。人に見せる状態の衝突はこの例外で、オペレーター向けは {@code IllegalStateException} で —
 * そして許可リストから {@code IllegalStateException} を外した。
 */
public class StateConflictException extends DomainException {

    public StateConflictException(String message) {
        super(message);
    }
}
