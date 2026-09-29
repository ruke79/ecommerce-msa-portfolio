import DialogModal from "components/DialogModal";
import { useDispatch } from "react-redux";
import { showDialog } from "../../../redux/DialogSlice";
import dataURItoBlob from "util/dataURItoBlob";
import { Rating } from "@mui/material";
import api, { postRequest, deleteRequest } from 'util/api';
import { useEffect, useState } from "react";
import ImagesReview from "./Images";
import Select from "./Select";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "i18n/I18nProvider";

// 서버로 가는 값이라 번역하지 않는다 — 화면에는 Select 가 FIT_LABEL_KEYS 로 바꿔 그린다.
let fits = ["Small", "True to size", "Large"];

const deleteReview = async (productId) => {
    const { data } = await deleteRequest(
        `user-service/api/review/delete/${productId}`
    );
    return data;
}

const addReview = async ({ productId, formData }) => {
    
    const { data } = await postRequest(
        `user-service/api/review/add/${productId}`, formData,
        {
            headers: {
                "Content-Type": "multipart/form-data",

            },
            transformRequest: [
                function () {
                    return formData;
                },
            ],
        }
    );
    return data;
}



/** 서버가 준 사유, 없으면 우리 문구 — 서버 문구는 요청 언어로 온다(Accept-Language, docs/60 §4). */
const reasonOf = (error, fallback) => error?.response?.data?.message || fallback;

/**
 * 리뷰 쓰기·고치기·지우기.
 *
 * <p>🐛 예전 모양의 문제:
 * <ul>
 *   <li>{@code const showBoundary = useErrorBoundary()} — 이 훅은 <b>객체</b>({@code { showBoundary }})를 준다. 함수처럼
 *       부르면 TypeError 라, 서버가 거절하면(검증·401) 안내 대신 화면이 죽었다. 이제 사유를 대화상자로 보여 준다.</li>
 *   <li>"리뷰 삭제" 가 늘 보였고, 내 리뷰가 없어도 서버가 성공이라 답해 "삭제되었습니다" 가 떴다. 내 리뷰가 있을 때만 그리고
 *       (서버의 {@code mine}), 지우기 전에 묻는다. 서버도 이제 없으면 404 다.</li>
 *   <li>스타일로 상품의 색상 정보({@code {id, color, colorImage}})를 통째로 보냈는데 서버는 {@code {color, image}} 를 읽는다 —
 *       이미지가 늘 비어 리뷰 카드에 깨진 그림이 떴다.</li>
 *   <li>지운 뒤 평균 별점을 0 으로 박았다 — 남은 리뷰로 다시 센다(Reviews).</li>
 * </ul>
 */
