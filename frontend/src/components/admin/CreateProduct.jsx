import { Formik, Form } from "formik";
import * as Yup from "yup";
import SelectInput from './SelectInput';
import MultipleSelect from './MultipleSelect';
import AdminInput from "./AdminInput";
import { useDispatch } from "react-redux";
import DialogModal from "./DialogModal";
import { showDialog } from "../../redux/DialogSlice";
import ImagesProduct from "./ProductImages";
import Colors from "./ProductColors";
import Style from "./ProductStyle";
import Sizes from "./ProductSizes";
import Questions from "./ProductQuestions";
import Details from "./ProductDetails";
import dataURItoBlob from "../../util/dataURItoBlob";
import { useState } from "react";
import validateCreateProduct from '../../util/validation';
import { postRequest } from "../../util/api";
import slugify from "slugify";
import { useTranslation } from "i18n/I18nProvider";

// Blob 에는 파일명이 없다. 이름 없이 append 하면 브라우저가 "blob" 으로 보내고, 서버가
// 확장자로 컨텐트 타입을 정하는 경로에서 어긋난다. MIME 에서 확장자를 되돌린다.
const EXT_BY_MIME = {
    "image/jpeg": "jpg",
    "image/png": "png",
    "image/webp": "webp",
};
const extOf = (blob) => EXT_BY_MIME[blob.type] || "jpg";

