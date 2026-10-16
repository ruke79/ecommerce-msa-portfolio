package com.project.common.exception;

/**
 * 이 예외의 문구는 <b>손님에게 내지 않는다</b> — 운영자용이다. {@link ClientMessage} 가 패키지 규칙({@code com.project.*})보다 먼저 본다.
 *
 * <p>예: 다른 서비스를 부르다 실패한 것({@link UpstreamCallException}) — 문구에 상류 응답의 상태 · 본문이 들어갈 수 있다.
 *
 * <p>[JA] この例外の文言は<b>客に出さない</b> — オペレーター向けである。{@link ClientMessage} がパッケージ規則より先に見る。
 */
public interface NotForClients {
}
