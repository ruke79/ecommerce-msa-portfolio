package com.project.userservice.metrics;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 서비스 메서드마다의 실행 시간 로그 — <b>기본 끔</b>, 진단할 때만 {@code EXECUTION_TIME_LOG_ENABLED=true} 로 켠다(B-105).
 *
 * <p>🐛 2026-10-23 CPU 프로파일(capacity-steps 300 단계, docs/83 §5 · ops/58): 켜져 있던 이 애스펙트가 서비스 패키지의 모든 메서드
 * 호출마다 INFO 한 줄을 써(요청 하나에 여러 줄) catalog CPU 의 7.9% 였다. 실행 시간은 추적(B-72) · HTTP 지표에 이미 남는다.
 * 꺼 두면 빈이 없어 애스펙트의 프록시 비용도 없다.
 *
 * <p>[JA] サービスメソッドごとの実行時間ログ — <b>既定オフ</b>、診断のときだけ {@code EXECUTION_TIME_LOG_ENABLED=true}。全メソッド呼び出しで
 * INFO を一行ずつ書き catalog CPU の 7.9% だった。実行時間はトレース · HTTP 指標に残る。
 */
@Aspect
@Component
@ConditionalOnProperty(name = "EXECUTION_TIME_LOG_ENABLED", havingValue = "true")
@Slf4j
public class ExecutionTimeLogger {

    @Around("execution(* com.project.userservice.service..*(..))")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        Object proceed = joinPoint.proceed();
        long endTime = System.currentTimeMillis();
        log.info("{} executed in {} ms", joinPoint.getSignature(), (endTime - startTime));
        return proceed;
    }
}