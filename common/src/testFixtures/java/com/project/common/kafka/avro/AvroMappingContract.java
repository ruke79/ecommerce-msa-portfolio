package com.project.common.kafka.avro;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;

/**
 * DTO ↔ Avro 매핑의 계약 — <b>DTO 의 필드가 전선을 건너 살아서 돌아오는가</b> (docs/55 §7-7).
 *
 * <h3>🐛 왜 필요한가</h3>
 *
 * <p>결제 요청 DTO 에는 {@code paymentProvider}·{@code providerPaymentId} 가 있었지만 Avro 스키마에는 없었다. 매핑은 없는
 * 필드를 조용히 버렸고, 컴파일도 테스트도 통과했다 — pay 는 Stripe·포트원·무통장 결제의 참조를 받은 적이 없다
 * (docs/49 §3). DTO 와 스키마는 따로 논다. 한쪽에 필드를 더하고 다른 쪽을 잊으면 아무도 모른다.
 *
 * <p>두 가지를 본다.
 * <ol>
 *   <li><b>이름</b> — DTO 의 인스턴스 필드와 스키마의 필드가 같은가. 목록 안의 중첩 레코드까지 내려간다.</li>
 *   <li><b>왕복</b> — 모든 필드를 기본값이 아닌 값으로 채워 {@code toRecord → encoder → decoder → toDto} 를 한 번 돌리고,
 *       같은 값이 돌아오는가. 스키마에 필드가 있어도 매핑 코드가 옮기지 않으면 여기서 걸린다.</li>
 * </ol>
 *
 * <p>일부러 싣지 않는 필드는 {@code ignored} 에 <b>경로 → 이유</b>로 적는다. 이유 없는 제외는 받지 않는다.
 * Kafka 없이 Avro 만으로 돈다 — 직렬화기가 하는 일(단일 객체 인코딩)과 같다.
 */
public final class AvroMappingContract {

    private AvroMappingContract() {
    }

    /** 문제 목록. 비어 있으면 계약을 지킨다. */
    public static <D, R extends SpecificRecord> List<String> verify(AvroMapping<D, R> mapping,
            Map<String, String> ignored) {
        List<String> problems = new ArrayList<>();
        ignored.forEach((path, reason) -> {
            if (reason == null || reason.isBlank()) {
                problems.add(path + ": 제외하는 이유를 적을 것");
            }
        });

        D sample;
        try {
            sample = mapping.dtoType().cast(sample(mapping.dtoType(), mapping.dtoType().getSimpleName(), problems));
        } catch (RuntimeException e) {
            problems.add(mapping.dtoType().getSimpleName() + ": 샘플을 만들 수 없다 — " + e);
            return problems;
        }
        if (!problems.isEmpty()) {
            return problems;
        }

        R record = mapping.toRecord(sample);
        compareNames(mapping.dtoType(), record.getSchema(), mapping.dtoType().getSimpleName(), ignored, problems);

        D back;
        try {
            ByteBuffer bytes = mapping.encoder().encode(record);
            back = mapping.toDto(mapping.decoder().decode(bytes));
        } catch (IOException | RuntimeException e) {
            problems.add(mapping.dtoType().getSimpleName() + ": 왕복에 실패했다 — " + e);
            return problems;
        }
        compareValues(sample, back, mapping.dtoType().getSimpleName(), ignored, problems);
        return problems;
    }

    // ---------------------------------------------------------------- 이름

    private static void compareNames(Class<?> dto, Schema schema, String path, Map<String, String> ignored,
            List<String> problems) {
        Schema record = nonNull(schema);
        if (record.getType() != Schema.Type.RECORD) {
            return;
        }
        Map<String, Type> dtoFields = fieldsOf(dto);
        for (Map.Entry<String, Type> f : dtoFields.entrySet()) {
            String p = path + "." + f.getKey();
            if (ignored.containsKey(p)) {
                continue;
            }
            Schema.Field sf = record.getField(f.getKey());
            if (sf == null) {
                problems.add(p + ": DTO 에만 있다 — 스키마에 없어 전선에서 조용히 사라진다");
                continue;
            }
            Class<?> element = elementClassOf(f.getValue());
            Schema s = nonNull(sf.schema());
            if (element != null && s.getType() == Schema.Type.ARRAY) {
                compareNames(element, s.getElementType(), p + "[]", ignored, problems);
            } else if (isNested(raw(f.getValue())) && s.getType() == Schema.Type.RECORD) {
                compareNames(raw(f.getValue()), s, p, ignored, problems);
            }
        }
        for (Schema.Field sf : record.getFields()) {
            String p = path + "." + sf.name();
            if (!dtoFields.containsKey(sf.name()) && !ignored.containsKey(p)) {
                problems.add(p + ": 스키마에만 있다 — DTO 가 받을 곳이 없다");
            }
        }
    }

    private static Schema nonNull(Schema s) {
        if (s.getType() != Schema.Type.UNION) {
            return s;
        }
        return s.getTypes().stream().filter(t -> t.getType() != Schema.Type.NULL).findFirst().orElse(s);
    }

    // ---------------------------------------------------------------- 왕복

