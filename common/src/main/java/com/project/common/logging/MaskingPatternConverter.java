package com.project.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

/**
 * 텍스트 패턴용 마스킹 — {@code %mask(...)} 로 감싼 구간을 {@link SensitiveDataMasker} 로 거른다.
 *
 * <pre>{@code
 * <conversionRule conversionWord="mask" class="com.project.common.logging.MaskingPatternConverter"/>
 * <pattern>... - %mask(%msg)%n%mask(%wEx)</pattern>
 * }</pre>
 *
 * <p>메시지뿐 아니라 <b>예외</b>도 감싼다. 예외 메시지에 이메일·토큰이 섞이는 일이 흔하다
 * (예: "User not found: a@b.com").
 */
public class MaskingPatternConverter extends CompositeConverter<ILoggingEvent> {

    @Override
    protected String transform(ILoggingEvent event, String in) {
        return SensitiveDataMasker.mask(in);
    }
}
