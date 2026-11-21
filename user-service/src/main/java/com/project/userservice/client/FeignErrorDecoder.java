package com.project.userservice.client;


import feign.Response;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.project.common.exception.ErrorCode;



@Component
public class FeignErrorDecoder implements ErrorDecoder {
    Environment env;
    @Autowired
    public FeignErrorDecoder(Environment env) {
        this.env = env;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response.status() == 404 && methodKey.contains("find")) {
            throw ErrorCode.throwUserNotFound();
        }
        // 🐛 예전에는 400 · (find 가 아닌) 404 에 null 을 돌려줘 Feign 이 `throw null` — 부르는 쪽은 NullPointerException 을 받았고,
        //    그 밖의 상태는 검사 예외 new Exception(…) 이라 UndeclaredThrowableException 이었다(docs/101 §4). 이제 cart · coupon 과 같이
        //    상태를 담은 UpstreamCallException(RuntimeException) — 부르는 쪽의 catch (RuntimeException) 는 그대로 받고, 서킷 브레이커는
        //    5xx · 429 만 실패로 센다. 상류 본문은 싣지 않는다(손님에게 나갈 수 있다).
        // [JA] 以前は 400 などで null を返して Feign が throw null(NPE)、その他は検査例外だった。今は状態を持つ UpstreamCallException。
        return new com.project.common.exception.UpstreamCallException(methodKey, response.status());
    }
}
