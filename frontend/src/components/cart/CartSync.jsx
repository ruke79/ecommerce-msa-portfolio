import { useEffect, useRef } from "react";
import { useDispatch, useStore } from "react-redux";
import { STATUS, useAuthContext } from "store/AuthContext";
import { forgetOwner, syncedFromServer } from "../../redux/CartSlice";
import { loadCart, putCartItem } from "util/api";

/**
 * 로그인하면 이 브라우저의 장바구니를 **서버 장바구니와 한 번 맞춘다**(docs/40 0-b). 화면을 그리지 않는다.
 *
 * <p>서버가 장바구니의 기준이 된 뒤로, 로그인한 사용자의 담기·수량·삭제는 바로 서버에 반영되고 화면은 서버의 응답으로 바뀐다.
 * 남은 일은 경계 두 곳이다.
 * <ul>
 *   <li><b>로그인</b> — 주인 없는 줄(로그인 전에 담은 것, 또는 이번 배포 전의 옛 장바구니)은 서버에 올려 합친다. 다른 사람의
 *       장바구니(같은 브라우저에서 앞 사람이 쓰던 것)는 올리지 않고 버린다 — 예전에는 그대로 보였고 체크아웃하면 내 주문이 됐다.
 *       그 뒤 서버 장바구니를 받는다 — 다른 기기에서 담은 것이 여기서 보인다.</li>
 *   <li><b>로그아웃</b> — 서버 장바구니의 사본을 지운다(서버에는 남아 있다). 다음 사람에게 보이지 않는다.</li>
 * </ul>
 *
 * <p>새로고침 직후에는 토큰이 없다(메모리에만 둔다) — 인증 상태가 PENDING 인 동안은 아무것도 하지 않는다. 로그아웃은 IDLE 이다.
 *
 * <p>[JA] ログインするとこのブラウザのカートを **サーバーのカートと一度合わせる**(docs/40 0-b)。画面は描かない。
 * サーバーがカートの基準になってから、ログインしたユーザーの追加 · 数量 · 削除はすぐサーバーに反映され、画面はサーバーの応答で変わる。
 * 残る仕事は境界の二か所である。
 * ・ログイン — 持ち主のない行(ログイン前に入れたもの、または今回のデプロイ前の古いカート)はサーバーに上げて合わせる。他人のカート
 * (同じブラウザで前の人が使っていたもの)は上げずに捨てる — 以前はそのまま見え、チェックアウトすると自分の注文になった。
 * その後サーバーのカートを受け取る — 別の端末で入れたものがここで見える。
 * ・ログアウト — サーバーカートのコピーを消す(サーバーには残っている)。次の人には見えない。
 * 再読み込み直後はトークンがない(メモリにだけ置く) — 認証状態が PENDING の間は何もしない。ログアウトは IDLE である。
 */
const CartSync = () => {
    const { user, status } = useAuthContext();
    const store = useStore();
    const dispatch = useDispatch();
    const syncedFor = useRef(null);
    const uid = user?.userId ?? null;
    // 비동기 작업이 끝났을 때의 인증 상태 — 그사이 로그아웃했거나 다른 사람이 로그인했으면 결과를 버린다.
    // [JA] 非同期処理が終わったときの認証状態 — その間にログアウトしたか別の人がログインしていたら結果を捨てる。
    const current = useRef({ status, uid });
    current.current = { status, uid };

    useEffect(() => {
        if (status === STATUS.IDLE) {
            syncedFor.current = null;
            dispatch(forgetOwner());
            return;
        }
        if (status !== STATUS.SUCCEEDED || !uid || syncedFor.current === uid) return;
        syncedFor.current = uid;

        const stillMe = () => current.current.status === STATUS.SUCCEEDED && current.current.uid === uid;
        (async () => {
            // 지금 상태를 이 순간에 읽는다 — 렌더 때의 값이면 그사이 다른 화면이 바꾼 것을 놓친다.
            // [JA] 今の状態をこの瞬間に読む — レンダー時の値ではその間に別の画面が変えたものを見逃す。
            const { cartItems, ownerId } = store.getState().cart;
            const mine = ownerId === uid;
            const toUpload = ownerId == null ? cartItems : [];
            for (const item of toUpload) {
                try {
                    await putCartItem(stripLocal(item));
                } catch {
                    // 한 줄이 안 올라가도(판매 종료 등) 나머지는 계속 — 서버 장바구니가 기준이다.
                    // [JA] 一行が上がらなくても(販売終了など)残りは続ける — サーバーのカートが基準である。
                }
            }
            try {
                const items = await loadCart();
                if (stillMe()) dispatch(syncedFromServer({ items, ownerId: uid }));
            } catch {
                // 서버를 못 읽었다 — 내 장바구니였으면 보던 것을 그대로 두고, 아니면(남의 것·올린 옛 것) 비운다.
                // [JA] サーバーを読めなかった — 自分のカートなら見ていたものをそのままにし、そうでなければ(他人のもの · 上げた古いもの)空にする。
                if (stillMe() && !mine) dispatch(syncedFromServer({ items: toUpload, ownerId: null }));
                if (syncedFor.current === uid) syncedFor.current = null;   // 다음 인증 변화 때 다시 ／ [JA] 次の認証変化のときにもう一度
            }
        })();
    }, [status, uid, store, dispatch]);

    return null;
};

/** 화면에서만 쓰는 표시는 서버에 보내지 않는다. */
// [JA] 画面だけで使う表示はサーバーに送らない。
const stripLocal = ({ stockStatus, onHold, ...item }) => item;

export default CartSync;