const CreateProduct = ({
    parents,
    product,
    setProduct,
    categories,
    subs,
    images,
    setImages,
    setColorImage,
    colorImage,
    setLoading,
    loading,
    initialProduct,
}) => {
    const dispatch = useDispatch();
    const { t } = useTranslation();
    const [colors, setColors] = useState([]);
    const [progress, setProgress] = useState(0);
    // 방금 등록한 상품 — "색상 변형 이어서 등록" 배너의 근거. 서버 응답의 productId 를
    // 쓴다(엔티티 직렬화 시절에는 이 값이 응답에 없어서 이 흐름을 만들 수 없었다).
    const [lastCreated, setLastCreated] = useState(null);

    const validate = Yup.object({
        name: Yup.string()
            .required(t("admin.product_form.name_required"))
            .min(10, t("admin.product_form.name_length"))
            .max(300, t("admin.product_form.name_length")),
        brand: Yup.string().required(t("admin.product_form.brand_required")),
        category: Yup.string().required(t("admin.product_form.category_required")),
        // subCategories: Yup.array().min(1, "하위 카테고리를 하나 이상 선택해 주세요."),
        // sku 는 선택 입력이다 — 비우면 서버가 <카테고리 접두사>-<순번> 으로 생성한다.
        // 직접 넣으면 서버가 중복을 검사해 400 으로 알려준다.
        sku: Yup.string(),
        color: Yup.string().required(t("admin.product_form.color_required")),
        description: Yup.string().required(t("admin.product_form.description_required")),
    });
    // ⚠️ 컴포넌트와 같은 이름(CreateProduct)이던 것을 바꿨다. 안쪽 const 가 컴포넌트를
    //    가려서, 스택트레이스와 에디터의 "정의로 이동"이 전부 엉뚱한 곳을 가리켰다.
    const handleSubmit = async () => {
        const test = validateCreateProduct(product, images);
        if (test === "valid") {
            await createProductHandler();
        } else {
            dispatch(
                showDialog({
                    header: t("admin.product_form.invalid_header"),
                    msgs: test,
                })
            );
        }
    };

    // catalog-service 가 기대하는 것 (ProductController.addProduct):
    //
    //   POST /catalog-service/api/product   multipart/form-data
    //     product     : ProductRequest JSON  (@RequestPart)
    //     images      : 파일 N개             (@RequestParam List<MultipartFile>)
    //     colorImage  : 파일 1개             (@RequestParam MultipartFile)
    //
    // ⚠️ images·colorImage 는 시그니처상 required=false 지만 **실제로는 둘 다 필수**다.
    //    createSku() 가 images 를 그대로 순회하고 colorImage 를 무조건 업로드하므로
    //    비면 서버가 NPE 로 500 을 낸다. validateCreateProduct 가 이미 이미지 3장과
    //    스타일 이미지를 요구하므로 여기까지 왔다면 둘 다 있다.
    const createProductHandler = async () => {
        setLoading(true);
        try {
            const formData = new FormData();

            // 이미지는 화면에서 FileReader.readAsDataURL 로 담겨 data URL 상태다.
            for (let i = 0; i < images.length; i++) {
                const blob = dataURItoBlob(images[i]);
                formData.append("images", blob, `image-${i + 1}.${extOf(blob)}`);
            }

            const colorBlob = dataURItoBlob(product.color.image);
            formData.append("colorImage", colorBlob, `color.${extOf(colorBlob)}`);

            // ⚠️ color.image(=base64 data URL)를 JSON 에 실어 보내지 않는다. 같은 이미지를
            //    파일 파트로 이미 보내고 있어 본문이 두 배가 되고, Cloudflare 무료 플랜의
            //    100MB 업로드 제한에 훨씬 빨리 걸린다(그 경우 엣지에서 잘려 서버 로그에
            //    아무것도 남지 않는다). 서버도 request.getImages() 를 쓰지 않는다 —
            //    URL 은 S3 업로드 결과로 채워진다.
            const { image, ...color } = product.color;
            const slug = slugify(product.name, { lower: true }) || product.sku;
            const payload = { ...product, color, images: [], slug };

            formData.append(
                "product",
                new Blob([JSON.stringify(payload)], { type: "application/json" })
            );

            // ⚠️ contentType 을 반드시 "multipart/form-data" 로 넘긴다. api 인스턴스의 기본값이
            //    application/json 인데, axios 1.x 는 Content-Type 이 JSON 이면 **FormData 를
            //    JSON 으로 바꿔 버린다**(transformRequest). Blob 은 {} 가 되어 빈 본문이
            //    올라가고, 요청은 200 으로 성공한 것처럼 보인다. 실측으로 확인했다.
            //    문자열만 넘기면 실제 boundary 는 브라우저가 붙인다(resolveConfig 가
            //    FormData 일 때 Content-Type 을 지운다).
            const { data } = await postRequest(
                "/catalog-service/api/product",
                formData,
                "multipart/form-data",
                {
                    onUploadProgress: (e) => {
                        if (!e.total) return;
                        setProgress(Math.round((e.loaded * 100) / e.total));
                    },
                    // 이미지 여러 장을 올리므로 인스턴스 기본 15초로는 부족하다.
                    timeout: 120000,
                }
            );

            // 변형 연속 등록의 재료 — 새 상품일 때만 담는다(변형 추가면 부모를 유지한 채
            // 배너가 계속 살아 있는 편이 자연스럽다).
            if (data?.productId) {
                setLastCreated({ productId: data.productId, name: product.name });
            }
            setProduct(initialProduct);
            setImages([]);
            setColorImage("");
            setColors([]);
            dispatch(
                showDialog({
                    header: t("admin.product_form.created_header"),
                    msgs: [{
                        msg: t("admin.product_form.created_msg", { sku: data?.sku ?? product.sku }),
                        type: "success",
                    }],
                })
            );
        } catch (error) {
            // ⚠️ 예전에는 이 자리가 console.log 였다. 화면에는 아무 변화가 없어서
            //    "눌러도 아무 일이 없다"로 보였다. 실패는 반드시 사용자에게 보인다.
            const res = error?.response;
            const detail =
                res?.data?.message ||
                res?.data?.error ||
                (res ? `HTTP ${res.status}` : error.message);
            dispatch(
                showDialog({
                    header: t("admin.product_form.create_failed_header"),
                    msgs: [{ msg: detail, type: "error" }],
                })
            );
        } finally {
            // ⚠️ 여기가 이 화면이 고장 나 있던 이유다. 예전 코드는 setLoading(true) 뒤의
            //    블록이 통째로 주석 처리되어 setLoading(false) 에 **닿지 못했다.**
            //    버튼이 'Loading...' 에서 영원히 멈췄다. finally 로 옮겨 어떤 경로로도
            //    반드시 풀리게 한다.
            setLoading(false);
            setProgress(0);
        }
    };
    const handleChange = (e) => {


        const { value, name } = e.target;
        if (name === "subCategories") {

            const data = typeof value === "string" ? value.split(",") : value;
            setProduct({
                ...product,
                [name]: data,

            });


        } else {
            setProduct({
                ...product,
                [name]: value,
            });
        }
    };



    return (
        <div className="my-4">
            <div className="flex p-2 border-b pb-1 font-semibold">
                {t("admin.product_form.title")}
            </div>

            <DialogModal />

            {/* 색상 변형 연속 등록 — 예전에는 SKU 를 하나 더 붙이려면 Parent Product
                셀렉트에서 방금 만든 상품을 손으로 찾아야 했고, 목록이 갱신되지 않아
                대개 찾을 수도 없었다. 응답의 productId 로 parent 를 바로 채운다.
                parent 가 설정되면 카테고리·이름은 폼이 알아서 잠근다/채운다. */}
            {lastCreated && !product.parent && (
                <div className="flex items-center justify-between bg-green-50 border border-green-300 rounded p-3 my-2 text-sm">
                    <span>
                        <b>{lastCreated.name}</b> {t("admin.product_form.variant_created")}
                    </span>
                    <button
                        type="button"
                        data-testid="continue-variant"
                        className="px-3 py-1 rounded bg-green-500 text-white"
                        onClick={() => {
                            setProduct({
                                ...initialProduct,
                                parent: String(lastCreated.productId),
                            });
                        }}
                    >
                        {t("admin.product_form.continue_variant")}
                    </button>
                </div>
            )}

            <Formik
                enableReinitialize
                initialValues={{
                    name: product.name,
                    brand: product.brand,
                    description: product.description,
                    category: product.category,
                    subCategories: product.subCategories,
                    parent: product.parent,
                    sku: product.sku,
                    discount: product.discount,
                    color: product.color.color,
                    imageInputFile: "",
                    styleInput: "",
                }}
                validationSchema={validate}
                onSubmit={() => {
                    handleSubmit();
                }}
            >
                {(formik) => (
                    <Form>
                        <ImagesProduct
                            name="imageInputFile"
                            header={t("admin.product_form.carousel_header")}
                            text={t("admin.product_form.add_images")}
                            images={images}
                            setImages={setImages}
                            setColorImage={setColorImage}
                        />
                        <div className="flex flex-col">
                            <div className="flex items-center space-x-2">
                                {product.color.image && (
                                    <div className="relative ">
                                        <img
                                            src={product.color.image}
                                            alt={t("admin.product_form.color_image_alt")}
                                            //fill
                                            className="object-contain"
                                            width="30"
                                        />
                                    </div>
                                )}
                                {product.color.color && (
                                    <span
                                        className="w-12 h-12 rounded-full shadow my-2"
                                        style={{
                                            backgroundColor: `${product.color.color}`,
                                        }}
                                    ></span>
                                )}
                            </div>
                            <Colors
                                name="color"
                                product={product}
                                setProduct={setProduct}
                                colorImage={colorImage}
                                setColors={setColors}
                                colors={colors}
                            />
                            <Style
                                name="styleInput"
                                product={product}
                                setProduct={setProduct}
                                colorImage={colorImage}
                            />
                            <SelectInput
                                name="parent"
                                value={product.parent}
                                label={t("admin.product_form.parent_product")}
                                data={parents}
                                handleChange={handleChange}
                            />
                            <SelectInput
                                name="category"
                                value={product.category}
                                label={t("admin.product_form.category")}
                                data={categories}
                                handleChange={handleChange}
                                disabled={product.parent}
                            />
                            {product.category && (
                                <MultipleSelect
                                    name="subCategories"
                                    value={product.subCategories}
                                    data={subs}
                                    label={t("admin.product_form.sub_categories")}
                                    disabled={product.parent}
                                    handleChange={handleChange}
                                />
                            )}
                        </div>
                        <div className="flex flex-col">
                            <h3 className="p-2 border-b pb-1 font-semibold">
                                {t("admin.product_form.basic_info")}
                            </h3>
                            <AdminInput
                                type="text"
                                name="name"
                                label={t("admin.product_form.name")}
                                placeholder={t("admin.product_form.name_placeholder")}
                                icon="category"
                                onChange={handleChange}
                            />
                            <AdminInput
                                type="text"
                                name="description"
                                label={t("admin.product_form.description")}
                                placeholder={t("admin.product_form.description_placeholder")}
                                icon="category"
                                onChange={handleChange}
                            />
                            <AdminInput
                                type="text"
                                name="brand"
                                label={t("admin.product_form.brand")}
                                placeholder={t("admin.product_form.brand_placeholder")}
                                icon="category"
                                onChange={handleChange}
                            />
                            <AdminInput
                                type="text"
                                name="sku"
                                label={t("admin.product_form.sku")}
                                placeholder={t("admin.product_form.sku_placeholder")}
                                icon="category"
                                onChange={handleChange}
                            />
                            <AdminInput
                                type="text"
                                name="discount"
                                label={t("admin.product_form.discount")}
                                placeholder={t("admin.product_form.discount_placeholder")}
                                icon="category"
                                onChange={handleChange}
                            />
                            <AdminInput
                                type="text"
                                name="shippingFee"
                                label={t("admin.product_form.shipping_fee")}
                                placeholder={t("admin.product_form.shipping_fee_placeholder")}
                                icon="category"
                                onChange={handleChange}
                            />
                            <Sizes
                                sizes={product.sizes}
                                product={product}
                                setProduct={setProduct}
                            />
                            <Details
                                details={product.details}
                                product={product}
                                setProduct={setProduct}
                            />
                            <Questions
                                questions={product.questions}
                                product={product}
                                setProduct={setProduct}
                            />
                        </div>
                        <button
                            disabled={loading}
                            type="submit"
                            className={`my-4 w-52 py-2 px-4 rounded text-white ${loading ? ' bg-slate-400 ' : 'bg-green-500'}`}
                        >
                            {/* progress 를 계산만 하고 어디에도 쓰지 않아, 큰 이미지를
                                올리는 동안 화면이 멈춘 것처럼 보였다. */}
                            {loading
                                ? (progress > 0
                                    ? t("admin.product_form.uploading", { progress })
                                    : t("admin.product_form.loading"))
                                : t("admin.product_form.submit")}
                        </button>
                    </Form>
                )}
            </Formik>
        </div>
    );
};

export default CreateProduct;