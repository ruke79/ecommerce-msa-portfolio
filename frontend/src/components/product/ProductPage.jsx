import { Link } from "react-router-dom";
import { useEffect, useState } from "react";

import BreadCrumb from "./BreadCrumb";
import Infos from "./Infos";
import ShippingInfo from "./ShippingInfo";
import MainSwiper from "./MainSwiper";
import Reviews from "./reviews/Reviews";
import SimilarSwiper from "./SimilarSwiper";
import { useFetchReviews } from "hook/hooks";




const ProductPage = ({ product }) => {
    const [activeImg, setActiveImg] = useState("");

    const { reviews, isSuccess, isPending, updateFetchReviews } = useFetchReviews(product.id);

    

    useEffect(() => {

        
        if (isSuccess) {
            
            function calculatePercentage(num) {
                return (
                    reviews.length > 0 ? (reviews.reduce((total, review) => {
                        return (
                            total +
                            (review.rating === Number(num) ||
                                review.rating === Number(num) + 0.5)
                        );
                    }, 0) *
                        100) /
                        reviews.length : 0
                ).toFixed(1);
            }

            product.ratings = [

                {
                    percentage: calculatePercentage("5"),
                },
                {
                    percentage: calculatePercentage("4"),
                },
                {
                    percentage: calculatePercentage("3"),
                },
                {
                    percentage: calculatePercentage("2"),
                },
                {
                    percentage: calculatePercentage("1"),
                },
            ];
        }

        
    }, [isPending]);

    

    useEffect(() => {

        window.scrollTo({ top: 0, left: 0, behavior: 'auto' });

    }, []);

    if (isPending) return;

    return (
        <div className="w-full bg-white h-auto px-3 mb-6 md:px-2">
            <BreadCrumb
                category={product.category}
                subCategories={product.subCategories}
            />
            <div className="grid grid-row-8 md:grid-cols-8 gap-4">
                <MainSwiper images={product.images} activeImg={activeImg} />
                <Infos product={product} num_reviews={reviews?.num_reviews} setActiveImg={setActiveImg} />
                <ShippingInfo product={product} />
            </div>

            <div className="mt-2 mx-auto w-full md:w-4/5 p-2 border rounded-lg">
                <SimilarSwiper />
            </div>
            <Reviews product={product} num_reviews={reviews?.num_reviews} userReviews={reviews?.reviews} updateReviews={updateFetchReviews} />
        </div>
    );
};

export default ProductPage;