const AddReview = ({ product, myReview, setReviews, updateReviews }) => {
    const dispatch = useDispatch();
    const { t } = useTranslation();
    const [loading, setLoading] = useState(false);
    const [size, setSize] = useState("");
    const [style, setStyle] = useState("");
    const [fit, setFit] = useState("");
    const [review, setReview] = useState("");
    const [rating, setRating] = useState(0);
    const [images, setImages] = useState([]);

    const failed = (header, error, fallback) => {
        setLoading(false);
        dispatch(showDialog({ header, msgs: [{ msg: reasonOf(error, fallback), type: "error" }] }));
    };

    
    const { mutate: addReviewOp } = useMutation({
        mutationFn: addReview,
        onSuccess: (response) => {            
            setReviews(response);
            dispatch(
                showDialog({
                    header: t("product.review.added.header"),
                    msgs: [{
                        msg: t("product.review.added.msg"),
                        type: "success",
                    }],
                })
            );
            setSize("");
            setStyle("");
            setFit("");
            setRating(0);
            setImages([]);
            setReview("");
            setLoading(false);

            updateReviews();


        },
        onError: (error) => failed(t("product.review.error.header"), error, t("product.review.error.submit_failed")),
    });

    const { mutate: deleteReviewOp } = useMutation({
        mutationFn: deleteReview,
        onSuccess: (response) => {
            setReviews(response);
            setLoading(false);

            dispatch(
                showDialog({
                    header: t("product.review.deleted.header"),
                    msgs: [{
                        msg: t("product.review.deleted.msg"),
                        type: "success",
                    }],
                })
            );

            updateReviews();
        },
        onError: (error) => failed(t("product.review.error.delete_header"), error, t("product.review.error.delete_failed")),
    });

    // 사이즈 없는 상품 — 고를 사이즈가 없다(loader 가 이름 없는 사이즈를 뺐다). 사이즈도 착용감도 묻지도 막지도 않는다
    // (올리브오일에 "정사이즈" 는 말이 되지 않는다).
    const sizeless = !(product.allSizes?.length > 0);

    const handleSubmit = async () => {
        setLoading(true);
        let msgs = [];
        if (!size && !sizeless) {
            msgs.push({
                msg: t("product.review.error.size"),
                type: "error",
            });
        }
        if (!style) {
            msgs.push({
                msg: t("product.review.error.style"),
                type: "error",
            });
        }
        if (!fit && !sizeless) {
            msgs.push({
                msg: t("product.review.error.fit"),
                type: "error",
            });
        }
        if (rating < 0.4) {
            msgs.push({
                msg: t("product.review.error.rating"),
                type: "error",
            });
        }
        if (!review) {
            msgs.push({
                msg: t("product.review.error.review"),
                type: "error",
            });
        }
        if (msgs.length > 0) {
            dispatch(
                showDialog({
                    header: t("product.review.error.header"),
                    msgs,
                })
            );
            setLoading(false);
            return;
        }

        let reviewData = {
            size,
            style,
            fit,
            rating,
            review,                
        };

        let formData = new FormData();        
        formData.append("review", new Blob([JSON.stringify(reviewData)], {
            type: 'application/json'
        }));

        if (images.length > 0) {

            let files = images.map((img) => {
                return dataURItoBlob(img);
            });
            
            
            files.map((file) => {
                formData.append("image", file);
            })

            addReviewOp({productId: product.id, formData:formData});

        } else {

            addReviewOp({ productId: product.id, formData:formData});
        }
    }

    const handleDeleteReview = () => {
        if (!window.confirm(t("product.review.delete_confirm"))) return;
        setLoading(true);
        deleteReviewOp(product.id);
    };

    return (
        <div className="flex flex-col">
            <DialogModal />
            <div className="flex flex-wrap max-md:space-y-4 mt-2">
                {!sizeless && (
                <Select
                    property={size}
                    text="size"
                    data={product.allSizes.filter(
                        (item) => item.size !== size
                    )}
                    handleChange={setSize}
                />
                )}
                <Select
                    property={style}
                    text="style"
                    data={(product.colors ?? []).filter((item) => item?.color !== style?.color)}
                    // 서버는 {color, image} 를 읽는다 — 상품 색상의 그림은 colorImage 다.
                    handleChange={(c) => setStyle({ color: c.color, image: c.colorImage ?? c.image ?? null })}
                />
                {!sizeless && (
                <Select
                    property={fit}
                    text="fit"
                    data={fits.filter((item) => item !== fit)}
                    handleChange={setFit}
                />
                )}
            </div>
            <ImagesReview images={images} setImages={setImages} />
            <textarea
                className="mt-4 p-3 w-full rounded-md bg-white h-[200px] border border-slate-200 outline-none resize-none"
                name="review"
                value={review}
                onChange={(e) => setReview(e.target.value)}
                placeholder={t("product.review.placeholder")}
            ></textarea>
            <div className="flex flex-col md:flex-row items-center md:space-x-10">
                <Rating
                    className="mt-4"
                    name="half-rating-read"
                    defaultValue={0}
                    value={parseFloat(rating)}
                    precision={0.5}
                    onChange={(e) => setRating(e.target.value)}
                    style={{ color: "#FACF19", fontSize: "3rem" }}
                />
                <button
                    disabled={loading}
                    onClick={() => handleSubmit()}
                    className={`w-full mt-4  p-3  font-semibold rounded-md transition-all ${loading ? 'bg-gradient-to-r from-amazon-blue_light to-slate-400 text-white cursor-not-allowed' : 'bg-gradient-to-r from-amazon-orange to-yellow-300 text-amazon-blue_dark hover:scale-95'}`}
                >
                    {loading ? t("product.review.submitting") : myReview ? t("product.review.update") : t("product.review.submit")}
                </button>
                {/* 내 리뷰가 있을 때만 — 없는데 누르면 "삭제되었습니다" 가 뜨던 자리 */}
                {myReview && (
                    <button
                        disabled={loading}
                        onClick={handleDeleteReview}
                        className={`w-full mt-4 p-3 font-semibold rounded-md border transition-all ${loading ? 'bg-slate-200 text-slate-500 cursor-not-allowed' : 'bg-white text-red-600 border-red-300 hover:bg-red-50'}`}
                    >
                        {t("product.review.delete")}
                    </button>
                )}

            </div>
        </div>
    );
};

export default AddReview;
