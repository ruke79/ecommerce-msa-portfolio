package com.project.cart_service.client;

import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.project.common.exception.ErrorCode;

@Slf4j
@Component
public class FeignErrorDecoder implements ErrorDecoder {
    Environment env;

    @Autowired
    public FeignErrorDecoder(Environment env) {
        this.env = env;
    }

    @Override
    public Exception decode(String methodKey, Response response) {

        try {
            log.error("methodkey : {} status : {} body : {}", methodKey, response.status(),
                    Util.toString(response.body().asReader(Util.UTF_8)));
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        if (response.status() == 404 && methodKey.contains("find")) {
            throw ErrorCode.throwUserNotFound();
        }
        // 🐛 예전에는 다른 상태면 상류 본문을 담은 RuntimeException 을 돌려줘 그 내부 문구가 손님에게 나갔고(ClientMessage 가
        // RuntimeException 을 허용한다), 400 · 그 밖의 404 에는 null 을 돌려줘 Feign 이 던질 예외가 없어 NPE(500)가 났다.
        // 본문은 위 로그에만 남긴다.
        // [JA] 以前は上流の本文を含む RuntimeException を返して内部文言が客に出ており、400 などでは null を返して NPE(500)になっていた。
        return new com.project.common.exception.UpstreamCallException(methodKey, response.status());
    }
}
