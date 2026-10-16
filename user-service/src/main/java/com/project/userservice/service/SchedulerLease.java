package com.project.userservice.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 예약 작업의 실행 임대 — 레플리카가 여럿이어도 한 회차는 한 파드만 돈다(V17 {@code users.scheduler_lock}).
 *
 * <p>DB 연결을 붙잡지 않는다(advisory lock 은 그 연결을 작업 내내 쥐어야 해 풀 5개 중 하나를 묶는다). 행 하나에 "언제까지 · 누가" 를
 * 적고, 만료됐거나 빈 자리만 가져간다. 시각은 DB 의 {@code now()} 로 — 파드끼리 시계가 어긋나도 된다. 작업이 끝나면 푼다.
 * 파드가 도중에 죽으면 임대는 만료 시각에 저절로 풀린다.
 *
 * <p>[JA] 予約ジョブの実行リース。DB 接続を握らない — 行に「いつまで · 誰が」を書き、期限切れか空きだけを取る。時刻は DB の now()。
 */
@Slf4j
@Component
public class SchedulerLease {

    private final JdbcTemplate jdbc;

    /** 이 파드의 표지 — 파드 이름(HOSTNAME) + 난수. 풀 때 남의 임대를 풀지 않게. */
    private final String owner;

    public SchedulerLease(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        String host = System.getenv("HOSTNAME");
        String id = (host == null || host.isBlank() ? "local" : host) + "/" + UUID.randomUUID();
        this.owner = id.length() > 128 ? id.substring(0, 128) : id;
    }

    /** 임대를 잡았으면 {@code true}. 다른 파드가 쥐고 있으면(만료 전) {@code false}. */
    public boolean tryAcquire(String name, Duration lease) {
        int rows = jdbc.update("INSERT INTO users.scheduler_lock (name, locked_until, locked_by) "
                        + "VALUES (?, now() + (? * interval '1 second'), ?) "
                        + "ON CONFLICT (name) DO UPDATE SET locked_until = EXCLUDED.locked_until, locked_by = EXCLUDED.locked_by "
                        + "WHERE users.scheduler_lock.locked_until <= now()",
                name, lease.toSeconds(), owner);
        return rows == 1;
    }

    /** 내가 잡은 임대만 푼다. */
    public void release(String name) {
        int rows = jdbc.update("UPDATE users.scheduler_lock SET locked_until = now() WHERE name = ? AND locked_by = ?",
                name, owner);
        if (rows == 0) {
            log.warn("예약 작업 임대를 풀지 못했다(이미 만료돼 다른 파드가 가져갔다?) name={}", name);
        }
    }
}
