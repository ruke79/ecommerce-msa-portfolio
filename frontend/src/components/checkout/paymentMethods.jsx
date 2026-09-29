import { translate } from "i18n/I18nProvider";

/**
 * 결제수단 목록.
 *
 * <p>🐛 예전에는 이름이 {@code "Creadit cart"} 였다(credit card 오타 둘). 그리고 셋 중
 * <b>PayPal 만 실제로 동작했다</b> — 주문 화면이 결제수단과 무관하게 PayPal 버튼만
 * 그렸기 때문이다(order/Payment.jsx 주석).
 *
 * <p>{@code id} 는 서버로 가는 값이라 함부로 바꾸지 않는다. 화면에 보이는 것은
 * {@code name}(상표) 또는 {@code nameKey}(사전의 키)이고, 그 둘을 나눠 둔 이유는 docs/45 §4-1 의 정렬 옵션과 같다 —
 * <b>화면 문구가 API 계약을 바꾸면 안 된다.</b>
 *
 * <p>{@code available: false} 는 "목록에는 있지만 아직 못 쓴다" 는 뜻이다. 목록에서
 * 아예 빼지 않는 이유: 사용자가 기대하는 수단이 왜 없는지 알 수 없는 것보다, 있는데
 * 준비 중이라고 말하는 편이 낫다. 화면이 이 값을 보고 안내 문구를 띄운다.
 *
 * <p>{@code icon} 은 public/ 아래의 실제 파일이다. 🐛 예전에는 화면이
 * {@code /assets/images/${id}.png} 를 조립했는데, Stripe·포트원에는 그런 PNG 가 없어
 * 아이콘이 깨진 채 떴다. 파일이 있는지는 paymentMethods.test.js 가 확인한다.
 */
export const paymentMethods = [
    {
        name: "PayPal",
        id: "paypal",
        icon: "/assets/images/paypal.png",
        descriptionKey: "payment.method.paypal.description",
        available: true,
        images: [],
    },
    {
        name: "Stripe",
        id: "stripe",
        icon: "/assets/images/stripe.svg",
        descriptionKey: "payment.method.stripe.description",
        available: true,
        images: [],
    },
    {
        nameKey: "payment.method.portone.name",
        id: "portone",
        icon: "/assets/images/portone.svg",
        descriptionKey: "payment.method.portone.description",
        available: true,
        images: [],
    },
    {
        nameKey: "payment.method.credit_card.name",
        id: "credit_card",
        icon: "/assets/images/credit_card.png",
        // 🐛 "mastedcard" 였다. 파일은 public/assets/images/mastercard.png 다.
        descriptionKey: "payment.method.credit_card.description",
        available: false,
        images: [
            "visa",
            "mastercard",
            "maestro",
            "american_express",
            "cb",
            "jcb",
        ],
    },
    {
        nameKey: "payment.method.cash.name",
        id: "cash",
        icon: "/assets/images/cash.png",
        // 결제 API 가 아니라 상태 기계다(docs/49 §11): 안내 → 입금 대기 → 관리자 입금 확인 → 결제 완료.
        descriptionKey: "payment.method.cash.description",
        available: true,
        images: [],
    },
]

/**
 * 화면에 보일 이름. 상표(PayPal·Stripe)는 {@code name} 그대로, 나머지는 사전({@code nameKey})에서.
 *
 * <p>목록은 모듈이 한 번 읽을 때 만들어지므로 문구를 여기 담아 두면 그 순간의 언어로 굳는다 — 키만 두고
 * 그릴 때 번역한다. 컴포넌트는 useTranslation 의 {@code t} 를 넘긴다(언어가 바뀌면 다시 그려진다).
 */
export function paymentMethodName(method, t = translate) {
    if (!method) return "";
    return method.nameKey ? t(method.nameKey) : method.name;
}

/** 화면에 보일 설명 — {@link paymentMethodName} 과 같은 이유로 그릴 때 번역한다. */
export function paymentMethodDescription(method, t = translate) {
    return method?.descriptionKey ? t(method.descriptionKey) : "";
}

/** 결제수단 id → 정의. 모르는 값이면 undefined. */
export function paymentMethodOf(id) {
    return paymentMethods.find((m) => m.id === id);
}