    private static void compareValues(Object sent, Object got, String path, Map<String, String> ignored,
            List<String> problems) {
        if (ignored.containsKey(path)) {
            return;
        }
        if (sent == null) {
            return;
        }
        if (got == null) {
            problems.add(path + ": 보낸 값 " + sent + " — 받은 값 null (매핑이 옮기지 않는다)");
            return;
        }
        if (sent instanceof BigDecimal a && got instanceof BigDecimal b) {
            if (a.compareTo(b) != 0) {
                problems.add(path + ": 보낸 값 " + a + " — 받은 값 " + b);
            }
            return;
        }
        if (sent instanceof List<?> a && got instanceof List<?> b) {
            if (a.size() != b.size()) {
                problems.add(path + ": 목록 크기 " + a.size() + " — 받은 크기 " + b.size());
                return;
            }
            for (int i = 0; i < a.size(); i++) {
                compareValues(a.get(i), b.get(i), path + "[]", ignored, problems);
            }
            return;
        }
        if (isNested(sent.getClass())) {
            for (String name : fieldsOf(sent.getClass()).keySet()) {
                compareValues(read(sent, name), read(got, name), path + "." + name, ignored, problems);
            }
            return;
        }
        if (!sent.equals(got)) {
            problems.add(path + ": 보낸 값 " + sent + " — 받은 값 " + got);
        }
    }

    // ---------------------------------------------------------------- 샘플 — 기본값이 아닌 값으로

    private static Object sample(Type type, String path, List<String> problems) {
        Class<?> c = raw(type);
        if (c == String.class) {
            return "v-" + path.substring(path.lastIndexOf('.') + 1);
        }
        if (c == Long.class || c == long.class) {
            return 1234567890123456789L;   // 19자리 — TSID 처럼
        }
        if (c == Integer.class || c == int.class) {
            return 42;
        }
        if (c == Double.class || c == double.class) {
            return 1.5d;
        }
        if (c == Boolean.class || c == boolean.class) {
            return true;
        }
        if (c == BigDecimal.class) {
            return new BigDecimal("12345.67");
        }
        if (c == LocalDateTime.class) {
            return LocalDateTime.of(2026, 9, 25, 12, 34, 56, 789_000_000);   // timestamp-millis 정밀도
        }
        if (c == Instant.class) {
            return Instant.ofEpochMilli(1_790_000_000_123L);
        }
        if (c == LocalDate.class) {
            return LocalDate.of(2026, 9, 25);
        }
        if (c == UUID.class) {
            return UUID.fromString("00000000-0000-4000-8000-000000000001");
        }
        if (c.isEnum()) {
            Object[] values = c.getEnumConstants();
            return values[values.length - 1];   // 첫 값은 흔히 기본값이다
        }
        if (List.class.isAssignableFrom(c) || Collection.class.isAssignableFrom(c)) {
            Type element = ((ParameterizedType) type).getActualTypeArguments()[0];
            return new ArrayList<>(List.of(sample(element, path + "[]", problems)));
        }
        if (Map.class.isAssignableFrom(c)) {
            Type value = ((ParameterizedType) type).getActualTypeArguments()[1];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("k", sample(value, path + "{}", problems));
            return m;
        }
        if (isNested(c)) {
            return instantiate(c, path, problems);
        }
        problems.add(path + ": 이 타입의 샘플을 만들 줄 모른다(" + type.getTypeName() + ") — AvroMappingContract 에 더할 것");
        return null;
    }

    private static Object instantiate(Class<?> c, String path, List<String> problems) {
        try {
            if (c.isRecord()) {
                RecordComponent[] rc = c.getRecordComponents();
                Object[] args = new Object[rc.length];
                Class<?>[] types = new Class<?>[rc.length];
                for (int i = 0; i < rc.length; i++) {
                    args[i] = sample(rc[i].getGenericType(), path + "." + rc[i].getName(), problems);
                    types[i] = rc[i].getType();
                }
                Constructor<?> k = c.getDeclaredConstructor(types);
                k.setAccessible(true);
                return k.newInstance(args);
            }
            Constructor<?> k = c.getDeclaredConstructor();
            k.setAccessible(true);
            Object o = k.newInstance();
            for (Field f : instanceFields(c)) {
                f.setAccessible(true);
                f.set(o, sample(f.getGenericType(), path + "." + f.getName(), problems));
            }
            return o;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(path + " 을 만들 수 없다", e);
        }
    }

    // ---------------------------------------------------------------- 리플렉션

    private static Map<String, Type> fieldsOf(Class<?> c) {
        Map<String, Type> m = new LinkedHashMap<>();
        if (c.isRecord()) {
            for (RecordComponent rc : c.getRecordComponents()) {
                m.put(rc.getName(), rc.getGenericType());
            }
            return m;
        }
        for (Field f : instanceFields(c)) {
            m.put(f.getName(), f.getGenericType());
        }
        return m;
    }

    private static List<Field> instanceFields(Class<?> c) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                    fields.add(f);
                }
            }
        }
        return fields;
    }

    private static Object read(Object o, String name) {
        try {
            if (o.getClass().isRecord()) {
                return o.getClass().getMethod(name).invoke(o);
            }
            for (Field f : instanceFields(o.getClass())) {
                if (f.getName().equals(name)) {
                    f.setAccessible(true);
                    return f.get(o);
                }
            }
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?> raw(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        return Object.class;
    }

    private static Class<?> elementClassOf(Type t) {
        if (t instanceof ParameterizedType p && Collection.class.isAssignableFrom(raw(p))) {
            Class<?> e = raw(p.getActualTypeArguments()[0]);
            return isNested(e) ? e : null;
        }
        return null;
    }

    /** 우리 코드의 DTO 인가 — 값 타입(JDK·열거형)이 아닌가. */
    private static boolean isNested(Class<?> c) {
        return !c.isPrimitive() && !c.isEnum() && !c.isArray() && c.getName().startsWith("com.project.");
    }

    /** 제외 목록을 만드는 도우미 — 경로와 이유를 짝으로. */
    public static Map<String, String> ignoring(String... pathAndReason) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pathAndReason.length; i += 2) {
            m.put(pathAndReason[i], pathAndReason[i + 1]);
        }
        return m;
    }
}
