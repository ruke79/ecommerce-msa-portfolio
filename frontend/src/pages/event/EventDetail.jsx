import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import toast from "react-hot-toast";

import ProductCard from "components/home/ProductCard";
import useDocumentTitle from "hook/useDocumentTitle";
import { useAuthContext } from "store/AuthContext";
import { claimEventCoupon, getEvent } from "util/api";
import { formatDateTime } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 고객군을 사람이 읽는 말로. 서버는 enum 이름을 준다. 문구가 아니라 <b>키</b>를 담는다 — 모듈에서 번역하면 그 순간의
 * 언어로 굳는다.
 */
const AUDIENCE_LABEL_KEYS = {
    NEW_USER: "event.audience.new_user",
    EXISTING_USER: "event.audience.existing_user",
};

const formatRange = (t, startsAt, endsAt) => {
    const fmt = (v) => formatDateTime(v, null);
    const from = fmt(startsAt);
    const to = fmt(endsAt);
    if (!from && !to) return "";
    if (!to) return t("event.detail.starts_at", { from });
    return `${from ?? ""} ~ ${to}`;
};

/**
 * 기획전 상세 — 대상 상품을 할인된 가격으로 보여 준다.
 *
 * <p><b>할인율을 여기서 계산하지 않는다.</b> 서버가 상품을 내려줄 때 이미 합성해서 준다
 * (catalog-service 의 EventPricing). 화면에서 다시 계산하면 같은 규칙이 두 곳에 생기고,
 * 상품 목록·검색·장바구니와 언젠가 어긋난다 — 그때 어느 쪽이 맞는지 알 수 없다.
 *
 * <p>그래서 {@code ProductCard} 를 그대로 쓴다. 홈 화면과 같은 컴포넌트이고, 할인 배지도
 * 같은 값에서 나온다.
 *
 * <p>진행 중이 아닌 기획전은 서버가 404 다. 끝난 세일 링크를 눌렀을 때 "끝났습니다"를
 * 보여 주는 것이 가격이 사라진 페이지를 보여 주는 것보다 낫다.
 */
const EventDetail = () => {
    const { slug } = useParams();
    const [detail, setDetail] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const [claiming, setClaiming] = useState(false);
    const [claimed, setClaimed] = useState(false);
    const { isAuthenticated } = useAuthContext();
    const { t } = useTranslation();

    useDocumentTitle(detail?.event?.title ?? t("event.title"));

    useEffect(() => {
        setLoading(true);
        getEvent(slug)
            .then(setDetail)
            // 오류는 문구 키로 들고 있다가 그릴 때 번역한다 — 언어를 바꿔도 따라간다.
            .catch((e) => setError(
                e?.response?.status === 404
                    ? "event.detail.not_found"
                    : "event.load_failed"))
            .finally(() => setLoading(false));
    }, [slug]);

    /**
     * 쿠폰을 받는다.
     *
     * <p>"이미 받았다"도 성공으로 다룬다 — 사용자 입장에서는 결과가 같다. 버튼을 두 번
     * 눌렀거나 다른 탭에서 이미 받은 것이고, 오류를 띄우면 무엇이 잘못됐는지 찾게 만든다.
     */
    const onClaim = async () => {
        if (claiming || claimed) return;
        setClaiming(true);
        const result = await claimEventCoupon(slug);
        setClaiming(false);

        if (result.ok) {
            setClaimed(true);
            toast.success(result.message ?? t("event.detail.claimed_toast"));
            return;
        }
        // 대상이 아니거나 기획전이 끝났다. 서버가 준 이유를 그대로 보여 준다 — 왜 못
        // 받는지는 서버만 안다.
        toast.error(result.message);
    };

    if (loading) {
        return <p className="p-6 text-slate-500">{t("event.loading")}</p>;
    }
    if (error) {
        return <p className="p-6 text-slate-600">{t(error)}</p>;
    }

    const event = detail?.event ?? {};
    const products = detail?.products ?? [];

    return (
        <div className="mx-auto w-full max-w-6xl p-4">
            {event.bannerImageUrl && (
                <img
                    src={event.bannerImageUrl}
                    alt=""
                    className="mb-6 aspect-[5/2] w-full rounded-lg object-cover"
                />
            )}

            <h1 className="text-3xl font-bold">{event.title}</h1>
            {event.description && (
                <p className="mt-2 whitespace-pre-wrap text-slate-700">{event.description}</p>
            )}
            <p className="mt-2 text-sm text-slate-500">
                {formatRange(t, event.startsAt, event.endsAt)}
            </p>

            {/* 딸린 쿠폰.
                🔴 사용자가 **직접 받아 간다.** 시작할 때 전 사용자에게 발급하면 사용자 수만큼
                쿠폰 행이 생기고, 대부분은 쓰지 않는다. 받아 간 사람만 행이 생기게 두면
                쓰기량이 실제 관심에 비례한다.

                자격(고객군) 판정은 여기서 하지 않는다 — 이 응답은 비회원도 받고 캐시될 수
                있다. 눌렀을 때 서버가 판정하고 대상이 아니면 그때 이유를 말해 준다. */}
            {event.couponName && (
                <div className="mt-4 flex flex-wrap items-center gap-3 rounded-lg border border-amber-300 bg-amber-50 p-4">
                    <div className="flex-1">
                        <p className="font-semibold">
                            {t("event.detail.coupon", { discount: event.couponDiscount })}
                            {AUDIENCE_LABEL_KEYS[event.audience] && (
                                <span className="ml-2 rounded bg-amber-200 px-2 py-0.5 text-xs font-normal">
                                    {t(AUDIENCE_LABEL_KEYS[event.audience])}
                                </span>
                            )}
                        </p>
                        <p className="text-sm text-slate-600">{event.couponName}</p>
                    </div>
                    <button
                        type="button"
                        onClick={onClaim}
                        disabled={claiming || claimed || !isAuthenticated}
                        className="rounded bg-amazon-orange px-4 py-2 font-semibold text-amazon-blue_dark disabled:opacity-40"
                    >
                        {claimed
                            ? t("event.detail.claimed")
                            : claiming ? t("event.detail.claiming") : t("event.detail.claim")}
                    </button>
                    {!isAuthenticated && (
                        <p className="w-full text-xs text-slate-500">
                            {t("event.detail.login")}
                        </p>
                    )}
                </div>
            )}

            <div className="mt-8 flex flex-wrap gap-4">
                {products.map((product) => (
                    <ProductCard key={product.id} product={product} />
                ))}
            </div>

            {products.length === 0 && (
                <p className="mt-8 text-slate-500">{t("event.detail.empty")}</p>
            )}
        </div>
    );
};

export default EventDetail;
