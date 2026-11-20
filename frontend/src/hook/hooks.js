import { useRef, useEffect, useCallback, useState } from "react";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import { useNavigate } from "react-router-dom";
import { useAuthContext } from "store/AuthContext";
import api, { createChatRoom, getChatRoomList, getRoomMessages, getReviews, getCoupons, getOrder } from "util/api";
import { getRequest, postRequest, getAddresses, getOrders, loadCart, loadCheckOut, throwWithoutData } from "util/api";
import tokenUtil from "util/tokenUtil";
import { clearPendingLogout, markPendingLogout } from "util/pendingLogout";
import { translate } from "i18n/I18nProvider";

export function useReRenderer() {
  const [, setState] = useState({});
  return useCallback(() => setState({}), []);
}

/**
 * 감시 대상이 화면에 들어오면 다음 페이지를 불러온다.
 *
 * <p><b>예전 코드는 동작한 적이 없다.</b> 첫 줄이 `useRef < HTMLDivElement > (null)` 이었는데,
 * 이건 TypeScript 의 제네릭 문법을 .js 파일에 그대로 붙여 넣은 것이다. JS 파서는 이걸
 * 제네릭이 아니라 <b>비교 연산 두 번</b>으로 읽는다 — `(useRef < HTMLDivElement) > (null)`.
 * 그래서 ref 에는 DOM 참조가 아니라 <b>불리언 false</b> 가 들어갔다. 결과적으로
 * `ref.current` 는 항상 undefined 라 IntersectionObserver 가 만들어지지 않았고, 이 ref 를
 * 실제 엘리먼트에 붙이면 React 가 "Function components cannot be given refs" 로 넘어간다.
 * 무한 스크롤이 조용히 죽어 있었다.
 *
 * <p>의존성에서 `ref` 도 뺐다. useRef 가 돌려주는 객체는 항상 같은 참조라 의존성으로서
 * 의미가 없고, 있으면 "ref 가 바뀌면 다시 붙는다"는 잘못된 인상을 준다.
 *
 * <p>[JA] 監視対象が画面に入ったら次のページを読み込む。
 * <b>以前のコードは動いたことがない。</b> 最初の行が `useRef < HTMLDivElement > (null)` だったが、
 * これは TypeScript のジェネリック構文を .js ファイルにそのまま貼り付けたものだ。JS パーサーはこれを
 * ジェネリックではなく <b>比較演算二回</b>として読む — `(useRef < HTMLDivElement) > (null)`。
 * そのため ref には DOM 参照ではなく <b>ブーリアン false</b> が入った。結果として
 * `ref.current` は常に undefined で IntersectionObserver が作られず、この ref を
 * 実際の要素に付けると React が "Function components cannot be given refs" で落ちる。
 * 無限スクロールが静かに死んでいた。
 * 依存配列から `ref` も外した。useRef が返すオブジェクトは常に同じ参照なので依存としての
 * 意味がなく、あると「ref が変われば付け直す」という誤った印象を与える。
 */
export function useInfiniteScroll({ hasNextPage, fetchNextPage }) {

  const ref = useRef(null);

  const handleIntersect = useCallback(([entry]) => {
    if (entry?.isIntersecting && hasNextPage) {
      fetchNextPage();
    }
  }, [fetchNextPage, hasNextPage]);

  useEffect(() => {
    const target = ref.current;
    if (!target) return undefined;

    const observer = new IntersectionObserver(handleIntersect, { threshold: 0.6 });
    observer.observe(target);
    return () => observer.disconnect();
  }, [handleIntersect]);

  return ref;

}

export const useLogout = (isMultipleLogin = false) => {
  const navigate = useNavigate();
  const { logout } = useAuthContext();

  const { mutate: logoutOp, isPending } = useMutation({
    mutationFn: () =>
      // 예전에는 `api.defaults.headers.post["MultiLogin"]` 을 여기서 바꿨다. 그건 axios
      // 인스턴스의 **전역 기본값**이라, 로그아웃 한 번이 이후 모든 POST 요청의 헤더를
      // 바꿔 놓고 되돌리지 않았다. 이 요청에만 필요한 헤더는 이 요청에만 싣는다.
      // [JA] 以前は `api.defaults.headers.post["MultiLogin"]` をここで変えていた。それは axios
      //      インスタンスの **グローバルデフォルト**なので、一度のログアウトが以降のすべての POST リクエストのヘッダーを
      //      変えたまま戻さなかった。このリクエストにだけ必要なヘッダーはこのリクエストにだけ載せる。
      postRequest('/user-service/api/auth/logout', null, undefined, {
        headers: { MultiLogin: isMultipleLogin ? "true" : "false" },
      }),
    onSuccess: () => {
      clearPendingLogout();
      tokenUtil.remove();
      logout();
      navigate('/signin');
      toast.success(translate("logout.done"));
    },
    // 🐛 예전에는 실패하면 안내만 띄우고 화면은 로그인 상태로 남았다 — 공용 PC 의 사용자는 로그아웃된 줄 알고 자리를 뜬다.
    //    화면(과 다른 탭)은 지금 로그아웃하고, 서버 로그아웃은 다음 접속 때 마무리한다(util/pendingLogout, docs/68 §13).
    // [JA] 以前は失敗すると案内だけで画面はログイン状態のままだった。画面(と他のタブ)は今ログアウトし、サーバーのログアウトは次回に仕上げる。
    onError: (err) => {
      markPendingLogout();
      tokenUtil.remove();
      logout();
      navigate('/signin');
      toast.error(translate("logout.local_only"), { id: "logoutLocalOnly" });
      console.error(err.response?.data?.message);
    },
  });

  return { logout: logoutOp, isPending };
};



