package com.project.chat_service.client;


import feign.Response;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.project.chat_service.common.exception.handler.ErrorCode;

@Component
public class FeignErrorDecoder implements ErrorDecoder {
    Environment env;
    @Autowired
    public FeignErrorDecoder(Environment env) {
        this.env = env;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        switch (response.status()) {
            case 400:
                break;
            case 404:
                if (methodKey.contains("find")) {
                    throw ErrorCode.throwUserrNotFound();
                }                
                break;
            default:
                return new Exception(response.reason());
        }
        return null;
    }
}
