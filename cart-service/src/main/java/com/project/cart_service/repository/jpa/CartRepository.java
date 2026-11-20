package com.project.cart_service.repository.jpa;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.cart_service.model.Cart;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    
    // 비관적 잠금: 사용자의 장바구니를 조회할 때 쓰기 락을 걸어 다른 트랜잭션이 수정하지 못하도록 합니다.
    // 이 메서드를 호출하면 데이터베이스에 "SELECT ... FOR UPDATE" 쿼리가 실행됩니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    // 줄을 함께 읽는다 — 조회 · 체크아웃은 트랜잭션 밖에서 줄을 쓴다(open-in-view 를 끈 뒤로는 지연 로딩이 거기서 실패한다, B-87).
    // [JA] 悲観的ロック: ユーザーのカートを照会するときに書き込みロックをかけ、他のトランザクションが変更できないようにする。
    //      このメソッドを呼ぶとデータベースで "SELECT ... FOR UPDATE" クエリが実行される。
    //      行を一緒に読む — 照会 · チェックアウトはトランザクションの外で行を使う(open-in-view を切って以降、遅延ロードはそこで失敗する、B-87)。
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"cartProducts"})
    Optional<Cart> findByUserId(Long userId);
    
    // 낙관적 잠금: @Version 필드를 사용하여 동시성 충돌을 감지하고, 별도의 DB 락은 걸지 않습니다.
    // 이 메서드로 조회된 엔티티가 이후 수정될 때 @Version 필드를 기반으로 충돌을 감지합니다.
    //@Lock(LockModeType.OPTIMISTIC)
    // [JA] 楽観的ロック: @Version フィールドを使って同時実行の衝突を検知し、別途の DB ロックはかけない。
    //      このメソッドで照会したエンティティが後で更新されるとき、@Version フィールドに基づいて衝突を検知する。
    @Override
    Optional<Cart> findById(Long id);

    /**
     * 사용자의 장바구니가 없을 때만 만든다 — 이미 있으면(다른 요청이 방금 만들었어도) 아무것도 하지 않는다.
     *
     * <p>"읽고 없으면 save" 는 처음 담기가 동시에 오면 둘 다 만든다. 유니크 인덱스(V5)만 두면 늦은 쪽이 중복 키로 실패하고,
     * PostgreSQL 은 그 트랜잭션을 통째로 무효로 만들어 이어서 읽을 수도 없다. {@code ON CONFLICT DO NOTHING} 은 앞선 쪽의
     * 커밋을 기다렸다가 조용히 넘어가므로, 뒤이은 {@code findByUserId} 가 그 장바구니를 읽는다(READ COMMITTED).
     * [JA] 利用者のカートが無いときだけ作る — 既にあれば(別のリクエストが今作ったところでも)何もしない。
     * 「読んで無ければ save」は初回追加が同時に来ると両方が作る。ユニークインデックス(V5)だけだと遅い方が重複キーで失敗し、
     * PostgreSQL はそのトランザクションを丸ごと無効にするので続けて読むこともできない。{@code ON CONFLICT DO NOTHING} は先の方の
     * コミットを待ってから静かに通り過ぎるので、続く {@code findByUserId} がそのカートを読む(READ COMMITTED)。
     * 戻り値: 作れば 1、既にあれば 0。
     *
     * @return 만들었으면 1, 이미 있었으면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "INSERT INTO cart.cart (cart_id, user_id, version, created_at, updated_at) "
            + "VALUES (:cartId, :userId, 0, LOCALTIMESTAMP, LOCALTIMESTAMP) ON CONFLICT DO NOTHING")
    int insertIfAbsent(@Param("cartId") Long cartId, @Param("userId") Long userId);

    /**
     * 이 이벤트를 처음 처리하면 원장에 남기고 1, 이미 있으면 0(V6) — 같은 트랜잭션의 처리와 함께 커밋 · 롤백된다. 동시에 둘이 와도 하나만 1.
     * [JA] このイベントを初めて処理するなら台帳に残して 1、既にあれば 0。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "insert into cart.processed_event (event_id, processed_at) values (:id, now())"
            + " on conflict (event_id) do nothing", nativeQuery = true)
    int markProcessed(@org.springframework.data.repository.query.Param("id") String eventId);
}
