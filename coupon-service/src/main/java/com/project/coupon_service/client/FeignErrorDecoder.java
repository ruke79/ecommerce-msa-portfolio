package com.project.coupon_service.client;


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
        // 🐛 예전에는 400 · find 가 아닌 호출의 404(getCart · getProductsInfo · getEventCouponOffer)에 null 을 돌려줬다 — Feign 이
        //    던질 예외가 없어 NPE 가 났고, 로그에는 원래 상태 대신 NPE 가 남았다(CP9). 상태와 호출을 실은 예외를 돌려준다(cart 와 같다).
        //    본문은 싣지도 남기지도 않는다 — 상류의 내부 문구 · 개인정보가 섞일 수 있다.
        // [JA] 以前は 400 などで null を返し NPE になっていた。状態と呼び出しを載せた例外を返す。
        return new com.project.common.exception.UpstreamCallException(methodKey, response.status());
    }
}

