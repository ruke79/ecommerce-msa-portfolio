import { useDispatch } from 'react-redux';
import { fileToResizedDataUrl } from "../../util/imageResize";
import { showDialog } from "../../redux/DialogSlice";
import { ErrorMessage, useField } from "formik";
import { useRef } from "react";
import { useTranslation } from "i18n/I18nProvider";
import noImage from "../../assets/images/no-image.png";
import { ArrowsPointingInIcon, SwatchIcon, TrashIcon } from "@heroicons/react/24/outline";

const ImagesProduct = ({
    images,
    setImages,
    header,
    text,
    setColorImage,
    ...props
}) => {
    const dispatch = useDispatch();
    const fileInput = useRef(null);    
    const [field, meta] = useField(props);
    const { t } = useTranslation();

    const handleImages = (e) => {
        e.preventDefault();        
        let files = Array.from(e.target.files);        
        files.forEach((img, i) => {
            if (images.length === 6) {
                // 사용자에게는 바로 아래 dialog 로 알린다. "err" 한 글자를 콘솔에
                // 찍는 것은 아무에게도 도움이 되지 않았다.
                dispatch(
                    showDialog({
                        header: t("admin.product_form.max_images_header"),
                        msgs: {
                            msg: t("admin.product_form.max_images_msg"),
                            type: "error",
                        },
                    })
                );
                return;
            }
            if (
                img.type !== "image/jpeg" &&
                img.type !== "image/png" &&
                img.type !== "image/webp"
            ) {
                dispatch(
                    showDialog({
                        header: t("admin.product_form.format_header"),
                        msgs: {
                            msg: t("admin.product_form.format_msg", { name: img.name }),
                            type: "error",
                        },
                    })
                );
                files = files.filter((item) => item !== img.name);
                return;
            } else if (img.size > 1024 * 1024 * 1024) {
                dispatch(
                    showDialog({
                        header: t("admin.product_form.format_header"),
                        msgs: {
                            msg: t("admin.product_form.size_msg", { name: img.name }),
                            type: "error",
                        },
                    })
                );
                return;
            } else {
                // 원본 그대로 담지 않는다 — 폰 사진(장당 5~12MB)을 3장 이상 담으면
                // 요청 하나가 수십 MB 가 되고, Cloudflare 무료 플랜의 100MB 제한에
                // 걸리면 **엣지에서 잘려 서버 로그에 아무것도 남지 않는다**(docs/33 §11).
                // 긴 변 1920px 로 줄이면 장당 수백 KB 다. 실패하면 원본으로 폴백한다.
                fileToResizedDataUrl(img).then((dataUrl) => {
                    setImages((images) => [...images, dataUrl]);
                });
            }
        });
    };

    const handleRmove = (image) => {
        setImages((images) => images.filter((item) => item !== image))
    }
    
    return (
        <div className="flex flex-col">
            <div className={` ${meta.error ? "flex" : ""}`}>
                <div className="flex">
                    {meta.error && t("admin.product_form.images_warning")}
                    <span className=" font-semibold">{header}</span>
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
                multiple
                accept="image/jpeg, image/png, image/webp, image/gif"
                onInput={(e)=>handleImages(e)}  
                onClick={(e)=> { 
                    e.currentTarget.value = null
               }}
            />

            <div className="flex my-4">
                <div
                    className={`gap-2 ${
                        images.length === 2
                            ? "grid grid-cols-2"
                            : images.length === 2
                            ? "grid grid-cols-2"
                            : images.length === 3
                            ? "grid grid-cols-3"
                            : images.length === 4
                            ? "grid grid-cols-4"
                            : images.length === 5
                            ? "grid grid-cols-5"
                            : images.length === 6
                            ? "grid grid-cols-6"
                            : "grid grid-cols-6"
                    }`}
                >
                    {!images.length
                        ? (
                            <img src={noImage} width={500} height={500}  className="object-contain" alt={t("admin.product_form.no_image_alt")} />
                        )
                        : images.map((img, i) => (
                              <div className="relative" key={i}>
                                  
                                  <div className="relative mr-10" >
                                      <img
                                          src={img}
                                          //fill
                                          className="object-cover rounded "
                                          alt={t("admin.product_form.image_alt")}  width="100"                                             
                                      />
                                  </div>
                                    <div className="absolute z-50 top-0 right-0 bottom-0 left-0 hover:bg-slate-600/[0.5] transition rounded duration-300 ">
                                      <div className="btn-edit-post z-50">
                                        <button type="button" onClick={() => handleRmove(img)}><TrashIcon className="w-6 h-6" /></button>
                                        <button type="button" onClick={() => setColorImage(img)}><SwatchIcon className="w-6 h-6" /></button>
                                        <button><ArrowsPointingInIcon className="w-6 h-6" /></button>
                                      </div>
                                    </div>
                              </div>
                          ))}
                </div>
            </div>

            <button
                type="reset"
                disabled={images.length === 6}
                className={`w-52 bg-slate-300 p-2 rounded ${
                    images.length === 6 && "opacity-50"
                }`}
                onClick={() => fileInput.current?.click()}
            >
                {text}
            </button>
        </div>
    );
};

export default ImagesProduct;