/** 리뷰 목록을 다시 읽는 주기 — 다른 사람이 누른 좋아요·새 리뷰가 보이게. 탭이 보일 때만 돈다. */
// [JA] レビュー一覧を読み直す周期 — 他の人が押したいいね・新しいレビューが見えるように。タブが見えているときだけ回る。
export const REVIEWS_REFRESH_MS = 30_000;

export const useFetchReviews = (productId) => {

  const { data, isPending, refetch, isSuccess } = useQuery({
    queryKey: [`reviews/${productId}`],
    queryFn: async () => {
      const res = await getReviews(productId);
      return res;
    },
    throwOnError: throwWithoutData,
    // 예전에는 페이지를 열 때 한 번만 읽었다 — 다른 사람이 누른 좋아요는 새로고침해야 보였다. 좋아요 수는 몇 초 늦어도 되는
    // 값이라 푸시(SSE) 대신 주기적으로 다시 읽는다. 탭이 가려져 있으면 쉬고(refetchIntervalInBackground 기본 false),
    // 돌아오면 바로 읽는다(refetchOnWindowFocus).
    // [JA] 以前はページを開いたとき一度だけ読んでいた — 他の人が押したいいねはリロードしないと見えなかった。いいね数は数秒遅れてもよい
    //      値なのでプッシュ(SSE)ではなく定期的に読み直す。タブが隠れていれば休み(refetchIntervalInBackground のデフォルト false)、
    //      戻ればすぐ読む(refetchOnWindowFocus)。
    refetchInterval: REVIEWS_REFRESH_MS,
    refetchOnWindowFocus: true,
    // 이 주기적 읽기가 실패해도 알림을 띄우지 않는다 — 30초마다 "새로 읽지 못했습니다" 가 뜬다. 가진 목록을 그대로 둔다.
    // [JA] この定期読み込みが失敗しても通知を出さない — 30 秒ごとに「読み直せませんでした」が出てしまう。持っている一覧をそのままにする。
    meta: { silentRefetch: true },
  });

  return {
    reviews: data,
    isPending: isPending,
    isSuccess: isSuccess,
    updateFetchReviews: refetch,
  };
};

export const useFetchAddresses = (userId) => {

  const queryClient = useQueryClient();

  const invalidate = async () => {
    await queryClient.invalidateQueries({
      queryKey: [`address/${userId}`],
      refetchType: 'active',
    },
    );
  }

  const { data, isPending, isSuccess, refetch } = useQuery({
    queryKey: [`address/${userId}`],
    queryFn: async () => {
      const res = await getAddresses();

      return res;
    },
    throwOnError: throwWithoutData,
  });

  return {
    addressesData: data,
    isPendingAddress: isPending,
    isSuccessAddress: isSuccess,
    updateFetchAddresses: refetch,
    invalidate
  };
};




export const useFetchCoupons = (email) => {

  const { data, isPending, refetch, isSuccess } = useQuery({
    queryKey: [`coupons/${email}`],
    queryFn: async () => {
      const res = await getCoupons();
      return res;
    },
    throwOnError: throwWithoutData,
  });

  return {
    coupons: data,
    isPending,
    isSuccess,
    updateFetchCoupons: refetch
  };
};

export const useFetchOrders = (filter, email) => {

  const { data, isPending, refetch, isSuccess } = useQuery({
    queryKey: [`${email}/${filter}`],
    queryFn: async () => {
      const res = await getOrders(filter);
      return res;
    },
    throwOnError: throwWithoutData,
  });

  return {
    orders: data,
    isPending,
    isSuccess,
    updateFetchOrders: refetch,
  };
};

export const useFetchCart = (email, cartStatus, cartId) => {

  const { data, isPending, status, isSuccess, refetch } = useQuery({
    queryKey: [ 'cart', cartId, cartStatus],
    queryFn: async () => {
      const res = await loadCart();
      
      return res;
    },
    throwOnError: throwWithoutData,
  });

  return {
    cartData: data,
    isPendingCart: isPending,
    status,
    isSuccessCart: isSuccess,
    updateCart: refetch,
  };
};

export const useFetchCheckOut = (email, cartId, cartStatus) => {


  const { data, isPending, isSuccess, refetch, isError, status } = useQuery({
    queryKey: [{ cartId: cartId, cartStatus: cartStatus }, `checkout/${email}`],
    queryFn: async () => {
      const res = await loadCheckOut();
      return res;
    },
    refetchOnMount: true,
    throwOnError: throwWithoutData,
  });


  return {
    checkoutData: data,
    isPendingCheckOut: isPending,
    isSuccessCheckOut: isSuccess,
    status: status,
    isError,
    updateFetchCheckOut: refetch
  };
};


export const useFetchOrder = (orderId, email) => {


  const { data, isPending, isSuccess, isError, status, refetch } = useQuery({
    queryKey: [`order/${email}/${orderId}`],
    queryFn: async () => {
      const res = await getOrder(orderId);
      return res;
    },
    throwOnError: throwWithoutData,
  });


  return {
    orderData: data,
    isPendingOrder: isPending,
    isSuccessOrder: isSuccess,
    status: status,
    isError,
    // 결제·환불 뒤 주문을 다시 읽는다. window.location.reload() 대신 — 전체 새로고침은
    // 주소의 쿼리(?stripe=success…)까지 다시 태워 확인 요청이 반복된다(StripeReturn 주석).
    // [JA] 決済・返金後に注文を読み直す。window.location.reload() の代わりに — 全体リロードは
    //      アドレスのクエリ(?stripe=success…)まで再び乗せ確認リクエストが繰り返される(StripeReturn のコメント)。
    refetchOrder: refetch,
  };
};


