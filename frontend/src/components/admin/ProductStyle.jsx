import { useDispatch } from 'react-redux'
import { fileToResizedDataUrl } from "../../util/imageResize";
import { showDialog } from "../../redux/DialogSlice";
import { ExclamationTriangleIcon } from "@heroicons/react/24/outline";
import { ErrorMessage, useField } from "formik";
import { useRef } from "react";
import { useTranslation } from "i18n/I18nProvider";

const Style = ({ product, setProduct, colorImage, ...props }) => {
    const dispatch = useDispatch();
    const fileInput = useRef(null);
    const [field, meta] = useField(props);
    const { t } = useTranslation();

    const handleImage = (e) => {
        let img = e.target.files[0];        
        if (img == null)
            return;
        if (
            img.type !== "image/jpeg" && 
            img.type !== "image/png" &&
            img.type !== "image/webp"
        ) {
            dispatch(
                showDialog({
                    header: t("admin.product_form.style_format_header"),
                    msgs: {
                        msg: t("admin.product_form.style_format_msg", { name: img.name }),
                        type: "error",
                    },
                })
            );
            return;
        } else if (img.size > 1024 * 1024 * 10) {
            dispatch(
                showDialog({
                    header: t("admin.product_form.style_size_header"),
                    msgs: {
                        msg: t("admin.product_form.style_size_msg", { name: img.name }),
                        type: "error",
                    },
                })
            );
            return;
        } else {
            // 캐러셀 이미지와 같은 이유로 리사이즈한다(imageResize.js 주석 참조).
            fileToResizedDataUrl(img).then((dataUrl) => {
                let obj = {
                    color: product.color.color,
                    image: dataUrl,
                };
                setProduct({
                    ...product,
                    color: obj,
                });
            });
        }
    };

    return (
        <div className="flex flex-col">
        <div className={` ${meta.error ? "flex" : ""}`}>
            <div className="flex w-full">
                {meta.error && (<ExclamationTriangleIcon className="w-6 h-6" />)}
                <span className="w-full my-2 pb-2 border-b font-semibold">{t("admin.product_form.pick_style")}</span>
            </div>
            <span>
                {meta.touched && meta.error && (
                    <div className="">
                        <span></span>
                        <ErrorMessage name={field.name} />
                    </div>
                )}
            </span>
        </div>

        <input
            type="file"
            name={field.name}
            ref={fileInput}
            hidden
            accept="image/jpeg, image/png, image/webp, image/gif"
            onChange={handleImage}
        />

        <button
            type="reset"
            className={`w-52 bg-slate-300 p-2 rounded `}
            onClick={() => fileInput.current?.click()}
        >
            {t("admin.product_form.pick_style_button")}
        </button>
    </div>
    );
};

export default Style;
