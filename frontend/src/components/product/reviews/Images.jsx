import { MinusIcon } from "@heroicons/react/24/outline";
import { useRef, useState } from "react";
import { useTranslation } from "i18n/I18nProvider";

const ImagesReview = ({ images, setImages }) => {
    const { t } = useTranslation();
    const [error, setError] = useState("");
    const inputRef = useRef(null);

    const handleImages = (e) => {
        let files = Array.from(e.target.files);
        files.forEach((img, i) => {
            if (images.length === 3 || i === 2) {
                setError(t("product.review.images.max", { max: 3 }));
                return;
            }
            if (
                img.type !== "image/jpeg" &&
                img.type !== "image/png" &&
                img.type !== "image/webp"
            ) {
                setError(t("product.review.images.unsupported", { name: img.name }));
                files = files.filter((item) => item.name !== img.name);
                return;
            } else if (img.size > 1024 * 1024) {
                // 예전 문구는 "max 5MB" 였지만 실제 검사는 1MB 다 — 검사하는 값을 그대로 보여 준다.
                setError(t("product.review.images.too_large", { name: img.name, max: "1MB" }));
                files = files.filter((item) => item.name !== img.name);
            } else {
                setError("");
                const reader = new FileReader();
                reader.readAsDataURL(img);
                reader.onload = (e) => {
                    setImages((images) => [...images, e.target.result]);
                };
            }
        });
    };

    const removeImg = (image) => {
        setImages((images) => images.filter((img) => img !== image));
        if (images.length <= 3) {
            {
                setError("");
            }
        }
    };

    return (
        <div className="mt-5 grid md:grid-cols-4 ">
            <div className="flex flex-col justify-center">
                <input
                    type="file"
                    ref={inputRef}
                    hidden
                    multiple
                    onChange={handleImages}
                    accept="image/png,image/jpeg,image/webp"
                />
                <button
                    className="bg-amazon-blue_light md:w-52 p-2 text-white rounded-md hover:scale-95 transition"
                    onClick={() => inputRef.current?.click()}
                >
                    {t("product.review.images.add")}
                </button>
                <span className="text-red-500 text-sm mt-2">{error}</span>
            </div>
            <div className="flex max-md:mt-4 md:col-span-3">
                {images.length > 0 &&
                    images.map((img, i) => (
                        <span className="relative mx-2" key={i}>
                            <span className=" bg-slate-300 p-1 rounded-full absolute -top-3 -right-2  z-10 shadow-md transition cursor-pointer hover:bg-slate-400">
                                <MinusIcon
                                    className="w-4 h-4"
                                    onClick={() => removeImg(img)}
                                />
                            </span>
                            <img
                                src={img}
                                alt={img}
                                width={100}
                                height={50}
                                className="rounded-md  outline hover:outline-1 hover:outline-offset-2 hover:outline-slate-500 transition cursor-pointer"
                            />
                        </span>
                    ))}
            </div>
        </div>
    );
};

export default ImagesReview;
