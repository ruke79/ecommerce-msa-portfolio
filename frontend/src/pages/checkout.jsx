// 🧹 안 쓰는 import 를 걷어냈다. 그중 하나는 단순한 잉여가 아니라 **잘못된 것**이었다:
//    `import { isPending } from "@reduxjs/toolkit"` — 이 파일이 쓰는 isPending 은
//    react-query 가 준 `isPendingCheckOut` 이고, redux-toolkit 의 isPending 은 async
//    thunk 액션 매처다. 이름이 겹쳐 보여 들어온 것으로 보이는데, 누군가 그냥
//    `isPending` 을 쓰면 **언제나 참이 아닌 함수 객체**를 조건으로 쓰게 된다.
//    걷어내야 그 실수를 할 자리가 없어진다.
//
//    함께 지운 것: useEffect·useFetcher·useLoaderData·api·getRequest·tokenUtil·
//    useFetchAddresses·useFetchCart — 전부 이 파일에서 한 번도 호출되지 않는다.
// [JA] 🧹 使わない import を取り除いた。そのうち一つは単なる余剰ではなく**間違ったもの**だった:
//      `import { isPending } from "@reduxjs/toolkit"` — このファイルが使う isPending は react-query がくれる `isPendingCheckOut` で、
//      redux-toolkit の isPending は async thunk のアクションマッチャーである。名前が似て見えて入ってきたようだが、誰かが単に `isPending` を
//      使うと**常に真の関数オブジェクト**を条件に使うことになる。取り除いてこそそのミスをする余地がなくなる。
//      一緒に消したもの: useEffect・useFetcher・useLoaderData・api・getRequest・tokenUtil・useFetchAddresses・useFetchCart — どれもこのファイルで一度も呼ばれていない。
import { memo, useState } from "react";

import { useSelector } from "react-redux";
import Header from "pages/header/Header";
import ShippingPage from "components/checkout/ShippingPage";
import Product from "components/checkout/ChekoutProduct";
import Payment from "components/checkout/Payment";
import Summary from "components/checkout/Summary";
import DotLoaderSpinner from "components/loader/Loading";
import { useAuthContext } from "store/AuthContext";
import { useFetchCheckOut } from "hook/hooks";

const Checkout = () => {

    const { user, addresses, updateAddresses } = useAuthContext();

    const cart = useSelector((state) => { return state.cart; });

    const [selectedAddress, setSelectedAddress] = useState("");
    
    const { checkoutData, isPendingCheckOut, isSuccessCheckOut} =
     useFetchCheckOut(user.email, cart.cartId, cart.status);
    
    // ⚠️ user.defaultPaymentMethod 는 **없는 필드**다. user 는 JWT 에서 만들어지고
    //    (util/jwtUser.js) email·userId·roles 셋뿐이다. 초기값이 undefined 라
    //    Summary 의 가드 `paymentMethod === ""` 를 그대로 통과해, 결제수단을 고르지 않고
    //    주문이 만들어졌다. 그런 주문은 주문 화면에서 아이콘이 하나도 안 그려진다.
    //    프로필에서 기본 결제수단을 받아오게 되면(docs/40 7번) 그때 여기 채운다.
    // [JA] ⚠️ user.defaultPaymentMethod は**存在しないフィールド**である。user は JWT から作られ
    //      (util/jwtUser.js)email・userId・roles の三つだけである。初期値が undefined なので Summary のガード `paymentMethod === ""` をそのまま通過し、
    //      決済手段を選ばずに注文が作られていた。そのような注文は注文画面でアイコンが一つも描かれない。
    //      プロフィールから既定の決済手段を受け取るようになったら(docs/40 7 番)そのときここを埋める。
    const [paymentMethod, setPaymentMethod] = useState("");
    const [totalAfterDiscount, setTotalAfterDiscount] = useState("");    
    const [loading, setLoading] = useState(false);


    if (isPendingCheckOut)  {             
        return <DotLoaderSpinner loading={isPendingCheckOut}/>
    }

    
 
    


    return (
        <>
            {loading && <DotLoaderSpinner loading={loading} />}
            <Header />
            <main className="grid grid-cols-3 md:px-10 mb-10 py-4 gap-8 ">
                <section className="col-span-2">
                    <ShippingPage
                        setSelectedAddress={setSelectedAddress}
                        user={user}                        
                        setLoading={setLoading}
                        profile={false}
                    />
                    {<Product cart={checkoutData}/>}
                </section>

                <section className="col-span-1">
                    <Payment
                        paymentMethod={paymentMethod}
                        setPaymentMethod={setPaymentMethod}                        
                    />
                    <Summary                        
                        selectedAddress={selectedAddress}
                        user={user}
                        cart={checkoutData}
                        paymentMethod={paymentMethod}
                        totalAfterDiscount={totalAfterDiscount}
                        setTotalAfterDiscount={setTotalAfterDiscount}
                        setLoading={setLoading}
                    />
                </section>
            </main>            
        </>
    );
};

export default memo(Checkout);


