package com.project.userservice.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.model.ShippingAddress;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.ShippingAddressRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.dto.AddressDto;
import com.project.userservice.security.request.AddressRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Transactional
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressService {

    private final ShippingAddressRepository shippingAddressRepository;
    private final UserRepository userRepository;

    public AddressDto saveShippingAddress(AddressRequest request, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 🔒 사람을 가리키는 값은 로그에 남기지 않는다. username 은 사용자가 고른
        //    이름이거나 소셜 계정의 provider_providerId 이고, 둘 다 그 사람을 특정한다.
        //    다른 경로(보안 알림·기기 인식)가 userId 만 남기는 것과 기준을 맞춘다.
        //    배송지 저장은 자주 일어나므로 INFO 한 줄이 그대로 쌓인다.
        // [JA] 🔒 人を指す値はログに残さない。username はユーザーが選んだ名前かソーシャルアカウントの provider_providerId で、どちらもその人を特定する。
        //      他の経路(セキュリティ通知・機器認識)が userId だけを残すのと基準を揃える。配送先の保存は頻繁なので INFO 一行がそのまま積もる。
        log.debug("배송지 저장: userId={}", user.getUserId());

        // 매핑은 ShippingAddress.from 하나뿐이다 — 예전에는 빌더로 채운 직후 deepCopy 가
        // 같은 필드를 다시 덮었다(ShippingAddress.from 주석 참조).
        // [JA] マッピングは ShippingAddress.from 一つだけ — 以前はビルダーで埋めた直後に deepCopy が同じフィールドを上書きしていた(ShippingAddress.from のコメント参照)。
        ShippingAddress address = ShippingAddress.from(request.getAddress());

        // 새 주소를 기본으로 저장하면 지금 기본을 먼저 끈다 — 아니면 '사용자당 기본 하나' 인덱스에 걸린다(아래 deactivateCurrentDefault).
        // [JA] 新しい住所を既定として保存するなら今の既定を先に外す — でないと「ユーザーごとに既定は一つ」インデックスに引っかかる(下の deactivateCurrentDefault)。
        if (address.isActive()) {
            deactivateCurrentDefault(user.getUserId(), null);
        }
        address.setUser(user);

        address = shippingAddressRepository.save(address);
        log.debug("배송지 저장 완료: addressId={}", address.getShippingAddressId());

        // 양방향 연관을 메모리에서도 맞춰 둔다. 소유 측은 address.user 라 DB 에는 이미
        // 반영됐고, 이 줄은 같은 트랜잭션 안에서 user 를 다시 읽는 코드가 방금 만든
        // 주소를 보게 하려는 것이다.
        // [JA] 双方向関連をメモリ上でも揃えておく。所有側は address.user なので DB には既に反映済みで、この行は同じトランザクション内で
        //      user を読み直すコードが今作った住所を見られるようにするためのもの。
        user.getShippingAddresses().add(address);

        // 🧹 여기 있던 `userRepository.save(user); // prevent java.stackoverflow` 를 지웠다.
        //    주석의 설명은 사실이 아니다. user 는 바로 위에서 findByUsername 으로 읽은
        //    **영속 상태 엔티티**이고 이 클래스는 @Transactional 이라, 변경은 커밋 시점에
        //    더티 체킹으로 반영된다. save 는 아무 일도 하지 않으면서 SELECT/UPDATE 를
        //    한 번 더 유발할 뿐이다. 스택오버플로와는 아무 관계가 없다 — 그건 양방향
        //    연관을 toString/직렬화할 때 나는 증상이고, 그 방어는 @JsonBackReference 가
        //    이미 하고 있다.
        // [JA] 🧹 ここにあった `userRepository.save(user); // prevent java.stackoverflow` を消した。コメントの説明は事実ではない。
        //      user は直前に findByUsername で読んだ**永続状態のエンティティ**で、このクラスは @Transactional なので、変更はコミット時にダーティチェックで反映される。
        //      save は何もせず SELECT/UPDATE を一回余計に起こすだけ。スタックオーバーフローとは無関係 — それは双方向関連を toString/シリアライズするときの症状で、
        //      その防御は @JsonBackReference が既にしている。

        AddressDto result = new AddressDto();
        AddressDto.deepCopyShippingAddressDto(result, address);

        return result;
    }

    /**
     * 기본 배송지를 고른다.
     *
     * <p>🔴 <b>예전에는 두 가지가 잘못돼 있었다.</b>
     *
     * <ol>
     *   <li>현재 기본 배송지를 {@code findByActive(true)} 로 찾았다 — {@code user_id} 조건이
     *       없는 <b>전 테이블 조회</b>다. 사용자가 둘 이상 기본 배송지를 가지면 Optional
     *       쿼리가 두 행을 만나 {@code IncorrectResultSizeDataAccessException} → 400 이 되고,
     *       그 전이라도 전체에 active 가 하나뿐일 때 그것이 남의 것이면 <b>남의 기본
     *       배송지를 꺼 버렸다.</b></li>
     *   <li>사용자의 배송지를 전부 불러 자바에서 반복하며 id 를 대조했다. 배송지가 N 개면
     *       N 개를 다 읽는다. 쿼리 하나로 충분하다.</li>
     * </ol>
     *
     * <p>[JA] 既定の配送先を選ぶ。
     * <p>🔴 以前は二つの誤りがあった: (1) 現在の既定を findByActive(true) で探していた — user_id 条件のない<b>全テーブル検索</b>。
     * 既定が二つ以上あると Optional クエリが二行に当たり IncorrectResultSizeDataAccessException → 400 になり、その前でも全体で active が一つだけで
     * それが他人のものなら<b>他人の既定配送先を外していた</b>。(2) ユーザーの配送先を全部読みジャバで回して id を照合していた。N 件なら N 件全部読む。クエリ一つで足りる。
     */
    public AddressDto selectShipAddress(String username, String addressId) {
        // 🐛 예전: 없는 사용자 · 잘못된 id · 남의(없는) 주소 모두 null → 200 빈 본문이었다 — 화면은 "기본 배송지로 정했습니다" 로 읽었다.
        //    이제 잘못된 id 는 400, 없거나 남의 것은 404(어느 쪽인지 말하지 않는다).
        // [JA] 以前はどの失敗も null → 200 の空本文だった。今は不正な id は 400、ない · 他人のものは 404。
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            log.warn("selectShipAddress: 사용자를 찾을 수 없다");
            throw new java.util.NoSuchElementException(ADDRESS_NOT_FOUND);
        }

        Long targetId = parseAddressId(addressId);
        if (targetId == null) {
            throw new IllegalArgumentException("주소 id 가 올바르지 않습니다.");
        }

        // 내 것 중에서 찾는다. 소유권 확인이 쿼리에 들어 있으므로 빠뜨릴 수가 없다.
        // [JA] 自分のものの中から探す。所有権の確認がクエリに入っているので漏らしようがない。
        ShippingAddress address = shippingAddressRepository
                .findByShippingAddressIdAndUser_UserId(targetId, user.getUserId())
                .orElse(null);

        if (address == null) {
            log.warn("selectShipAddress: 본인의 배송지가 아니거나 존재하지 않는다");
            throw new java.util.NoSuchElementException(ADDRESS_NOT_FOUND);
        }

        // 이 사용자의 현재 기본 배송지만 끈다.
        // [JA] このユーザーの現在の既定配送先だけを外す。
        deactivateCurrentDefault(user.getUserId(), targetId);

        address.setActive(true);
        address = shippingAddressRepository.save(address);

        AddressDto dto = new AddressDto();
        AddressDto.deepCopyShippingAddressDto(dto, address);
        return dto;
    }

    /**
     * 주소 id 파싱.
     *
     * <p>예전에는 {@code Long.parseLong(addressId)} 를 그대로 불렀다. 숫자가 아닌 값이 오면
     * {@code NumberFormatException} 이 그대로 올라가 500 이 된다 — 잘못된 요청은 400 이다.
     * (컨트롤러가 RuntimeException 을 400 으로 바꾸기는 하지만, 메시지가
     * "For input string: ..." 이라 무엇이 문제인지 알 수 없다.)
     *
     * <p>[JA] 住所 id のパース。
     * <p>以前は Long.parseLong(addressId) をそのまま呼んでいた。数字でない値が来ると NumberFormatException がそのまま上がり 500 になる — 不正なリクエストは 400 である。
     * (コントローラが RuntimeException を 400 に変えはするが、メッセージが "For input string: ..." で何が問題か分からない。)
     */
    static final String ADDRESS_NOT_FOUND = "배송지를 찾을 수 없습니다.";

    private Long parseAddressId(String addressId) {
        try {
            return Long.parseLong(addressId);
        } catch (NumberFormatException e) {
            log.warn("주소 id 가 숫자가 아니다");
            return null;
        }
    }

    /**
     * 지금 기본 배송지를 끄고 <b>바로 DB 에 반영</b>한다.
     *
     * <p>🐛 예전: 두 엔티티를 바꾼 뒤 커밋 때 함께 flush 했다. Hibernate 는 읽은 순서대로 UPDATE 를 내보내서, 새 기본
     * (먼저 읽음, active=true)이 옛 기본(active=false)보다 먼저 갔다 — 그 순간 같은 사용자의 기본이 둘이라
     * V7 의 부분 유니크 인덱스 {@code uq_shipping_address_active_per_user} 가 즉시 거절했다. <b>기본 배송지 변경이 늘 실패했다</b>
     * (실제 PostgreSQL 로 재현 — UserServiceApplicationTests).
     */
    // [JA] 今の既定の配送先をオフにし、すぐ DB に反映する。以前は二つの更新を読み込み順に flush し、新しい既定が先に出て
    //      部分ユニークインデックスに即座に拒否されていた — 既定の配送先の変更が常に失敗していた。
    private void deactivateCurrentDefault(Long userId, Long keepId) {
        shippingAddressRepository.findByUser_UserIdAndActiveTrue(userId)
                .filter(current -> !Objects.equals(current.getShippingAddressId(), keepId))
                .ifPresent(current -> {
                    current.setActive(false);
                    shippingAddressRepository.saveAndFlush(current);
                });
    }

    @Transactional(readOnly = true)
    public List<AddressDto> getShipAddresses(String username) {
        // 🔒 username 은 로그에 남기지 않는다 — 사용자를 특정하는 값이다.
        //    필요한 것은 "누구의 주소를 몇 건 읽었나" 이고, 그건 userId 로 충분하다.
        // 없는 사용자는 빈 목록이다(이메일 · username 을 남기면 "그 계정이 있는지" 가 로그로 샌다).
        // 🐛 예전에는 try/catch(RuntimeException) 로 감싸 **DB 장애까지** 빈 목록으로 삼켰다 — 결제 화면이 "배송지가 없습니다" 를
        //    보여 손님이 주소를 다시 넣게 했다. 이제 없는 사용자만 빈 목록이고, 다른 오류는 그대로 올라간다(5xx).
        // [JA] 🔒 username はログに残さない — 利用者を特定する値だ。必要なのは「誰の住所を何件読んだか」で、それは userId で足りる。
        //      存在しないユーザーは空リストだ(メール · username を残すと「そのアカウントがあるか」がログから漏れる)。
        //      🐛 以前は try/catch(RuntimeException) で包んで**DB 障害まで**空リストとして飲み込んでいた — 決済画面が「配送先がありません」を
        //      見せて客に住所を入れ直させた。今は存在しないユーザーだけ空リストで、他のエラーはそのまま上がる(5xx)。
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            log.warn("배송지 조회: 사용자를 찾지 못해 빈 목록을 돌려준다");
            return Collections.emptyList();
        }
        // JOIN FETCH 로 User 와 ShippingAddress 를 함께 로드
        // [JA] JOIN FETCH で User と ShippingAddress を一緒にロード
        List<ShippingAddress> addresses = shippingAddressRepository.findByUser_UserIdWithUser(user.getUserId());
        log.debug("배송지 조회: userId={} count={}", user.getUserId(), addresses.size());

        // 스트림 API를 사용하여 변환 로직을 간결하게 작성
        // [JA] ストリーム API で変換ロジックを簡潔に書く
        List<AddressDto> addressDtos = addresses.stream()
                .map(address -> {
                    AddressDto dto = new AddressDto();
                    AddressDto.deepCopyShippingAddressDto(dto, address);
                    return dto;
                })
                .collect(Collectors.toList());

        return addressDtos;
    }

    /**
     * 배송지를 고친다 — <b>본인 것만</b>, 그리고 기본 배송지 여부는 그대로.
     *
     * <p>지금까지 주소는 등록과 삭제만 됐다. 나라 하나를 잘못 골랐으면(목록에서 두 한국이
     * 나란히 있어 실제로 그랬다, docs/25 §11.9) 지우고 다시 넣어야 했고, 그러면 기본 배송지
     * 표시도 함께 사라졌다.
     *
     * <p>소유권 판정은 삭제와 같다 — 내 것 중에서 찾고, 없으면 남의 것인지 없는 것인지
     * 구분해 말하지 않는다. 값은 {@link ShippingAddress#applyFrom} 이 옮긴다(필드 목록은 거기
     * 하나뿐이다). {@code active} 는 옮기지 않는다 — 그것은 {@link #selectShipAddress} 의 일이다.
     *
     * <p>과거 주문의 배송지는 바뀌지 않는다. 주문은 주문 시점의 주소를 복사해 갖는다.
     *
     * <p>[JA] 配送先を修正する — <b>本人のものだけ</b>、既定かどうかはそのまま。
     * <p>これまで住所は登録と削除しかできなかった。国を一つ間違えたら(一覧で二つの韓国が並んでいて実際にそうなった、docs/25 §11.9)
     * 消して入れ直すしかなく、そうすると既定の印も一緒に消えた。所有権の判定は削除と同じ — 自分のものから探し、なければ他人のものか存在しないのかを区別して言わない。
     * 値は ShippingAddress#applyFrom が移す(フィールド一覧はそこだけ)。active は移さない — それは selectShipAddress の仕事。過去の注文の配送先は変わらない。注文は注文時点の住所をコピーして持つ。
     */
    public AddressDto updateShippingAddress(String username, String addressId, AddressDto changes) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            log.warn("updateShippingAddress: 사용자를 찾을 수 없다");
            throw new AccessDeniedException("본인의 배송지가 아닙니다.");
        }

        Long targetId = parseAddressId(addressId);
        if (targetId == null) {
            throw new IllegalArgumentException("주소 id 가 올바르지 않습니다.");
        }

        ShippingAddress target = shippingAddressRepository
                .findByShippingAddressIdAndUser_UserId(targetId, user.getUserId())
                .orElse(null);
        if (target == null) {
            log.warn("updateShippingAddress: 본인의 배송지가 아니거나 존재하지 않는다");
            throw new AccessDeniedException("본인의 배송지가 아닙니다.");
        }

        target.applyFrom(changes);
        target = shippingAddressRepository.save(target);
        log.debug("배송지 수정: userId={} addressId={}", user.getUserId(), targetId);

        AddressDto dto = new AddressDto();
        AddressDto.deepCopyShippingAddressDto(dto, target);
        return dto;
    }

    /**
     * 배송지를 지운다.
     *
     * <p>🔴 <b>예전에는 소유권 검사가 없었다(IDOR).</b>
     *
     * <pre>
     *   Optional&lt;User&gt; user = userRepository.findByUsername(username);
     *   if (!user.isPresent()) return;
     *
     *   Optional&lt;ShippingAddress&gt; deleteAddress =
     *           shippingAddressRepository.findById(Long.parseLong(addressId));
     *   if (deleteAddress.isPresent()) {
     *       user.get().getShippingAddresses().remove(deleteAddress.get());  // 남의 것이면 무동작
     *       shippingAddressRepository.delete(deleteAddress.get());          // ← 그래도 지운다
     *   }
     * </pre>
     *
     * 사용자를 조회는 하지만 <b>그 주소가 이 사용자 것인지 대조하지 않았다.</b> 컬렉션에서
     * 빼는 {@code remove} 는 남의 것이면 조용히 지나가고, 그 다음 줄의 {@code delete} 가
     * 그냥 실행된다. 로그인한 아무나 주소 id 하나만 알면 남의 배송지를 지울 수 있었다.
     *
     * <p>TSID 라 id 추측이 어렵다는 것은 완화 요인이지 대책이 아니다. 같은 파일의
     * {@code selectShipAddress} 는 (방식은 비효율적이었지만) 소유권을 확인하고 있었다 —
     * 삭제만 빠져 있었다.
     *
     * <p>주소를 지워도 <b>과거 주문의 배송지는 바뀌지 않는다.</b> 주문은 주문 시점의 주소를
     * {@code OrderAddress} 로 복사해 갖는다(order-service OrderDataService).
     *
     * <p>[JA] 配送先を削除する。
     * <p>🔴 <b>以前は所有権の検査がなかった(IDOR)。</b> ユーザーを検索はするが<b>その住所がこのユーザーのものか照合しなかった。</b>
     * コレクションから外す remove は他人のものなら黙って素通りし、次の行の delete がそのまま実行される。ログインした誰でも住所 id 一つ知っていれば他人の配送先を消せた。
     * TSID で id の推測が難しいのは緩和要因であって対策ではない。同じファイルの selectShipAddress は(方式は非効率だったが)所有権を確認していた — 削除だけ抜けていた。
     * 住所を消しても<b>過去の注文の配送先は変わらない。</b> 注文は注文時点の住所を OrderAddress にコピーして持つ(order-service OrderDataService)。
     */
    public void deleteShippingAddress(String username, String addressId) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            log.warn("deleteShippingAddress: 사용자를 찾을 수 없다");
            return;
        }

        Long targetId = parseAddressId(addressId);
        if (targetId == null) {
            return;
        }

        // 내 것 중에서 찾는다 — 없으면 남의 것이거나 존재하지 않는다. 둘을 구분해
        // 알려 주지 않는다(존재 여부가 새면 그 자체가 정보다).
        // [JA] 自分のものの中から探す — なければ他人のものか存在しない。二つを区別して知らせない(存在するかどうかが漏れればそれ自体が情報になる)。
        ShippingAddress target = shippingAddressRepository
                .findByShippingAddressIdAndUser_UserId(targetId, user.getUserId())
                .orElse(null);

        if (target == null) {
            log.warn("deleteShippingAddress: 본인의 배송지가 아니거나 존재하지 않는다");
            throw new AccessDeniedException("본인의 배송지가 아닙니다.");
        }

        user.getShippingAddresses().remove(target);
        shippingAddressRepository.delete(target);
    }
}