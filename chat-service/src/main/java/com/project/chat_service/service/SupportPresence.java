package com.project.chat_service.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 누가 지금 어느 상담 방을 보고 있는가 (docs/58 §10) — STOMP 구독으로 안다.
 *
 * <p>상담원 답변 알림을 <b>보고 있는 고객에게는 보내지 않기</b> 위해서다. 대화창을 열어 둔 사람에게 벨 알림까지
 * 울리면 소음이다. 구독(SUBSCRIBE)에 적고, 구독 해지(UNSUBSCRIBE)·연결 끊김(DISCONNECT)에 지운다.
 *
 * <p>파드가 여럿이다(Redis 팬아웃, docs/21 §2) — 고객은 A 파드에 붙어 보고 있는데 답변은 B 파드가 소비할 수 있다. 그래서 각 파드는
 * 자기 메모리의 구독을 Redis 해시 {@code chat:support-presence:<파드>} 에도 적고(필드 {@code 사용자|방} = 이 파드의 구독 수),
 * {@link #watching} 은 자기 메모리 → 다른 파드의 해시 순으로 본다. 해시는 {@link #TTL} 뒤에 사라지고 {@link #REFRESH} 마다 다시
 * 적는다 — 죽은 파드의 기록은 길어야 {@link #TTL} 만 남는다. Redis 에 닿지 못하면 자기 메모리만 본다(틀려도 "알림 한 통 더" 쪽).
 *
 * <p>[JA] 誰がどの相談ルームを見ているか。Pod が複数になったので、各 Pod の購読を Redis のハッシュにも書き(TTL つき)、他の Pod の分も見る。
 */
@Slf4j
@Component
public class SupportPresence {

    static final String KEY_PREFIX = "chat:support-presence:";
    static final String PODS_KEY = KEY_PREFIX + "pods";
    static final Duration TTL = Duration.ofSeconds(90);
    static final Duration REFRESH = Duration.ofSeconds(30);

    private record Watch(Long userId, String roomId) {
        String field() {
            return userId + "|" + roomId;
        }
    }

    /** 세션 → (구독 id → 무엇을 보고 있나). */
    private final Map<String, Map<String, Watch>> sessions = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;
    private final String podKey = KEY_PREFIX + UUID.randomUUID();
    private ScheduledExecutorService refresher;

    /** 이 파드만 보는 판(테스트 · Redis 없음). */
    public SupportPresence() {
        this(null);
    }

    @Autowired
    public SupportPresence(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @jakarta.annotation.PostConstruct
    void startRefresh() {
        if (redis == null) {
            return;
        }
        refresher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "support-presence-refresh");
            t.setDaemon(true);
            return t;
        });
        refresher.scheduleWithFixedDelay(this::publishSnapshot, REFRESH.toSeconds(), REFRESH.toSeconds(), TimeUnit.SECONDS);
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
        if (redis != null) {
            try {
                redis.delete(podKey);
                redis.opsForSet().remove(PODS_KEY, podKey);
            } catch (RuntimeException e) {
                // 내려가는 중 — TTL 이 지운다
            }
        }
    }

    public void subscribed(String sessionId, String subscriptionId, Long userId, String roomId) {
        if (sessionId == null || subscriptionId == null || userId == null || roomId == null) {
            return;
        }
        Watch w = new Watch(userId, roomId);
        sessions.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(subscriptionId, w);
        sync(w);
    }

    public void unsubscribed(String sessionId, String subscriptionId) {
        if (sessionId == null || subscriptionId == null) {
            return;
        }
        Map<String, Watch> subs = sessions.get(sessionId);
        if (subs != null) {
            Watch w = subs.remove(subscriptionId);
            if (subs.isEmpty()) {
                sessions.remove(sessionId, subs);
            }
            if (w != null) {
                sync(w);
            }
        }
    }

    public void disconnected(String sessionId) {
        if (sessionId != null) {
            Map<String, Watch> subs = sessions.remove(sessionId);
            if (subs != null) {
                subs.values().stream().distinct().forEach(this::sync);
            }
        }
    }

    /** 이 사용자가 이 방을 지금 구독하고 있는가(어느 파드의 탭 하나라도). */
    public boolean watching(Long userId, String roomId) {
        if (userId == null || roomId == null) {
            return false;
        }
        Watch w = new Watch(userId, roomId);
        if (localCount(w) > 0) {
            return true;
        }
        if (redis == null) {
            return false;
        }
        try {
            Set<String> pods = redis.opsForSet().members(PODS_KEY);
            if (pods == null) {
                return false;
            }
            for (String pod : pods) {
                if (pod.equals(podKey)) {
                    continue;
                }
                if (Boolean.TRUE.equals(redis.opsForHash().hasKey(pod, w.field()))) {
                    return true;
                }
                if (!Boolean.TRUE.equals(redis.hasKey(pod))) {
                    redis.opsForSet().remove(PODS_KEY, pod);   // 죽은 파드 — 해시는 TTL 로 사라졌다
                }
            }
        } catch (RuntimeException e) {
            log.debug("상담 presence 조회 실패 — 이 파드만 본다 ({})", e.getClass().getSimpleName());
        }
        return false;
    }

    private long localCount(Watch w) {
        return sessions.values().stream().flatMap(subs -> subs.values().stream()).filter(w::equals).count();
    }

    /** 한 (사용자, 방)의 이 파드 구독 수를 Redis 에 맞춘다. */
    private void sync(Watch w) {
        if (redis == null) {
            return;
        }
        try {
            long n = localCount(w);
            if (n > 0) {
                redis.opsForHash().put(podKey, w.field(), Long.toString(n));
            } else {
                redis.opsForHash().delete(podKey, w.field());
            }
            redis.expire(podKey, TTL);
            redis.opsForSet().add(PODS_KEY, podKey);
            redis.expire(PODS_KEY, Duration.ofDays(1));
        } catch (RuntimeException e) {
            log.debug("상담 presence 기록 실패 ({})", e.getClass().getSimpleName());
        }
    }

    /** 이 파드의 구독 전체를 다시 적고 수명을 늘린다 — Redis 가 비워졌어도(재시작) 다음 주기에 돌아온다. */
    void publishSnapshot() {
        if (redis == null) {
            return;
        }
        try {
            Map<String, String> snapshot = new HashMap<>();
            sessions.values().stream().flatMap(subs -> subs.values().stream())
                    .forEach(w -> snapshot.merge(w.field(), "1", (a, b) -> Long.toString(Long.parseLong(a) + 1)));
            Set<Object> stale = redis.opsForHash().keys(podKey);
            if (stale != null) {
                stale.removeAll(snapshot.keySet());
                if (!stale.isEmpty()) {
                    redis.opsForHash().delete(podKey, stale.toArray());
                }
            }
            if (!snapshot.isEmpty()) {
                redis.opsForHash().putAll(podKey, snapshot);
                redis.expire(podKey, TTL);
                redis.opsForSet().add(PODS_KEY, podKey);
                redis.expire(PODS_KEY, Duration.ofDays(1));
            }
        } catch (RuntimeException e) {
            log.debug("상담 presence 갱신 실패 ({})", e.getClass().getSimpleName());
        }
    }

    /** 테스트용 — 이 파드의 Redis 해시 키. */
    String podKey() {
        return podKey;
    }
}
