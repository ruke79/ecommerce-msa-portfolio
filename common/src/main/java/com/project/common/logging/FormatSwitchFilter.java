package com.project.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * 설정값 하나로 appender 를 켜고 끈다 — 공통 logback 설정의 스위치.
 *
 * <pre>{@code
 * <filter class="com.project.common.logging.FormatSwitchFilter">
 *     <actual>${CONSOLE_FORMAT}</actual>
 *     <expected>json</expected>
 * </filter>
 * }</pre>
 *
 * <p>{@code actual} 이 {@code expected} 와 같으면(대소문자 무시) 통과시키고, 다르면 모든 줄을
 * 버린다. logback 의 {@code <if>} 는 Janino 를 더 들여와야 하고, {@code <springProfile>} 은 이 저장소에서
 * 쓸 수 없다 — 모든 환경이 {@code local} 프로파일로 돈다(k8s 포함). 그래서 프로파일이 아니라
 * 환경변수로 고른다.
 *
 * <p>판정은 시작할 때 한 번 한다. 줄마다 문자열을 비교하지 않는다.
 */
public class FormatSwitchFilter extends Filter<ILoggingEvent> {

    private String actual = "";
    private String expected = "";
    private boolean enabled;

    public void setActual(String actual) {
        this.actual = actual == null ? "" : actual.trim();
    }

    public void setExpected(String expected) {
        this.expected = expected == null ? "" : expected.trim();
    }

    @Override
    public void start() {
        enabled = !expected.isEmpty() && expected.equalsIgnoreCase(actual);
        super.start();
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        return enabled ? FilterReply.NEUTRAL : FilterReply.DENY;
    }
}
