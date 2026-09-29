package com.project.common.kafka.avro;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;

import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericEnumSymbol;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Avro 레코드를 <b>평범한 JSON</b>으로 — 문자열 소비자({@code List<String>} + {@code JsonNode})가
 * 예전 JSON 프로듀서의 출력과 같은 모양을 보게 한다.
 *
 * <p>Avro 자체의 JSON 인코더를 쓰지 않는 이유: 그건 유니온을 {@code {"string":"x"}} 로 감싸고
 * 논리 타입을 원시값(epoch millis)으로 낸다. delivery·notification 리스너는 {@code event.get("customerId")}
 * 처럼 필드를 바로 꺼내므로 그 모양이면 전부 null 이 된다. 여기서는 유니온을 벗기고, enum 은 이름으로,
 * timestamp-millis 는 ISO-8601 문자열로 낸다 — 예전 Jackson 출력과 같다.
 */
public final class SpecificRecordJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpecificRecordJson() {
    }

    public static String toJson(IndexedRecord record) {
        try {
            return MAPPER.writeValueAsString(toNode(record.getSchema(), record));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Avro → JSON 실패: " + record.getSchema().getFullName(), e);
        }
    }

    static JsonNode toNode(Schema schema, Object value) {
        if (value == null) {
            return MAPPER.nullNode();
        }
        switch (schema.getType()) {
            case RECORD: {
                ObjectNode node = MAPPER.createObjectNode();
                IndexedRecord r = (IndexedRecord) value;
                for (Schema.Field f : schema.getFields()) {
                    node.set(f.name(), toNode(f.schema(), r.get(f.pos())));
                }
                return node;
            }
            case UNION: {
                // 값에 맞는 분기를 고른다 — null 이 아니면 null 이 아닌 첫 분기가 맞는 경우가 대부분이다.
                for (Schema branch : schema.getTypes()) {
                    if (branch.getType() == Schema.Type.NULL) continue;
                    if (matches(branch, value)) return toNode(branch, value);
                }
                return toNode(schema.getTypes().get(schema.getTypes().size() - 1), value);
            }
            case ARRAY: {
                ArrayNode arr = MAPPER.createArrayNode();
                for (Object item : (Collection<?>) value) arr.add(toNode(schema.getElementType(), item));
                return arr;
            }
            case MAP: {
                ObjectNode node = MAPPER.createObjectNode();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    node.set(String.valueOf(e.getKey()), toNode(schema.getValueType(), e.getValue()));
                }
                return node;
            }
            case ENUM:
                return MAPPER.getNodeFactory().textNode(
                        value instanceof GenericEnumSymbol<?> s ? s.toString() : ((Enum<?>) value).name());
            case STRING:
                return MAPPER.getNodeFactory().textNode(value.toString());
            case LONG: {
                LogicalType lt = schema.getLogicalType();
                if (value instanceof Instant i) return MAPPER.getNodeFactory().textNode(i.toString());
                if (lt instanceof LogicalTypes.TimestampMillis) {
                    return MAPPER.getNodeFactory().textNode(Instant.ofEpochMilli((Long) value).toString());
                }
                return MAPPER.getNodeFactory().numberNode((Long) value);
            }
            case INT:
                return MAPPER.getNodeFactory().numberNode((Integer) value);
            case DOUBLE:
                return MAPPER.getNodeFactory().numberNode((Double) value);
            case FLOAT:
                return MAPPER.getNodeFactory().numberNode((Float) value);
            case BOOLEAN:
                return MAPPER.getNodeFactory().booleanNode((Boolean) value);
            case BYTES:
            case FIXED: {
                byte[] bytes = value instanceof ByteBuffer b ? b.array() : (byte[]) value;
                return MAPPER.getNodeFactory().textNode(Base64.getEncoder().encodeToString(bytes));
            }
            case NULL:
            default:
                return MAPPER.nullNode();
        }
    }

    private static boolean matches(Schema branch, Object value) {
        return switch (branch.getType()) {
            case RECORD -> value instanceof GenericRecord || value instanceof IndexedRecord;
            case ENUM -> value instanceof Enum || value instanceof GenericEnumSymbol;
            case STRING -> value instanceof CharSequence;
            case LONG -> value instanceof Long || value instanceof Instant;
            case INT -> value instanceof Integer;
            case BOOLEAN -> value instanceof Boolean;
            case DOUBLE -> value instanceof Double;
            case FLOAT -> value instanceof Float;
            case ARRAY -> value instanceof Collection;
            case MAP -> value instanceof Map;
            case BYTES, FIXED -> value instanceof ByteBuffer || value instanceof byte[];
            default -> false;
        };
    }
}
