import { Rating } from "@mui/material";
import { useAuthContext } from "../../../store/AuthContext";
import { useCallback, useEffect, useState } from "react";
import AddReview from "./Review";
import Table from "./Table";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "i18n/I18nProvider";

const Reviews = ({ product, num_reviews, userReviews, updateReviews }) => {
    const navigate = useNavigate();
    const { t } = useTranslation();

    const { token } = useAuthContext();
    const [key] = useState(0);
    const [prdRating, setPrdRating] = useState(product.rating);

    const [reviews, setReviews] = useState(userReviews);

    // 새로 읽은 목록(주기적으로 — useFetchReviews)을 반영한다. 예전에는 처음 받은 목록을 복사해 둔 뒤로 다시 보지 않아, 다른
    // 사람이 누른 좋아요·새 리뷰가 새로고침 전에는 보이지 않았다. 바뀐 것이 없으면 React Query 가 같은 참조를 주므로 돌지 않는다.
    useEffect(() => {
        if (userReviews) {
            setReviews(userReviews);
        }
    }, [userReviews]);

    useEffect(() => {

        if (reviews && reviews.length > 0) {
            // 리뷰 본문에는 사용자가 쓴 글이 들어 있다. 콘솔에 통째로 찍지 않는다.
            const totalPoints = reviews.reduce((acc, currReview) => {
                return acc + currReview.rating;
            }, 0.0);
            setPrdRating(totalPoints / reviews.length);
        } else if (reviews) {
            // 마지막 리뷰를 지웠다 — 예전에는 삭제 쪽이 0 을 직접 박았다(남은 리뷰가 있어도).
            setPrdRating(0);
        }
        

    }, [reviews, setReviews]);

    /*
     * 좋아요 상태는 리뷰 목록의 일부다. 그래서 카드가 각자 들고 있지 않고 목록을 소유한
     * 이 컴포넌트가 갱신한다 — 카드가 자기 상태를 따로 들면 목록이 다시 그려질 때(리뷰
     * 추가·삭제, 페이지 이동) 그 상태가 조용히 초기값으로 돌아간다.
     */
    const handleLikeChange = useCallback((reviewId, next, { settled = false } = {}) => {
        setReviews((prev) =>
            prev?.map((r) => (r.reviewId === reviewId ? { ...r, ...next } : r))
        );
        // 서버가 확정한 뒤에는 목록을 바로 다시 읽는다 — 누르기 전에 떠난 주기적 읽기가 늦게 도착해 방금 누른 것을 덮지 않게
        // (refetch 는 진행 중인 읽기를 취소하고 새로 읽는다).
        if (settled) {
            updateReviews?.();
        }
    }, [updateReviews]);

    return (
        
        <div key={key} className="mt-4 bg-slate-100 mx-auto w-full md:w-4/5 p-4 border rounded-md" >
            
            <h3 className="mb-2 font-bold text-2xl">
                {t("product.review.title", { count: num_reviews ?? 0 })}
            </h3>
            <div className="grid md:grid-cols-2">
                <div className="flex max-md:items-center md:flex-col md:justify-center">
                    <span className="text-sm font-semibold">{t("product.review.average")}</span>
                    <div className="mt-2 max-md:ml-auto flex items-center text-m font-semibold">
                        <Rating
                            name="half-rating-react"
                            value={prdRating}
                            precision={0.5}
                            readOnly
                            style={{ color: "#FACF19 " }}
                        />
                        <span className="ml-2">
                            {prdRating === 0.0
                                ? t("product.review.none")
                                : prdRating.toFixed(2)}
                        </span>
                    </div>
                </div>
                <div className="flex flex-col mt-4">
                    {product.ratings.map((rating, i) => (
                        <div
                            className="flex items-center space-x-4 max-md:mb-3"
                            key={i}
                        >
                            <Rating
                                name="half-rating-react"
                                defaultValue={5 - i}
                                readOnly
                                style={{ color: "#FACF19 " }}
                            />
                            <div className="flex items-center bg-slate-300 w-full md:w-56 h-1.5 rounded-full">
                                <div
                                    className="bg-red-500 h-1.5 rounded-full"
                                    style={{
                                        width: `${rating.percentage}%`,
                                    }}
                                ></div>
                            </div>
                            <span className="text-sm font-semibold">
                                {rating.percentage}%
                            </span>
                        </div>
                    ))}
                </div>
            </div>            
            {token ? (                
                  <AddReview
                      product={product}
                      myReview={reviews?.find((r) => r.mine) ?? null}
                      setReviews={setReviews}
                      updateReviews={updateReviews}
                  />
            ) : (
                <button
                    onClick={() => navigate('/signin')}
                    className="mt-4 w-full bg-amazon-orange font-semibold px-4 py-2 rounded"
                >
                    {t("product.review.login")}
                </button>
            )}
            {
                reviews?.length > 0 && (
                    <Table
                        reviews={reviews}
                        allSizes={product.allSizes}
                        colors={product.colors}
                        onLikeChange={handleLikeChange}
                    />
                )
            }
        </div>
    );
};

export default Reviews;
