import Layout from "components/profile/Layout";
import { useMoney } from "hook/useMoney";
import { useTranslation } from "i18n/I18nProvider";

import { ordersLinks } from "components/profile/sidebar/ordersLinks";
import { Link, useLoaderData, useSearchParams} from "react-router-dom";
import { CheckBadgeIcon, EyeIcon, XCircleIcon } from "@heroicons/react/24/solid";
import slugify from "slugify";
import api, { getRequest } from "util/api";
import toast from "react-hot-toast";
import { useAuthContext } from "store/AuthContext";
import tokenUtil from "util/tokenUtil";
import { useInfiniteScroll } from "hook/hooks";
import { useInfiniteQuery } from "@tanstack/react-query";
import { getOrdersPage } from "util/api";
import { useMemo } from "react";
import { isPaid } from "util/orderStatus";
import { displayName } from "util/userDisplay";
import { orderStatusLabel } from "util/statusLabel";


/** 한 번에 받는 주문 수. 서버 상한은 MAX_ORDER_PAGE_SIZE(50)다. */
const PAGE_SIZE = 10;

const Orders = () => {
    const { t } = useTranslation();
    const money = useMoney();

     const searchParams = useSearchParams();     
     const qParam = searchParams[0].get('q');
     //console.log(qParam?.split("__")[1]);
     

     const { user} = useAuthContext();
     const { filter, tab } = useLoaderData();

     

     /**
      * 🐛 예전에는 `useFetchOrders(filter, email)` 가 `GET /order/orders` 로 **주문을
      * 전부** 받았다. 주문이 쌓이는 만큼 응답이 커졌고, 서버는 주문마다 DB 조회 +
      * user-service 호출 + catalog 호출을 다시 했다(N+1). 게다가 상태로 거를 때는
      * 조건에 맞지 않는 주문이 **null 로 목록에 섞여** 왔다 — "Paid" 를 골라도 행 수는
      * 그대로이고 대부분이 빈 줄이었다. 아래 `order?.id` 물음표들이 그 흔적이다.
      *
      * 커서 페이징 엔드포인트로 옮겼다. 서버가 SQL 에서 걸러 주므로 null 이 오지 않는다.
      */
     const {
         data,
         error,
         fetchNextPage,
         hasNextPage,
         isFetchingNextPage,
         isPending,
     } = useInfiniteQuery({
         // 필터가 바뀌면 처음부터 다시 받는다.
         queryKey: ["orders", user.email, filter],
         queryFn: ({ pageParam }) => getOrdersPage({ filter, cursor: pageParam, size: PAGE_SIZE }),
         initialPageParam: undefined,
         getNextPageParam: (lastPage) => {
             const next = lastPage?.nextCursor;
             // 서버가 -1 로 "더 없음" 을 알린다(카탈로그 쪽과 같은 규칙).
             if (next === undefined || next === null || Number(next) === -1) return undefined;
             // 빈 묶음이 오면 멈춘다 — 커서가 제자리면 무한 루프가 된다.
             if (!lastPage?.contents?.length) return undefined;
             return next;
         },
         // 목록 한 묶음을 못 받았다고 화면 전체가 ErrorBoundary 로 날아가면 안 된다.
         throwOnError: false,
     });

     const orders = useMemo(() => {
         const seen = new Set();
         const merged = [];
         for (const page of data?.pages ?? []) {
             for (const order of page?.contents ?? []) {
                 if (order?.id != null && seen.has(order.id)) continue;
                 if (order?.id != null) seen.add(order.id);
                 merged.push(order);
             }
         }
         return merged;
     }, [data]);

     const sentinelRef = useInfiniteScroll({
         hasNextPage: Boolean(hasNextPage) && !isFetchingNextPage,
         fetchNextPage,
     });

     if (isPending) return <div>{t("profile.loading")}</div>;

     
     
        
    return (
        <>
            <Layout user={user} tab={tab} title={t("profile.orders.doc_title", { name: displayName(user) })}>
                <div className="text-center">
                    <h2 className="text-4xl font-bold mb-6">{t("profile.orders.title")}</h2>
                    <nav>
                        <ul className="flex">
                            {ordersLinks.map((order, i) => (
                                <li className={`${qParam?.split("__")[0] == slugify(order.name, {lower:true})  ? 'font-bold border-b' : '' } px-1 flex items-center justify-center hover:font-bold hover:border-b`} key={i}>
                                    <Link to={`?tab=${tab}&q=${slugify(order.name,{lower: true,})}__${order.filter}`}>{t(order.key)}</Link>
                                </li>
                            ))}
                        </ul>
                    </nav>

                    <table className="table_order">
                        <thead>
                            <tr>
                                <td>{t("profile.orders.col.id")}</td>
                                <td>{t("profile.orders.col.products")}</td>
                                <td>{t("profile.orders.col.payment_method")}</td>
                                <td>{t("profile.orders.col.total")}</td>
                                <td>{t("profile.orders.col.paid")}</td>
                                <td>{t("profile.orders.col.status")}</td>
                                <td>{t("profile.orders.col.view")}</td>
                            </tr>
                        </thead>
                        <tbody>
                            {/*
                              * key 가 배열 인덱스였다. 더 불러오면 목록이 늘어나는데
                              * 인덱스 key 는 "N번째 행" 을 가리키므로 React 가 행을
                              * 잘못 재사용한다. 주문 id 를 쓴다.
                              *
                              * `order?.` 물음표가 필요했던 이유는 서버가 거른 자리에
                              * null 을 채워 보냈기 때문이다. 이제 SQL 에서 거르므로
                              * null 이 오지 않는다 — 그래도 방어는 남겨 둔다(응답이
                              * 망가져도 목록 전체가 죽지는 않아야 한다).
                              */}
                            {orders.map((order, i) => (
                                <tr key={order?.id ?? i}>
                                    <td>{order?.id}</td>
                                    <td className="flex">
                                        {(order?.products ?? []).map((p) => (
                                            <img
                                                key={p.id}
                                                src={p.image}
                                                alt={p.name}
                                                width={50}
                                                height={50}
                                                className="rounded-full mx-1"
                                            />
                                        ))}
                                    </td>
                                    <td>{order?.paymentMethod}</td>
                                    <td>{money(Number(order?.total ?? 0))}</td>
                                    <td>
                                        {/*
                                          * 🐛 예전 조건은 `orderStatus === "UNPAID"` 일 때 초록 체크였다.
                                          * 즉 **미결제가 결제 완료처럼** 보였고 결제 완료는 빨간 X 였다.
                                          *
                                          * 상태를 뒤집는 것만으로는 부족하다. OrderStatus 에는 UNPAID 말고도
                                          * NOT_PROCESSED · CANCELED 처럼 "결제되지 않음"인 값이 있다
                                          * (common/constants/OrderStatus.java). 결제된 상태를 열거해 판정한다 —
                                          * 새 상태가 추가돼도 기본값이 "미결제"라 안전한 쪽으로 틀린다.
                                          */}
                                        {order && (isPaid(order.orderStatus) ? (
                                            <CheckBadgeIcon className="w-8 h-8 fill-green-500" />
                                        ) : (
                                            <XCircleIcon className="w-8 h-8 fill-red-500" />
                                        ))}
                                    </td>
                                    <td>{orderStatusLabel(t, order?.orderStatus)}</td>
                                    <td><Link to={`/order/${order?.id}`}> {order && <EyeIcon className="w-8 h-8 fill-slate-500 cursor-pointer hover:fill-slate-800" />}</Link></td>
                                </tr>
                            ))}
                        </tbody>
                    </table>

                    {orders.length === 0 && !error && (
                        <p className="py-8 text-sm text-slate-500">{t("profile.orders.empty")}</p>
                    )}

                    {/* 감시 대상. 화면에 들어오면 다음 묶음을 부른다. */}
                    <div ref={sentinelRef} className="w-full h-1" aria-hidden="true" />

                    {isFetchingNextPage && (
                        <p className="py-4 text-sm text-slate-500">{t("profile.orders.loading_more")}</p>
                    )}

                    {error && (
                        <div className="py-4 flex flex-col items-center gap-2">
                            <span className="text-sm text-red-500">
                                {t("profile.orders.load_more_failed")}
                            </span>
                            <button
                                type="button"
                                onClick={() => fetchNextPage()}
                                className="px-3 py-1.5 text-sm rounded border border-slate-300 hover:bg-slate-50"
                            >
                                {t("profile.retry")}
                            </button>
                        </div>
                    )}

                    {!hasNextPage && orders.length > 0 && (
                        <p className="py-4 text-sm text-slate-400">
                            {t("profile.orders.all_loaded", { count: orders.length })}
                        </p>
                    )}
                </div>
            </Layout>
        </>
    );
};

export default Orders;

export const loader =  async ({params, request}) => {    
            
        const searchParams = new URL(request.url).searchParams;
        
        const tab = Number(searchParams.get('tab')) || 0;
        

        // ⚠️ `get('q').split(...)` 이었다. 쿼리 없이 /profile/orders 로 직접 들어오면
        //    (주소창·북마크·새로고침) get 이 null 이라 TypeError 로 화면이 통째로 죽었다.
        //    사이드바 링크는 q 를 붙이지만, 그것만이 이 화면에 오는 길은 아니다.
        //    기본값은 ordersLinks 의 "All Orders" 와 같은 **빈 문자열**이다. "all" 같은
        //    임의의 값을 넣으면 안 된다 — 서버가 OrderStatus.getStatus(filter) 로 바꾸다
        //    IllegalStateException("Unsupported status all") 로 500 을 낸다. 빈 문자열일
        //    때만 "상태로 거르지 않는다"로 해석한다(OrderDataService.getOrder).
        const filter = searchParams.get('q')?.split("__")[1] ?? "";
                

        return { filter : filter, tab : tab };    
    };


