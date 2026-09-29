import { Swiper, SwiperSlide } from "swiper/react";
import { Navigation } from "swiper/modules";
import { similarProducts } from "./similarProducts";

import "swiper/css";
import "swiper/css/navigation";
import { Link } from "react-router-dom";
import { useTranslation } from "i18n/I18nProvider";


const SimilarSwiper = () => {
    const { t } = useTranslation();
    return (
        <div className="my-1 mx-2">
            <h4 className="mb-2 font-semibold border-b pb-1">
                {t("product.similar.title")}
            </h4>
            <Swiper
                slidesPerView={4}
                spaceBetween={1}
                slidesPerGroup={3}
                navigation={true}
                modules={[Navigation]}
                className="products-swiper"
                breakpoints={{
                    640: {
                        slidesPerView: 5,
                    },
                }}
            >
                {similarProducts.map((p, i) => (
                    <SwiperSlide key={i}>
                        <Link to={`/`}>
                            <img
                                src={p}
                                alt={p}
                                width={150}
                                height={150}
                                className=""
                            />
                        </Link>
                    </SwiperSlide>
                ))}
            </Swiper>
        </div>
    );
};

export default SimilarSwiper;
