import { HandThumbUpIcon as HandThumbUpOutline } from "@heroicons/react/24/outline";
import { HandThumbUpIcon as HandThumbUpSolid } from "@heroicons/react/24/solid";
import { Rating } from "@mui/material";
import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { useAuthContext } from "../../../store/AuthContext";
import { toggleReviewLike } from "util/api";
import { useTranslation } from "i18n/I18nProvider";
import { fitLabel } from "./Select";
import Avatar from "components/common/Avatar";

/**
 * 좋아요 버튼.
 *
 * <p>예전에는 아이콘만 있고 클릭 핸들러가 없었다. 옆의 숫자는 `{review.likes?.length}6 likes`
 * 였는데, 서버가 `likes` 를 준 적이 없어 앞부분은 항상 빈 문자열이 되고 하드코딩된 "6" 만
 * 남아 모든 리뷰가 "6 likes" 로 보였다.
 *
 * 화면 폭에 따라 같은 버튼이 두 번(모바일/데스크톱) 그려지므로 컴포넌트로 뺀다 — 로직이
 * 두 벌이면 한쪽만 고쳐지는 일이 반드시 생긴다.
 */
const LikeButton = ({ review, onLikeChange, className = "" }) => {
    const { token } = useAuthContext();
    const { t } = useTranslation();
    const navigate = useNavigate();
    const [pending, setPending] = useState(false);

    const liked = Boolean(review.likedByMe);
    const count = review.likeCount ?? 0;

    // reviewId 가 없으면 무엇을 누르는지 서버에 말할 수 없다. 눌리는 척하지 않는다.
    const usable = Boolean(review.reviewId);

    const handleToggle = async () => {
        if (!token) {
            navigate("/signin");
            return;
        }
        if (pending || !usable) return;

        setPending(true);

        // 낙관적 갱신: 네트워크를 기다리지 않고 먼저 그린다. 실패하면 아래에서 되돌린다.
        onLikeChange?.(review.reviewId, {
            likedByMe: !liked,
            likeCount: Math.max(0, count + (liked ? -1 : 1)),
        });

        try {
            const result = await toggleReviewLike(review.reviewId);
            // 서버가 다시 센 값으로 덮는다 — 그 사이 다른 사람이 눌렀다면 위의 ±1 은 이미 틀렸다.
            onLikeChange?.(review.reviewId, {
                likedByMe: result.likedByMe,
                likeCount: result.likeCount,
            }, { settled: true });
        } catch (e) {
            onLikeChange?.(review.reviewId, { likedByMe: liked, likeCount: count });
        } finally {
            setPending(false);
        }
    };

    const Icon = liked ? HandThumbUpSolid : HandThumbUpOutline;

    return (
        <button
            type="button"
            onClick={handleToggle}
            disabled={pending || !usable}
            aria-pressed={liked}
            aria-label={liked ? t("product.review.like.undo") : t("product.review.like")}
            title={token ? undefined : t("product.review.like.login")}
            className={`flex items-center rounded px-1 py-0.5 transition-opacity ${
                pending ? "opacity-50" : "hover:opacity-70"
            } ${usable ? "cursor-pointer" : "cursor-not-allowed"} ${className}`}
        >
            <Icon className={`h-6 w-6 mr-1 ${liked ? "text-amazon-orange" : ""}`} />
            <span>
                {/* 한국어·일본어에는 복수형이 없다 — 단수/복수 분기를 없앤다. */}
                {t("product.review.like.count", { count })}
            </span>
        </button>
    );
};

const ReviewCard = ({ review, onLikeChange }) => {
    const { t } = useTranslation();

    const { name, image } = review.reviewedBy;



    return (
        <div className="grid md:grid-cols-10 mb-4">
            <div className="md:col-span-1 flex flex-col items-center mt-1">
                {/* 🐛 사진을 올리지 않은 회원(대부분)은 image 가 null 이다 — `<img src={null}>` 은 깨진 아이콘이었다. */}
                <Avatar user={{ name, image }} size={55} className="outline outline-1 outline-offset-2 outline-slate-300" />
                <span className="mt-2 font-semibold">{name}</span>
                {/* <span className="mt-2 font-xs text-slate-500">{review.updatedAt}</span> */}
            </div>
            <div className="md:col-span-9 flex flex-col md:flex-row bg-white border rounded-xl px-5 py-3">
                <div className="max-md:flex md:mr-6 mt-1 md:w-[75px] max-md:border-b max-md:pb-2 max-md:border-slate-100">
                    {/* 예전 리뷰는 스타일 그림이 비어 있다(색상 정보를 통째로 보내던 때) — 빈 src 는 깨진 아이콘이 된다 */}
                    {review.style?.image ? (
                        <img
                            src={review.style.image}
                            width={70}
                            height={70}
                            alt={review.style.color ?? name}
                            className="rounded-xl outline outline-1 outline-offset-2 outline-slate-300"
                        />
                    ) : review.style?.color ? (
                        <span
                            className="inline-block w-[70px] h-[70px] rounded-xl border border-slate-300"
                            style={{ backgroundColor: review.style.color }}
                            title={review.style.color}
                        />
                    ) : null}
                    <div className="md:hidden ml-4 flex flex-grow flex-col space-y-2 justify-center text-sm text-slate-600">
                        {review.size?.trim() && <span className="">{t("product.review.card.size", { size: review.size })}</span>}
                        {review.fit?.trim() && <span className="">{t("product.review.card.fit", { fit: fitLabel(t, review.fit) })}</span>}
                        <Rating
                            className=""
                            name="half-rating-read"
                            readOnly
                            value={review.rating}
                            precision={0.5}
                        />
                        <LikeButton review={review} onLikeChange={onLikeChange} />
                    </div>
                </div>
                <div className="flex flex-col w-full">
                    <div className="hidden md:flex border-b pb-1 border-slate-100 flex-wrap items-center text-sm text-slate-600">
                        <Rating
                            className=""
                            name="half-rating-read"
                            readOnly
                            value={review.rating}
                        />
                        {review.size?.trim() && <span className="ml-4">{t("product.review.card.size", { size: review.size })}</span>}
                        {review.fit?.trim() && <span className="ml-4">{t("product.review.card.fit", { fit: fitLabel(t, review.fit) })}</span>}
                        <LikeButton
                            review={review}
                            onLikeChange={onLikeChange}
                            className="ml-auto"
                        />
                    </div>
                    <div className="flex flex-col md:flex-row">
                        <div className="flex flex-grow mt-2 text-slate-800 tracking-wide	">
                            {review.review}
                        </div>
                        <span className="m-4 flex justify-end space-x-5 w-52">
                            {review.images &&
                                review.images.map((img, i) => (
                                    <img
                                        key={i}
                                        src={img}
                                        width={50}
                                        height={50}
                                        alt={img}
                                        className="rounded-xl outline outline-1 outline-offset-2 outline-slate-300 cursor-pointer"
                                    />
                                ))}
                        </span>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default ReviewCard;
