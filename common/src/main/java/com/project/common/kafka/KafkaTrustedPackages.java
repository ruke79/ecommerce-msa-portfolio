package com.project.common.kafka;

import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * Kafka JSON 역직렬화가 믿는 패키지 — 메시지 DTO 가 있는 곳만(B-115, docs/57 §22).
 *
 * <p>🐛 예전에는 10곳이 {@code addTrustedPackages("*")} 였다. 이 컨슈머들은 헤더({@code __TypeId__})가 있으면 그 클래스로 읽는다
 * (CDC 릴레이 때문에 헤더가 없으면 대상 DTO 로 — docs/38 §5 A3). {@code "*"} 면 토픽에 쓸 수 있는 누구든 헤더 하나로
 * <b>클래스패스의 아무 클래스나</b> 만들게 할 수 있다 — Jackson 다형 역직렬화 가젯(생성자 · setter 의 부작용)의 입구다.
 * 서비스 사이 메시지 DTO 는 모두 {@code common} 의 {@code com.project.common.message.dto} 아래에 있으므로 그곳만 믿는다.
 * ({@code java.util} · {@code java.lang} 은 Spring 이 늘 믿는다.)
 *
 * <p>[JA] Kafka JSON デシリアライズが信頼するパッケージ — メッセージ DTO のある所だけ。以前は 10 か所が "*" で、
 * ヘッダー(__TypeId__)一つでクラスパス上の任意のクラスを作らせられた(Jackson ガジェットの入口)。DTO はすべて common にある。
 */
public final class KafkaTrustedPackages {

    /** 메시지 DTO 패키지(하위 request · response 포함). [JA] メッセージ DTO のパッケージ(下位を含む)。 */
    public static final String MESSAGE_DTO = "com.project.common.message.dto.*";

    private KafkaTrustedPackages() {
    }

    /** 이 역직렬화기가 메시지 DTO 패키지만 믿게 하고 그대로 돌려준다. [JA] DTO パッケージだけを信頼させる。 */
    public static <T> JsonDeserializer<T> trustMessageDtos(JsonDeserializer<T> deserializer) {
        deserializer.addTrustedPackages(MESSAGE_DTO);
        return deserializer;
    }
}
