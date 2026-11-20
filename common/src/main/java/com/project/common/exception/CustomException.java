package com.project.common.exception;

import lombok.Getter;

/**
 * 🐛 <b>{@code getMessage()} 가 언제나 {@code null} 이었다.</b>
 *
 * <p>예전에는 이랬다:
 *
 * <pre>{@code
 * @Getter
 * @AllArgsConstructor
 * public class CustomException extends RuntimeException {
 *     private final ErrorCode errorCode;
 * }
 * }</pre>
 *
 * <p>Lombok 의 {@code @AllArgsConstructor} 는 필드만 대입하고 {@code super(message)} 를
 * <b>부르지 않는다.</b> 그래서 아무리 자세한 {@link ErrorCode#getDetail()} 을 담아도
 * 예외 자체는 메시지가 없었다. {@code e.getMessage()} 를 응답 본문에 넣는 곳이 여럿이라
 * 화면에는 {@code {"message": null}} 이 떴고, 로그의 스택트레이스 첫 줄도
 * {@code com.project.common.exception.CustomException} 하나뿐이었다 — 무엇이 왜
 * 실패했는지 어디에도 없었다.
 *
 * <p>{@code detail} 을 {@code super} 로 올린다. {@code errorCode} 는 그대로 남으므로
 * 코드로 분기하던 곳은 영향이 없다.
 *
 * <p>[JA] 🐛 <b>{@code getMessage()} が常に {@code null} だった。</b>
 * 以前は {@code @Getter @AllArgsConstructor} で {@code errorCode} フィールドだけを持つクラスだった。Lombok の {@code @AllArgsConstructor} はフィールドを代入するだけで
 * {@code super(message)} を<b>呼ばない。</b>そのためいくら詳しい {@link ErrorCode#getDetail()} を入れても例外自体にはメッセージがなかった。{@code e.getMessage()} を
 * 応答本文に入れる箇所が複数あり、画面には {@code {"message": null}} が出て、ログのスタックトレースの先頭行も {@code com.project.common.exception.CustomException}
 * だけだった — 何がなぜ失敗したのかがどこにもなかった。
 * {@code detail} を {@code super} に渡す。{@code errorCode} はそのまま残るので、コードで分岐していた箇所には影響がない。
 */
@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;

    public CustomException(ErrorCode errorCode) {
        super(errorCode == null ? null : errorCode.getDetail());
        this.errorCode = errorCode;
    }
}
