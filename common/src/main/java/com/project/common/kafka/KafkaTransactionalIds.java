package com.project.common.kafka;

import java.net.InetAddress;
import java.util.UUID;

/**
 * 안정적인 {@code transactional.id}.
 *
 * <p>{@code transactional.id} 는 <b>같은 프로듀서의 다음 화신(incarnation)을 식별</b>하는 값이다.
 * 파드가 죽었다 살아나 같은 id 로 초기화하면 브로커가 epoch 를 올려 이전 화신(좀비)의 미완 트랜잭션을
 * 중단시키고 그 프로듀서의 쓰기를 거부한다 — 그게 펜싱이다. 기동마다 {@code UUID.randomUUID()} 를 쓰면
 * 매번 "처음 보는 프로듀서"라 펜싱이 일어나지 않고, 브로커에는 다시는 안 쓰일 트랜잭션 상태가
 * 쌓인다(docs/38 §2.3 #10).
 *
 * <p>k8s 에서 파드 이름({@code HOSTNAME})은 Deployment 의 파드가 교체되면 바뀌지만, <b>같은 파드가
 * 재시작할 때는 같다</b> — 좀비가 생기는 바로 그 경우다. StatefulSet 이면 완전히 고정된다.
 * 접두사는 <b>프로듀서 팩토리마다 달라야</b> 한다: 같은 JVM 의 두 프로듀서가 같은 id 를 쓰면 서로를
 * 펜싱한다.
 *
 * <p>[JA] 安定した {@code transactional.id}。
 *
 * <p>{@code transactional.id} は<b>同じプロデューサーの次の化身(incarnation)を識別</b>するための値である。
 * Pod が落ちて復活し同じ id で初期化すると、ブローカーが epoch を上げて前の化身(ゾンビ)の未完了トランザクションを
 * 中断させ、そのプロデューサーの書き込みを拒否する — これがフェンシングである。起動ごとに {@code UUID.randomUUID()} を使うと
 * 毎回「初めて見るプロデューサー」になるためフェンシングが起きず、ブローカーには二度と使われないトランザクション状態が
 * 溜まっていく(docs/38 §2.3 #10)。
 *
 * <p>k8s では Pod 名({@code HOSTNAME})は Deployment の Pod が入れ替わると変わるが、<b>同じ Pod が
 * 再起動するときは同じである</b> — ゾンビが生まれるのはまさにその場合である。StatefulSet なら完全に固定される。
 * 接頭辞は<b>プロデューサーファクトリごとに異なっていなければ</b>ならない: 同じ JVM の二つのプロデューサーが同じ id を使うと互いを
 * フェンシングしてしまう。
 */
public final class KafkaTransactionalIds {

    private KafkaTransactionalIds() {
    }

    /** {@code <prefix>-<hostname>}. 호스트명을 못 얻으면 UUID 로 물러선다(펜싱 없음, 기동은 된다). */
    // [JA] {@code <prefix>-<hostname>}。ホスト名が取得できなければ UUID にフォールバックする(フェンシングなし、起動はできる)。
    public static String stable(String prefix) {
        return prefix + "-" + instanceName();
    }

    static String instanceName() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = null;
            }
        }
        return host == null || host.isBlank() ? "anon-" + UUID.randomUUID() : host.trim();
    }
}
