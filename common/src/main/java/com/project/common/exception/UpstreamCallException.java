package com.project.common.exception;

/**
 * 다른 서비스를 부르다 실패했다(Feign 의 오류 응답). 문구는 운영자용이라 응답에는 일반 문구가 나간다({@link NotForClients}).
 *
 * <p>🐛 예전 cart 의 Feign 디코더는 {@code new RuntimeException("API call failed with status 503: " + 상류 본문)} 을 던졌고, 그 예외는
 * 응답 문구 허용 목록({@link ClientMessage} — 정확히 {@code RuntimeException})을 통과해 상류의 내부 문구가 손님 화면에 그대로 나갔다.
 * 상류 본문은 담지 않는다 — 디코더가 로그에만 남긴다.
 *
 * <p>[JA] 他サービスの呼び出しに失敗した。文言はオペレーター向けなので、レスポンスには一般的な文言が出る。以前は上流の本文を含む
 * {@code RuntimeException} を投げ、許可リストを通って上流の内部文言が客の画面に出ていた。
 */
public class UpstreamCallException extends RuntimeException implements NotForClients {

    private final int status;

    public UpstreamCallException(String methodKey, int status) {
        super("상류 호출 실패: " + methodKey + " → HTTP " + status);
        this.status = status;
    }

    /** 상류가 돌려준 HTTP 상태. ／ [JA] 上流が返した HTTP ステータス。 */
    public int getStatus() {
        return status;
    }
}
