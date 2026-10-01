package com.project.catalog_service.batch.reader;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

/**
 * id 기준 키셋 페이징 리더 — 재색인(ElasticsearchBatchConfig)이 수백만 행을 읽을 때 쓴다(docs/19 §5.2).
 *
 * <h3>🐛 예전: JpaPagingItemReader + 컬렉션 JOIN FETCH</h3>
 *
 * <ul>
 *   <li><b>메모리</b> — 컬렉션을 JOIN FETCH 한 쿼리에 페이지(LIMIT/OFFSET)를 걸면 Hibernate 는 SQL 에 LIMIT 을 붙이지 못해
 *       <b>결과 전체를 메모리에 올린 뒤</b> 자른다(HHH90003004). 페이지마다 전체를 읽는다 — 상품 250개에서는 안 보이고,
 *       200만 개면 catalog-service 가 OOM 으로 죽는다.</li>
 *   <li><b>정렬 없음</b> — ORDER BY 없는 OFFSET 페이징은 페이지 사이에 행이 빠지거나 겹칠 수 있다.</li>
 *   <li><b>OFFSET</b> — 정렬을 넣어도 OFFSET 은 앞 페이지를 매번 다시 건너뛴다. 600만 행 · 페이지 1000 이면 건너뛰는 행이
 *       약 180억 — 끝나지 않는다.</li>
 *   <li><b>스레드</b> — 스텝이 멀티 스레드라 프로세서가 리더의 EntityManager 로 LAZY 컬렉션을 동시에 읽었다
 *       (EntityManager 는 스레드 안전하지 않다).</li>
 * </ul>
 *
 * <h3>지금</h3>
 *
 * <ol>
 *   <li>id 만 {@code WHERE id > :last ORDER BY id LIMIT n} — 인덱스로 바로 그 자리부터 읽는다.</li>
 *   <li>그 id 들의 엔티티를 {@code IN} 으로 읽고, {@code initializer} 가 프로세서가 쓸 연관을 <b>같은 페이지 안에서</b>
 *       채운다(컬렉션 하나당 쿼리 하나).</li>
 *   <li>페이지마다 새 EntityManager 를 열고 닫는다 — 넘겨주는 엔티티는 분리(detached) 상태라 다른 스레드가 읽어도 안전하고,
 *       영속성 컨텍스트가 페이지 수만큼 자라지 않는다.</li>
 * </ol>
 *
 * <p>{@link #read()} 는 synchronized — 멀티 스레드 스텝이 함께 불러도 같은 행을 두 번 주지 않는다.
 * 재시작 지점은 저장하지 않는다(재색인은 처음부터 다시 돈다 — 새 인덱스에 쓰고 별칭을 마지막에 바꾼다).
 *
 * @param <T> 엔티티. id 는 Long.
 */
// [JA] id 基準のキーセットページングリーダー。旧 JpaPagingItemReader + コレクション JOIN FETCH は、ページングがメモリ上で行われ
//      (HHH90003004)、ORDER BY がなく、OFFSET で毎回前を読み飛ばし、マルチスレッドのプロセッサが共有 EntityManager で LAZY を読んでいた。
//      今は id だけを WHERE id > :last ORDER BY id LIMIT n で読み、IN で実体を読み、同じページ内で関連を初期化し、ページごとに
//      EntityManager を閉じる(渡すエンティティは detached)。
public class KeysetItemReader<T> implements ItemStreamReader<T> {

    private final EntityManagerFactory emf;
    private final String idQuery;
    private final String loadQuery;
    private final Function<T, Long> idOf;
    private final BiConsumer<EntityManager, List<Long>> initializer;
    private final int pageSize;

    private final Deque<T> buffer = new ArrayDeque<>();
    private long lastId = Long.MIN_VALUE;
    private boolean exhausted;

    /**
     * @param entity      JPQL 엔티티 이름(예: {@code "Product"})
     * @param idAttribute id 속성 이름(예: {@code "productId"})
     * @param loadQuery   {@code :ids} 로 엔티티를 읽는 JPQL — 단일 값 연관(ManyToOne)과 컬렉션 <b>하나</b>까지 JOIN FETCH 해도 된다
     * @param initializer 같은 EntityManager 에서 나머지 연관을 채운다(같은 영속성 컨텍스트라 이미 읽은 엔티티에 붙는다). 없으면 null
     */
    public KeysetItemReader(EntityManagerFactory emf, String entity, String idAttribute, String loadQuery,
            Function<T, Long> idOf, BiConsumer<EntityManager, List<Long>> initializer, int pageSize) {
        this.emf = emf;
        this.idQuery = "SELECT e." + idAttribute + " FROM " + entity + " e WHERE e." + idAttribute
                + " > :last ORDER BY e." + idAttribute;
        this.loadQuery = loadQuery;
        this.idOf = idOf;
        this.initializer = initializer;
        this.pageSize = pageSize;
    }

    @Override
    public synchronized T read() {
        if (buffer.isEmpty() && !exhausted) {
            fetchPage();
        }
        return buffer.poll();
    }

    @SuppressWarnings("unchecked")
    private void fetchPage() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            List<Long> ids = em.createQuery(idQuery, Long.class)
                    .setParameter("last", lastId)
                    .setMaxResults(pageSize)
                    .getResultList();
            if (ids.isEmpty()) {
                exhausted = true;
                tx.commit();
                return;
            }
            List<T> rows = (List<T>) em.createQuery(loadQuery)
                    .setParameter("ids", ids)
                    .setHint("org.hibernate.readOnly", true)
                    .getResultList();
            if (initializer != null) {
                initializer.accept(em, ids);
            }
            tx.commit();
            // JOIN FETCH 로 같은 엔티티가 여러 줄 나올 수 있다 — id 로 한 번씩, id 순으로
            rows.stream()
                    .collect(java.util.stream.Collectors.toMap(idOf, r -> r, (a, b) -> a))
                    .values().stream()
                    .sorted(Comparator.comparing(idOf))
                    .forEach(buffer::add);
            lastId = ids.get(ids.size() - 1);
            if (ids.size() < pageSize) {
                exhausted = true;
            }
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            em.close();
        }
    }

    @Override
    public synchronized void open(ExecutionContext executionContext) throws ItemStreamException {
        buffer.clear();
        lastId = Long.MIN_VALUE;
        exhausted = false;
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // 재시작 지점을 저장하지 않는다 — 재색인은 처음부터 다시 돈다
    }

    @Override
    public void close() throws ItemStreamException {
        buffer.clear();
    }
}
