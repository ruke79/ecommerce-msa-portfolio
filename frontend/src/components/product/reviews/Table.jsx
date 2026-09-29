import { useState } from "react";
import { usePagination } from "./Pagination";
import ReviewCard from "./ReviewCard";
import { Pagination } from "@mui/material";
import TableHeader from "./TableHeader";

const Table = ({ reviews, allSizes, colors, onLikeChange }) => {
    // console.log("table review: ", reviews);
    const [page, setPage] = useState(1);
    const PER_PAGE = 3;
    const count = Math.ceil(reviews.length / PER_PAGE);
    const _DATA = usePagination(reviews, PER_PAGE);

    const handleChange = (e, p) => {
        setPage(p);
        _DATA.jump(p);
    };

    return (
        <div className="mt-4 flex flex-col">
            <TableHeader
                reviews={reviews}
                allSizes={[{ size: "All" }, ...allSizes]}
                colors={[{ color: "All", image: "" }, ...colors]}
            />
            <div className="my-2">
                {/*
                  * key 는 reviewId 다. 배열 인덱스를 쓰면 페이지를 넘기거나 목록이 바뀔 때
                  * React 가 서로 다른 리뷰를 같은 요소로 취급해, 좋아요 상태(각 카드의
                  * pending 플래그)가 엉뚱한 카드에 남는다.
                  */}
                {_DATA.currentData().map((review, i) => (
                    <ReviewCard
                        review={review}
                        key={review.reviewId ?? i}
                        onLikeChange={onLikeChange}
                    />
                ))}
            </div>
            <div className="mt-2 ml-auto">
                <Pagination
                    count={count}
                    page={page}
                    shape="rounded"
                    onChange={handleChange}
                />
            </div>
        </div>
    );
};

export default Table;
