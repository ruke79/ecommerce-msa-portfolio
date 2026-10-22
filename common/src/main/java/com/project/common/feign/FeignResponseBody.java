package com.project.common.feign;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

import feign.Capability;
import feign.Response;
import feign.codec.Decoder;

/**
 * 서비스 간 Feign 응답을 <b>끝까지 읽은 뒤</b> 해석한다 — 그래야 JDK 가 그 연결을 다시 쓴다(keep-alive).
 *
 * <h3>왜</h3>
 *
 * <p>Spring MVC 의 JSON 응답은 길이를 모르고 보내는 {@code Transfer-Encoding: chunked} 다. Feign 기본 클라이언트(JDK
 * {@code HttpURLConnection})는 chunked 응답을 <b>마지막 빈 청크까지 읽어야</b> 연결을 keep-alive 캐시로 돌려준다. 그런데
 * Jackson 은 JSON 의 마지막 닫는 중괄호에서 읽기를 멈추고 스트림을 닫는다 — 끝 청크가 남은 채 닫히면 JDK 는 연결을 버린다.
 * 그래서 <b>호출마다 새 TCP · mTLS 핸드셰이크</b>였다. 이 저장소에서 재 보니 chunked JSON 300 호출에 연결 300개, 끝까지 읽으면 1개.
 *
 * <p>2026-10-18 order 부하 측정(docs/83 §5): cart CPU 의 23% 가 catalog 로, order 의 Feign 시간의 2/3 가 cart · catalog 로 가는
 * 새 TLS 핸드셰이크였다. 쉬는 연결 수를 늘린 {@code HttpKeepAlive}(5 → 64)를 배포한 뒤에도 order 는 그대로였다 — 연결을
 * 캐시에 넣기 전에 버렸기 때문이다.
 *
 * <h3>어떻게</h3>
 *
 * <p>Feign {@link Capability} 로 모든 클라이언트의 {@link Decoder} 를 감싼다: 본문을 바이트로 다 읽고(이때 JDK 가 끝 청크를 만나
 * 연결을 캐시에 넣는다) 그 바이트로 원래 디코더를 부른다. 서비스 간 응답은 작다(장바구니 · 상품 몇 개). 반환형이
 * {@code feign.Response} 인 메서드는 Feign 이 디코더를 부르지 않으므로 그대로 흘려 읽을 수 있다. 오류 응답은 Feign 의
 * {@code ErrorDecoder} 가 이미 끝까지 읽는다.
 *
 * <p>[JA] サービス間の Feign 応答を<b>最後まで読んでから</b>解釈する — そうして初めて JDK がその接続を再利用する。
 * Spring MVC の JSON は chunked で、Jackson は最後の } で読むのをやめて閉じるため、終端チャンクが残ったまま閉じられ、
 * JDK は接続を捨てていた — 呼び出しごとに新しい mTLS ハンドシェイク(chunked JSON 300 呼び出しで接続 300、読み切れば 1)。
 */
public final class FeignResponseBody implements Capability {

    @Override
    public Decoder enrich(Decoder decoder) {
        return decoder instanceof FullyRead ? decoder : new FullyRead(decoder);
    }

    /** 본문을 끝까지 읽어 바이트로 바꾼 뒤 원래 디코더에 넘긴다. */
    static final class FullyRead implements Decoder {

        private final Decoder delegate;

        FullyRead(Decoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object decode(Response response, Type type) throws IOException {
            Response.Body body = response.body();
            if (body == null) {
                return delegate.decode(response, type);
            }
            byte[] bytes;
            try (InputStream in = body.asInputStream()) {
                bytes = in.readAllBytes();   // 끝 청크까지 — JDK 가 이 연결을 keep-alive 캐시에 넣는다
            }
            return delegate.decode(response.toBuilder().body(bytes).build(), type);
        }
    }
}
