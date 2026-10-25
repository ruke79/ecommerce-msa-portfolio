//import paypal from "assets/images/paypal.png"; 
//import credit_card from "assets/images/credit_card.png"; 
//import cash from "assets/images/cash.png"; 

import { useReducer, useEffect, useState } from "react";
import { cancelCart, emptyCart } from "../../redux/CartSlice";
import { postRequest } from "util/api";
import { loadPortOne, loadPortOneV2, newPortOnePaymentId, portOneCustomer, portOneRedirectUrl, portOneStoreDetails, portOneSupportsVirtualAccount, portOneVersion } from "util/portone";
import { portOneFailureText } from "util/portoneError";
import { STORE_CURRENCY, amountString, chargeOf, portOneCurrencyCode } from "util/money";
import { useMoney } from "hook/useMoney";

import { paymentMethods, paymentMethodOf, paymentMethodDescription, paymentMethodName } from "../checkout/paymentMethods";
import { useTranslation } from "i18n/I18nProvider";
import BankTransfer from "components/order/BankTransfer";
import StockHoldNotice from "components/order/StockHoldNotice";
import { useDispatch } from "react-redux";
import { PayPalButtons, usePayPalScriptReducer } from "@paypal/react-paypal-js";
import { useAuthContext } from "store/AuthContext";
import { paymentAttemptKey } from "util/paymentAttempt";

function reducer(state, action) {
  switch (action.type) {
    case "PAY_REQUEST":
      return { ...state, loading: true };
    case "PAY_SUCCESS":
      return { ...state, loading: false, success: true };
    case "PAY_FAIL":
      return { ...state, loading: false, error: action.payload };
    case "PAY_RESET":
      return { ...state, loading: false, success: false, error: "" };
    default:
      // default 가 없으면 모르는 액션에서 undefined 를 반환하고, 다음 렌더에서
      // state.loading 접근이 터진다. 상태 머신은 항상 상태를 돌려줘야 한다.
      return state;
  }
}

