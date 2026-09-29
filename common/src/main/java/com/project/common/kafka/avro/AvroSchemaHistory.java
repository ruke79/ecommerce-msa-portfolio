package com.project.common.kafka.avro;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.apache.avro.Schema;
import org.apache.avro.message.SchemaStore;

/**
 * 옛 작성 스키마 — 스키마를 바꾼 뒤에도 옛 프로듀서가 쓴 레코드를 읽기 위해.
 *
 * <p>단일 객체 인코딩의 레코드에는 작성 스키마의 <b>지문</b>이 붙는다({@link AvroWire}). 생성 클래스의 기본 디코더는
 * 자기 스키마의 지문만 알아서, 필드를 하나만 더해도 <b>배포 도중 옛 파드가 쓴 레코드를 전부 못 읽는다</b> — 역직렬화
 * 실패로 DLT 에 간다. 레지스트리가 없으니 옛 스키마를 {@code resources/avro-history/} 에 남기고 디코더의
 * {@link SchemaStore} 에 등록한다. 읽을 때는 Avro 의 스키마 해석(writer → reader)이 새 필드를 default 로 채운다.
 *
 * <p>규칙: 스키마를 바꾸면 <b>바꾸기 전 파일</b>을 {@code avro-history/<이름>-v<n>.avsc} 로 복사하고, 그 토픽의
 * 매핑과 {@link AvroDecoders} 가 {@code createDecoder(store(...))} 를 쓰게 한다. 옛 스키마를 지우는 것은 그 스키마로
 * 쓴 레코드가 토픽 보존 기간을 지나 사라진 뒤다.
 */
public final class AvroSchemaHistory {

    private AvroSchemaHistory() {
    }

    /** 클래스패스의 옛 스키마들을 담은 저장소. 하나라도 없거나 깨졌으면 기동 단계에서 실패한다. */
    public static SchemaStore.Cache store(String... resources) {
        SchemaStore.Cache store = new SchemaStore.Cache();
        for (String resource : resources) {
            try (InputStream in = AvroSchemaHistory.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("옛 Avro 스키마가 클래스패스에 없다: " + resource);
                }
                store.addSchema(new Schema.Parser().parse(in));
            } catch (IOException e) {
                throw new UncheckedIOException("옛 Avro 스키마를 읽지 못했다: " + resource, e);
            }
        }
        return store;
    }

    /** 옛 스키마 하나 — 전환 릴리스 동안 옛 작성 스키마로 쓸 때, 그리고 옛 소비자를 흉내 내는 테스트에서. */
    public static Schema schema(String resource) {
        try (InputStream in = AvroSchemaHistory.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("옛 Avro 스키마가 클래스패스에 없다: " + resource);
            }
            return new Schema.Parser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("옛 Avro 스키마를 읽지 못했다: " + resource, e);
        }
    }

    /** chat 토픽의 옛 스키마 — v1 은 senderId 가 없고 발신자를 email 로 나타냈다. v2 는 둘 다 있었다. */
    public static final String[] CHAT_MESSAGE = {
            "avro-history/ChatMessage-v1.avsc", "avro-history/ChatMessage-v2.avsc" };

    /** user-created 토픽의 옛 스키마 — v1 은 email 이 있었다. */
    public static final String[] USER_CREATED = { "avro-history/UserCreatedRequest-v1.avsc" };

    /** cart-empty·cart-rollback·coupon-use 의 옛 스키마 — v1 은 사용자 액세스 토큰(authorizationToken)을 실었다. */
    public static final String[] CART_EMPTY = { "avro-history/CartEmptyRequest-v1.avsc" };
    public static final String[] CART_ROLLBACK = { "avro-history/CartRollbackRequest-v1.avsc" };
    public static final String[] COUPON_USE = { "avro-history/CouponUseRequest-v1.avsc" };

    /**
     * order 토픽의 옛 스키마 — v1 은 사용자 액세스 토큰을 실었다. v2 는 제공자 중립 필드(paymentProvider·
     * providerPaymentId)가 없었다. v3 는 옛 PayPal 전용 paypalOrderId 가 있었다. product-update 의 v1 도 토큰을 실었다
     * (오타 autorizationToken).
     *
     * <p>v4(paypalOrderId 제거)는 앞 릴리스(C1)가 "다음 스키마" 로 디코더에 먼저 넣었다 — 필드를 빼면 뒤 필드의 위치가
     * 밀려 "새 클래스를 옛 작성 스키마로 쓴다" 가 통하지 않기 때문이다(docs/49 §3). v1·v2 는 DLT 보존 기간(14일)이 지나면
     * 지운다(C3).
     */
    public static final String[] PAYMENT_REQUEST = {
            "avro-history/PaymentRequest-v1.avsc", "avro-history/PaymentRequest-v2.avsc",
            "avro-history/PaymentRequest-v3.avsc" };
    public static final String[] PRODUCT_UPDATE = { "avro-history/ProductUpdateRequest-v1.avsc" };
}