const Payment = ({ order, setLoading, setOrder }) => {
  const { t, lang } = useTranslation();
  const money = useMoney();
  const reduxDispatch = useDispatch();

  const { user } = useAuthContext();
  const [{ isPending, options: paypalOptions }, paypalDispatch] = usePayPalScriptReducer();

  // 제공자에게 청구할 통화·금액 — 상점 통화의 total 이 아니다(util/money.js).
  const charge = chargeOf(order);
  // 포트원 결제수단: 카드 또는 가상계좌. 가상계좌는 결제창이 계좌를 발급하고 끝난다 —
  // 입금은 나중이고, 포트원이 입금을 보면 웹훅으로 알려 준다(docs/49 §11).
  const [chosenPortOnePayMethod, setPortOnePayMethod] = useState("card");
  // 가상계좌는 국내(KRW) 채널에만 있다 — 이니시스 일본결제(JPY)는 카드 · 편의점 · 간편결제뿐이라 고를 수 없게 한다(카드 고정).
  // [JA] バーチャル口座は国内(KRW)チャネルだけ — イニシス日本決済(JPY)には無いので選べない(カード固定)。
  const vbankOffered = portOneSupportsVirtualAccount(charge.currency);
  const portOnePayMethod = vbankOffered ? chosenPortOnePayMethod : "card";
  /**
   * 결제 진행 상태. 🐛 {@code loading: true} 로 시작하고, 되돌리는 곳이
   * {@code if (!order.trackingId)} 하나뿐이었다 — 실제 주문에는 trackingId 가 항상 있으니
   * <b>영원히 true</b> 였다. PayPal 버튼은 SDK 의 {@code isPending} 을 보므로 멀쩡했지만,
   * Stripe·포트원 버튼은 {@code disabled={state.loading}} 이라 <b>처음부터 "이동 중…" 인 채
   * 눌리지 않았다.</b> 진행 중은 사용자가 버튼을 누른 뒤부터다(PAY_REQUEST).
   */
  const [state, dispatch] = useReducer(reducer, {
    loading: false,
    error: "",
    success: "",
  });


  // useEffect(() => {

  //   // order.trackingId가 있을 때만 PayPal SDK 옵션 설정
  //   if (order.trackingId) {
  //     paypalDispatch({
  //       type: "resetOptions",
  //       value: {
  //         "client-id": process.env.REACT_APP_PAYPAL_CLIENT_ID,
  //         currency: "USD",
  //       },
  //     });
  //     paypalDispatch({
  //       type: "setLoadingStatus",
  //       value: "pending",
  //     });
  //   }

  // }, [order, paypalDispatch]);

  /**
   * PayPal SDK 의 통화를 이 주문의 청구 통화로 맞춘다.
   *
   * <p>SDK 는 App 에서 한 번 로드되고 통화가 거기 박힌다(initialOptions.currency). 주문의
   * 청구 통화가 다르면(일본 테스트 JPY) 스크립트를 그 통화로 **다시** 로드해야 한다 —
   * createOrder 에 currency_code 만 바꿔 보내면 SDK 통화와 어긋나 PayPal 이 거절한다.
   * 같은 통화면 아무것도 하지 않는다(다시 로드하면 버튼이 깜빡인다).
   */
  useEffect(() => {
    if (order.paymentMethod !== "paypal") return;
    const current = paypalOptions?.currency;
    if (current && current !== charge.currency) {
      paypalDispatch({
        type: "resetOptions",
        value: { ...paypalOptions, currency: charge.currency },
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [order.paymentMethod, charge.currency]);

  /**
   * PayPal 주문을 만든다.
   *
   * <p>여기 쓰이는 {@code order.total} 은 <b>서버가 준 주문 총액</b>이다 — 이 화면은
   * `/api/order/{id}` 로 받은 주문을 그리고 있고, 그 총액은 서버가 장바구니와 쿠폰으로
   * 직접 계산한 값이다(docs/40 #10). 브라우저가 정한 숫자를 PayPal 로 넘기는 것이
   * 아니다. 결제가 끝나면 서버가 실제 결제액을 이 총액과 다시 대조한다.
   */
  async function createOrderHanlder(data, actions) {
    // 결제창을 열기 전에 서버에 결제 시작을 알린다 — 재고 예약 갱신과 쿠폰 잡기(coupon V7). 예전에는 PayPal 만 이 단계를 건너뛰어,
    // 같은 쿠폰의 다른 주문이 결제 중이어도 PayPal 창이 열렸다. 거절되면(400) 이유를 띄우고 창을 열지 않는다.
    if (!(await renewStockHold())) {
      throw new Error(t("payment.error.not_completed"));
    }
    return actions.order
      .create({
        purchase_units: [
          {
            amount: {
              // 청구 통화·금액. JPY 는 소수가 없다 — "1500.00" 은 PayPal 이 거절한다.
              value: amountString(charge.amount, charge.currency),
              currency_code: charge.currency,
            },
          },
        ],
      })
      .then((order_id) => {
        return order_id;
      }
      );
  }

  /**
   * 결제 승인. <b>capture 는 정확히 한 번만 호출한다.</b>
   *
   * <p>예전 코드는 {@code actions.order.capture()} 를 두 번 불렀다. 첫 번째 결과는 쓰지도
   * 않고 버린 뒤 두 번째를 다시 호출했는데, PayPal 은 이미 캡처된 주문의 재캡처를
   * {@code ORDER_ALREADY_CAPTURED} 로 거절한다. 그리고 백엔드로 결제를 기록하는 호출이
   * 하필 <b>두 번째 캡처의 .then 안에</b> 있었다 — 그래서 돈은 빠져나갔는데 주문은 영원히
   * 미결제 상태로 남았다. 사용자에게는 "결제했는데 주문에 반영이 안 된다"로 보인다.
   *
   * <p>또한 백엔드 기록이 실패하면 이제 그걸 <b>실패로 표시한다.</b> 예전에는 성공 경로에서만
   * 상태를 갱신하고 실패는 아무 데도 표시되지 않아, 화면은 계속 "결제 중"이었다.
   */
  async function onApproveHandler(data, actions) {
    dispatch({ type: "PAY_REQUEST" });
    try {
      const details = await actions.order.capture();

      const payment = {
        orderId: order.id,
        trackingId: order.trackingId,
        paypalOrderId: details.id,
        amounts: details.purchase_units[0].amount.value,
        couponName: order.couponApplied,
        orderStatus: order.orderStatus,
        paymentStatus: "COMPLETED",
        userEmail: user.email,
        createdTime: details.create_time,
      };

      const res = await postRequest("/order-service/api/order/payment/paypal", payment);

      // 서버에 기록된 뒤에만 화면을 결제 완료로 바꾼다. 순서를 바꾸면 기록 실패 시
      // 화면만 결제 완료가 되어 사용자가 다시 결제하지 않는다.
      setOrder({ ...order, orderStatus: "PAID" });
      reduxDispatch(emptyCart());
      dispatch({ type: "PAY_SUCCESS", payload: res.data });
    } catch (error) {
      console.error("PayPal onApproveHandler error: ", error);
      dispatch({
        type: "PAY_FAIL",
        payload:
          t("payment.error.record_failed") + " "
          + (error?.response?.data?.message ?? error.message ?? ""),
      });
    }
  }

  /**
   * 결제 취소 — PayPal 창을 승인 없이 닫았을 때.
   *
   * <p>🐛 예전에는 <b>승인과 같은 엔드포인트</b>(`/payment/paypal`)를 부르고 본문에
   * `paymentStatus: "CANCELED"` 를 실었다. 그런데 서버는 그 필드를 **일부러 읽지
   * 않는다** — 브라우저가 결제 상태를 정하게 두는 것이 결제 검증 결함의 출발점이었다
   * (docs/35). 그래서 취소도 결제로 취급돼 PayPal 캡처 검증에 걸렸고, 승인조차 안 한
   * 주문은 PayPal 에서 `CREATED` 라 **언제나 400** 이었다:
   *
   * <pre>
   * {"message":"Payment transaction failed: PayPal 결제가 완료되지 않았습니다:
   *   status=CREATED, paypalOrderId=..."}
   * </pre>
   *
   * <p>취소는 다른 연산이므로 다른 문을 쓴다(`/payment/paypal/cancel`). 그쪽은 캡처를
   * 요구하지 않고, 결제 아웃박스에도 쓰지 않으며, **이미 결제된 주문은 거절한다.**
   */
  async function onCancelHandler(id) {
    try {
      // 예전에는 `order.paymentStatus = ...` 로 부모가 준 객체를 직접 고쳤다. props 로 받은
      // 객체를 변형하면 React 는 바뀐 줄 모르고(참조가 같다) 다시 그리지 않는다. 화면이
      // 갱신되는 것처럼 보였던 건 옆에서 setOrder 가 같이 불렸기 때문일 뿐이다.
      //
      // 🔒 userEmail 은 보내지 않는다. 요청자는 게이트웨이가 검증한 헤더가 정한다 —
      //    본문으로 보내면 남의 주문을 취소할 수 있다.
      const cancelation = {
        orderId: order.id,
        trackingId: order.trackingId,
        paypalOrderId: id?.orderID ?? null,
      };

      await postRequest("/order-service/api/order/payment/paypal/cancel", cancelation);

      setOrder({ ...order, orderStatus: "CANCELED" });
      reduxDispatch(cancelCart());
      dispatch({ type: "PAY_RESET" });
    } catch (error) {
      console.error("PayPal onCancelHandler error: ", error);
      dispatch({
        type: "PAY_FAIL",
        payload:
          error?.response?.data?.message ?? t("payment.error.cancel_failed"),
      });
    }
  }

  /**
   * Stripe 결제 시작.
   *
   * <p>서버가 Checkout 세션을 만들고 URL 을 돌려주면 그리로 이동한다. <b>브라우저에
   * Stripe 키가 필요 없다</b> — 세션을 서버가 만들기 때문이다. PayPal 에서 겪은
   * "빌드 시점에 박히는 client id" 문제가 구조적으로 생기지 않는다.
   *
   * <p>🔒 금액을 보내지 않는다. 서버가 주문에서 읽는다(docs/35).
   */
  /**
   * 결제창을 열기 직전 — 재고 예약을 새로 잡는다(docs/59 §8). 결제 화면에 오래 머물면 주문 때 잡은 30분이 지나 있을 수 있다.
   * 특히 포트원 가상계좌는 계좌가 결제창 안에서 발급돼, 그 뒤에는 막을 수 없다.
   *
   * <p>모자라면 서버가 400 과 이유("재고가 부족해 결제를 시작하지 못했습니다…")를 준다 — 그 이유로 멈춘다(false). 그 밖의
   * 실패(네트워크·5xx)는 막지 않는다 — 주문 생성과 같은 fail-open 이고, 결제 뒤 부족분 환불이 받는다.
   */
  async function renewStockHold() {
    try {
      // 창마다의 결제 시도 키(order V27) — 다른 탭이 이 주문을 결제 중이면 서버가 400 과 이유를 준다.
      const { data } = await postRequest(`/order-service/api/order/payment/stock-hold/${order.trackingId}`, {},
        "application/json", { headers: { "Payment-Attempt": paymentAttemptKey(order.trackingId) } });
      if (data?.stockHoldUntil) {
        setOrder({ ...order, stockHoldUntil: data.stockHoldUntil });
      }
      return true;
    } catch (error) {
      if (error?.response?.status === 400) {
        dispatch({ type: "PAY_FAIL", payload: error.response.data?.message || t("payment.error.not_completed") });
        return false;
      }
      console.warn("stock hold renew failed — proceeding", error?.response?.status ?? error?.message);
      return true;
    }
  }

  async function onStripeHandler() {
    dispatch({ type: "PAY_REQUEST" });
    if (!(await renewStockHold())) return;
    try {
      const { data } = await postRequest(
        "/order-service/api/order/payment/stripe/session",
        { trackingId: order.trackingId }
      );

      if (!data?.url) {
        throw new Error(t("payment.error.no_checkout_url"));
      }
      // replace 가 아니라 assign 이다 — 결제를 취소하고 뒤로 가면 이 화면으로 돌아와야 한다.
      window.location.assign(data.url);
    } catch (error) {
      console.error("Stripe session error: ", error);
      dispatch({
        type: "PAY_FAIL",
        payload: error?.response?.data?.message
          ?? t("payment.error.start_failed"),
      });
    }
  }

  /**
   * 포트원 결제 시작.
   *
   * <p>Stripe 와 <b>모양이 다르다</b>. Stripe 는 서버가 세션을 만들고 브라우저가 이동하지만,
   * 포트원은 <b>브라우저가 결제창을 연다</b>(PayPal 과 같다). 그래서 시작 요청이 없고,
   * 결제창이 닫힌 뒤 확인 요청만 보낸다.
   *
   * <p>🔒 {@code amount} 를 브라우저가 정한다 — 그래도 되는 이유는 서버가 확인 단계에서
   * <b>포트원이 알려 준 실제 결제액을 DB 의 주문 총액과 대조</b>하기 때문이다. 사용자가
   * 1원으로 바꿔 결제하면 결제는 되지만 <b>주문에는 반영되지 않는다</b>(docs/35).
   *
   * <p>{@code merchant_uid} 는 우리 {@code trackingId} 다. 웹훅이 어느 주문인지 아는
   * 근거가 되므로 <b>반드시</b> 넣어야 한다.
   */
  async function onPortOneHandler() {
    const version = portOneVersion();
    if (!version) {
      dispatch({
        type: "PAY_FAIL",
        payload: t("payment.error.portone_not_configured"),
      });
      return;
    }
    if (version === "v2") {
      return onPortOneV2Handler();
    }

    const impCode = process.env.REACT_APP_PORTONE_IMP_CODE;
    dispatch({ type: "PAY_REQUEST" });
    if (!(await renewStockHold())) return;
    try {
      const IMP = await loadPortOne();
      IMP.init(impCode);

      IMP.request_pay(
        {
          // 포트원 콘솔의 결제 채널에서 고른 PG 와 **같아야** 한다. 채널이 KG이니시스면
          // html5_inicis, 토스페이먼츠면 tosspayments, 나이스면 nice — 다르면 결제창이
          // "등록되지 않은 PG" 로 뜬다. 빌드 인자로 받고 기본은 테스트 상점의 이니시스다.
          pg: process.env.REACT_APP_PORTONE_PG || "html5_inicis",
          // 가상계좌는 pay_method: "vbank". 발급되면 콜백의 status 가 "ready" 로 오고
          // 서버가 포트원에 조회해 계좌를 주문에 붙인다 — 입금 대기(UNPAID).
          pay_method: portOnePayMethod === "vbank" ? "vbank" : "card",
          merchant_uid: order.trackingId,
          name: t("payment.portone.order_name", { id: order.id }),
          // 청구 금액(KRW). 상점 통화의 total 이 아니다 — 서버가 이 값과 대조한다.
          amount: charge.amount,
          currency: charge.currency,
          buyer_email: user.email,
        },
        async (response) => {
          // success 가 false 면 사용자가 취소했거나 PG 가 거절한 것이다.
          if (!response.success) {
            dispatch({
              type: "PAY_FAIL",
              // PG 가 쓴 문구(대개 한국어)를 그대로 띄우지 않는다 — 알아보고 화면 언어로(util/portoneError).
              payload: portOneFailureText(t, lang, response),
            });
            return;
          }

          try {
            // 🔒 보내는 것은 imp_uid 하나다. 금액도 상태도 보내지 않는다 —
            //    서버가 포트원에 다시 조회해 확인한다.
            const res = await postRequest("/order-service/api/order/payment/portone/confirm", {
              trackingId: order.trackingId,
              providerPaymentId: response.imp_uid,
            });
            afterPortOneConfirm(res);
          } catch (error) {
            console.error("PortOne confirm error: ", error);
            // 결제는 됐는데 반영이 실패한 상태다. 웹훅이 뒤늦게 반영할 수 있으므로
            // "실패"가 아니라 "확인 중"이라고 말한다.
            dispatch({
              type: "PAY_FAIL",
              payload: t("payment.confirm_delayed"),
            });
          }
        }
      );
    } catch (error) {
      console.error("PortOne load error: ", error);
      dispatch({ type: "PAY_FAIL", payload: error.message });
    }
  }

  /**
   * 포트원 <b>V2</b> — 계정에 {@code imp…} 가 없고 {@code store-…} 만 있을 때.
   *
   * <p>V1 과 다른 것 셋:
   * <ul>
   *   <li>결제 id 를 <b>우리가</b> 정한다({@code paymentId}). 시도마다 새 값이다.</li>
   *   <li>주문과의 연결은 {@code merchant_uid} 가 아니라 {@code customData} 다 — 서버가
   *       포트원에 조회해 거기서 trackingId 를 읽는다.</li>
   *   <li>PG 코드가 아니라 <b>채널 키</b>로 채널을 고른다(콘솔 → 결제 채널 → 채널 키).</li>
   * </ul>
   *
   * <p>금액을 브라우저가 정해도 되는 이유는 V1 과 같다 — 서버가 포트원이 알려 준 실제
   * 결제액을 DB 의 주문 총액과 대조한다(docs/35).
   *
   * <p>⚠️ 통화. 국내 PG 는 KRW 로 결제한다. 이 상점의 주문 총액이 USD 면 서버의 통화
   * 대조에서 거절된다 — 상점 통화(PAYPAL_CURRENCY)와 여기 통화가 같아야 한다(docs/49 §6).
   */
  async function onPortOneV2Handler() {
    const storeId = process.env.REACT_APP_PORTONE_STORE_ID;
    const channelKey = process.env.REACT_APP_PORTONE_CHANNEL_KEY;
    if (!channelKey) {
      dispatch({
        type: "PAY_FAIL",
        payload: t("payment.error.channel_key_missing"),
      });
      return;
    }

    dispatch({ type: "PAY_REQUEST" });
    if (!(await renewStockHold())) return;
    try {
      const PortOne = await loadPortOneV2();
      const paymentId = newPortOnePaymentId();
      const storeDetails = portOneStoreDetails(charge.currency, process.env.REACT_APP_PORTONE_STORE_EMAIL);

      const response = await PortOne.requestPayment({
        storeId,
        channelKey,
        paymentId,
        orderName: t("payment.portone.order_name", { id: order.id }),
        // 청구 통화·금액은 주문이 안다 — 서버가 주문 생성 때 환율로 정해 저장했다.
        totalAmount: charge.amount,
        currency: portOneCurrencyCode(charge.currency),
        // 가상계좌는 VIRTUAL_ACCOUNT + 유효 시간. 발급되면 status 가 VIRTUAL_ACCOUNT_ISSUED 로
        // 남고, 서버가 포트원에 조회해 계좌를 주문에 붙인다 — 입금 대기(UNPAID).
        payMethod: portOnePayMethod === "vbank" ? "VIRTUAL_ACCOUNT" : "CARD",
        ...(portOnePayMethod === "vbank"
          ? { virtualAccount: { accountExpiry: { validHours: 48 } } }
          : {}),
        // KG이니시스 PC 결제 · 이니시스 일본결제는 구매자 이름이 필수다 — 이름 · 전화번호는 이 주문의 배송지에서(portOneCustomer).
        // [JA] KG イニシス PC 決済 · イニシス日本決済は購入者名が必須 — 名前 · 電話番号はこの注文の配送先から。
        customer: portOneCustomer(order, user),
        // 이니시스 일본결제(JPPG)는 상점 정보가 필수다 — JPY 청구일 때만 싣는다(portOneStoreDetails).
        // [JA] イニシス日本決済(JPPG)は店舗情報が必須 — JPY 請求のときだけ載せる。
        ...(storeDetails ? { storeDetails } : {}),
        // 모바일 등에서 결제창이 페이지 이동으로 열리면 끝난 뒤 이 주문 화면으로 돌아온다 — 확인은 PortOneReturn.
        // PC 에서 iframe 으로 열리면 예전처럼 아래 반환값으로 끝난다(forceRedirect 를 켜지 않는다 — 두 흐름 모두).
        // [JA] モバイル等でページ遷移になると、この注文画面へ戻る(確認は PortOneReturn)。PC の iframe は従来どおり戻り値で。
        redirectUrl: portOneRedirectUrl(),
        // 🔒 서버가 포트원에 조회해서 읽는 값이다. 본문으로 보내는 것이 아니다.
        customData: JSON.stringify({ trackingId: order.trackingId }),
      });

      // V2 는 실패를 예외가 아니라 code 가 있는 응답으로 준다(사용자 취소 포함).
      if (!response || response.code !== undefined) {
        dispatch({
          type: "PAY_FAIL",
          payload: portOneFailureText(t, lang, response),
        });
        return;
      }

      try {
        // 🔒 보내는 것은 paymentId 하나다. 금액도 상태도 보내지 않는다.
        const res = await postRequest("/order-service/api/order/payment/portone/confirm", {
          trackingId: order.trackingId,
          providerPaymentId: response.paymentId ?? paymentId,
        });
        afterPortOneConfirm(res);
      } catch (error) {
        console.error("PortOne V2 confirm error: ", error);
        dispatch({
          type: "PAY_FAIL",
          payload: t("payment.confirm_delayed"),
        });
      }
    } catch (error) {
      console.error("PortOne V2 load error: ", error);
      dispatch({ type: "PAY_FAIL", payload: error.message });
    }
  }

  /**
   * 포트원 복귀 확인의 결과. 서버가 orderStatus 를 말해 준다 — PAID 면 결제 완료,
   * UNPAID 면 가상계좌가 발급된 것이라 입금 안내를 보여 줘야 한다(BankTransfer).
   * 장바구니는 PAID 일 때만 비운다 — 입금 전에 비우면 기한이 지나 접힌 주문의 장바구니가 없다.
   */
  function afterPortOneConfirm(res) {
    const status = res?.data?.orderStatus === "UNPAID" ? "UNPAID" : "PAID";
    setOrder({ ...order, orderStatus: status });
    if (status === "PAID") {
      reduxDispatch(emptyCart());
    }
    dispatch({ type: "PAY_SUCCESS" });
    if (status === "UNPAID") {
      window.location.reload();
    }
  }

  function onErroHandler(error) {
    console.error("PayPal onError: ", error);
    dispatch({ type: "PAY_FAIL", payload: error.message });
  }

  return (
    <>
      <h3 className=" pb-2 mb-4 border-b border-b-2  text-xl font-semibold">
        {t("payment.title")}
      </h3>
      <div>
        {paymentMethods.map((payment) => {
          if (payment.id == order.paymentMethod) {
            return (
              <div
                key={payment.id}
                className={`cursor-pointer p-2 my-2 flex items-center rounded-xl ${order.paymentMethod === payment.id &&
                  "bg-slate-200"
                  } hover:bg-slate-200 transition`}
              >
                <label htmlFor={payment.id} className="">
                  <input
                    type="radio"
                    name="payment"
                    id={payment.id}
                    readOnly
                    defaultChecked={
                      order.paymentMethod === payment.id
                    }
                  />
                </label>
                <div className="flex items-center ">
                  {/* 결제 화면(checkout/Payment.jsx)과 같은 파일 — id 로 경로를 조립하면
                      stripe·portone 은 PNG 가 없어 깨진다. paymentMethods 의 icon 을 쓴다. */}
                  <img
                    src={`${process.env.PUBLIC_URL}${payment.icon}`}
                    alt={paymentMethodName(payment, t)}
                    width={40}
                    height={40}
                    className="mx-3"
                  />
                  <div className="flex flex-col">
                    <span className="font-semibold">
                      {paymentMethodName(payment, t)}
                    </span>
                    <p className="text-sm text-slate-600">
                      {paymentMethodDescription(payment, t)}
                    </p>
                  </div>
                </div>
              </div>
            );
          }
        })}

        {/* <div className=" mt-2 w-full rounded-xl bg-amazon-blue_light text-white p-4 font-semibold text-2xl hover:bg-amazon-blue_dark hover:scale-95 transition"> */}

        {/* {order.paymentMethod === "paypal" &&
                     ( */}

        {/*
          * 🐛 <b>여기가 결제수단과 무관하게 PayPal 버튼만 그렸다.</b> 분기 조건이
          *    주석 처리된 채였다:
          *
          *      {/* {order.paymentMethod === "paypal" && ( *\/}
          *      <PayPalButtons ... />
          *      {/* { order.paymentMethod === "credit_card" && } *\/}
          *
          *    그래서 "무통장입금"을 골라도 PayPal 버튼이 떴다. 결제수단 선택이
          *    화면에만 있고 <b>아무 동작도 하지 않았다.</b>
          */}
        {/* 청구 통화가 상점 통화와 다르면(포트원 KRW · 일본 JPY) 실제로 결제될 금액을 보여 준다.
            total 만 보이면 사용자는 결제창의 금액이 왜 다른지 알 수 없다. */}
        {charge.currency !== STORE_CURRENCY && (
          <p className="mt-2 text-sm text-slate-700" data-testid="charge-summary">
            {t("payment.charge_amount")}: <b>{money(charge.amount, charge.currency)}</b>
            <span className="ml-1 text-slate-500">
              {t("payment.charge_basis", { total: money(order.total) })}
            </span>
          </p>
        )}

        {/* 결제 전 재고 예약(docs/59) — 언제까지 결제해야 재고가 보장되는지. */}
        <StockHoldNotice order={order} />

        <div className="mt-2">
          {order.paymentMethod === "paypal" && (
            isPending ? (
              <span>{t("payment.paypal_loading")}</span>
            ) : (
              <PayPalButtons
                createOrder={createOrderHanlder}
                onApprove={onApproveHandler}
                onCancel={onCancelHandler}
                onError={onErroHandler}
              >   </PayPalButtons>
            )
          )}

          {order.paymentMethod === "portone" && order.orderStatus === "UNPAID" && (
            /* 가상계좌가 발급된 주문 — 계좌·금액·기한을 보여 준다. 입금은 포트원 웹훅이 확인한다. */
            <BankTransfer order={order} onChanged={() => window.location.reload()} />
          )}

          {order.paymentMethod === "portone" && order.orderStatus !== "UNPAID" && (
            <div>
              {vbankOffered && (
              <div className="mb-2 flex gap-4 text-sm text-slate-800">
                <label className="flex items-center gap-1">
                  <input type="radio" name="portone-pay-method" value="card"
                    checked={portOnePayMethod === "card"}
                    onChange={() => setPortOnePayMethod("card")} />
                  {t("payment.portone.card")}
                </label>
                <label className="flex items-center gap-1">
                  <input type="radio" name="portone-pay-method" value="vbank"
                    checked={portOnePayMethod === "vbank"}
                    onChange={() => setPortOnePayMethod("vbank")} />
                  {t("payment.portone.vbank")}
                </label>
              </div>
              )}
              <button
                type="button"
                onClick={onPortOneHandler}
                disabled={state.loading}
                className="w-full rounded bg-sky-600 py-3 font-semibold text-white hover:bg-sky-700 disabled:opacity-60"
              >
                {state.loading
                  ? t("payment.portone.opening")
                  : portOnePayMethod === "vbank" ? t("payment.portone.issue_vbank") : t("payment.portone.pay")}
              </button>
            </div>
          )}

          {order.paymentMethod === "stripe" && (
            <button
              type="button"
              onClick={onStripeHandler}
              disabled={state.loading}
              className="w-full rounded bg-indigo-600 py-3 font-semibold text-white hover:bg-indigo-700 disabled:opacity-60"
            >
              {state.loading ? t("payment.stripe.redirecting") : t("payment.stripe.pay")}
            </button>
          )}

          {/* 무통장입금 — 결제 API 가 아니라 상태 기계다(docs/49 §11). 안내를 받고 입금하면
              관리자가 확인한다. 확인 뒤에는 이 화면이 아니라 결제 완료 화면이다. */}
          {order.paymentMethod === "cash" && (
            <BankTransfer order={order} onChanged={() => window.location.reload()} />
          )}

          {/* 아직 못 쓰는 수단. 버튼을 그리지 않고 이유를 말한다 — 예전에는 이 자리에
              PayPal 버튼이 떠서 다른 수단을 고른 사용자를 혼란스럽게 했다. */}
          {!["paypal", "stripe", "portone", "cash"].includes(order.paymentMethod) && (
            <p className="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
              {paymentMethodDescription(paymentMethodOf(order.paymentMethod), t)
                || t("payment.unavailable")}
            </p>
          )}
        </div>

        {/* PAY_FAIL 이 담아 둔 메시지를 실제로 보여 준다. 예전에는 reducer 가 error 를
            저장만 하고 화면 어디에도 그리지 않아, 결제가 실패해도 사용자는 아무 표시를
            못 받고 버튼만 계속 눌렀다. */}
        {state.error && (
          <p className="mt-2 text-red-600 text-sm" role="alert">{state.error}</p>
        )}
        {/* )                        
                          } */}
        {/* { order.paymentMethod === "credit_card" && }      */}
        {/* </div> */}
      </div>
    </>
  );
};

export default Payment;
