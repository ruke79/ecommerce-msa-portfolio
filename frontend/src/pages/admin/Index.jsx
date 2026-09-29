import CreateProduct from "../../components/admin/CreateProduct";
import StorageHealthBanner from "../../components/admin/StorageHealthBanner";
import { useEffect, useState } from "react";
import api, { getRequest } from "../../util/api";
import { loadCategories } from "../../components/admin/CreateCategory";
import { saveDraft, loadDraft, clearDraft } from "../../util/formDraft";
import { translate, useTranslation } from "i18n/I18nProvider";

const initialState = {
    name: "",
    description: "",
    brand: "",
    sku: "",
    discount: 0,
    images: [],
    //description_images: [],
    parent: "",
    category: "",
    subCategories: [],
    color: {
        color: "",
        image: "",
    },
    sizes: [
        {
            size: "",
            quantity: "",
            price: "",
        },
    ],
    details: [
        {
            name: "",
            value: "",
        },
    ],
    questions: [
        {
            question: "",
            answer: "",
        },
    ],
    shippingFee: "",
};

// 초안 저장 키. 이미지 없이 텍스트 필드만 저장된다(formDraft.js 주석 참조).
const DRAFT_KEY = "admin-product-draft";

/**
 * 실패를 화면 문구로. 컴포넌트 밖에 두어 effect 의 의존성이 되지 않게 한다 — 안에 두고 t 를 쓰면
 * 언어를 바꿀 때마다 effect 가 다시 돌아 요청이 또 나간다. 실패한 <b>그 순간</b>의 언어로 만든다.
 */
const describeError = (e, fallbackKey) =>
    e?.response?.status === 429
        // 게이트웨이 레이트리미터(burst 40 · 초당 20)다. 예전에는 이 화면이
        // 키 입력마다 요청을 보내서 429 가 쉽게 났고, 아무도 처리하지 않았다.
        ? translate("admin.index.rate_limited")
        : (e?.response?.data?.message ?? translate(fallbackKey));

const AdminProduct = () => {
    const { t } = useTranslation();
    const [parents, setParents] = useState([]);
    // 초안이 있으면 그걸로 시작한다 — 긴 폼이 새로고침 한 번에 날아가던 것을 지킨다.
    const [product, setProduct] = useState(() => {
        const draft = loadDraft(DRAFT_KEY);
        return draft ? { ...initialState, ...draft, images: [] } : initialState;
    });
    const [subs, setSubs] = useState([]);
    const [categories, setCategories] = useState([]);
    const [colorImage, setColorImage] = useState("");
    const [images, setImages] = useState([]);
    //const [descriptionImages, setDescriptionImages] = useState("");
    const [loading, setLoading] = useState(false);

    // 입력할 때마다 초안을 갱신한다. 폼이 초기 상태로 돌아가면(등록 성공 후 리셋)
    // 초안도 지운다 — 안 지우면 다음 방문 때 "지난 초안" 이라며 빈 폼이 복구된다.
    useEffect(() => {
        if (JSON.stringify(product) === JSON.stringify(initialState)) {
            clearDraft(DRAFT_KEY);
        } else {
            saveDraft(DRAFT_KEY, product);
        }
    }, [product]);

    // 요청이 실패했을 때 화면에 보여줄 것. 예전에는 세 effect 의 async 함수에 catch 가
    // 하나도 없어서, 실패는 콘솔의 "처리되지 않은 거절"로만 남고 화면은 그대로였다 —
    // 관리자는 목록이 비어 있는 것이 장애인지 데이터가 없는 건지 알 수 없었다.
    const [loadError, setLoadError] = useState("");

    // 부모 상품 목록과 카테고리 목록은 **마운트 때 한 번**이면 된다.
    useEffect(() => {
        let cancelled = false;

        (async () => {
            try {
                const { data } = await api.get("/catalog-service/api/product/products");
                if (!cancelled && data) {
                    setParents(data);
                }
            } catch (e) {
                if (!cancelled) {
                    setLoadError(describeError(e, "admin.index.load_products_failed"));
                }
            }
        })();

        // loadCategories 는 예전에 아래 effect 안에 있었다 — product.category 와
        // product.name 이 바뀔 때마다 카테고리 전체를 다시 받았다는 뜻이다.
        loadCategories(setCategories);

        return () => { cancelled = true; };
    }, []);

    useEffect(() => {
        if (!product.parent) return undefined;

        let cancelled = false;

        (async () => {
            try {
                const { data } = await getRequest(
                    `/catalog-service/api/product/edit/${product.parent}`);
                if (cancelled || !data) return;

                /**
                 * 🐛 예전에는 `setProduct({ ...product, ... })` 였다. `product` 는 이
                 * effect 가 만들어질 때 닫힌 **오래된 값**이다. 응답이 오는 사이 관리자가
                 * 입력한 내용이 그 낡은 스냅샷으로 덮였다 — 타이핑하던 글자가 사라진다.
                 * 함수형 갱신은 항상 최신 상태를 받는다.
                 */
                setProduct((prev) => ({
                    ...prev,
                    name: data.name,
                    description: data.description,
                    brand: data.brand,
                    category: data.category?.id ?? "",
                    subCategories: Array.from(data.subCategories ?? [], (sub) => sub.id),
                    questions: data.questions,
                    details: data.details,
                    discount: data.discount,
                }));
            } catch (e) {
                if (!cancelled) {
                    setLoadError(describeError(e, "admin.index.load_parent_failed"));
                }
            }
        })();

        return () => { cancelled = true; };
    }, [product.parent]);

    /**
     * 🐛 의존성에 `product.name` 이 들어 있었다.
     *
     * 그래서 <b>상품 이름을 한 글자 칠 때마다</b> 서브카테고리 조회(그리고 예전에는
     * 카테고리 전체 조회까지)가 다시 나갔다. 이름이 20자면 요청 20번이다. 게이트웨이
     * 레이트리미터가 burst 40 · 초당 20 이라 빠르게 치면 429 가 났고, 429 는 어디서도
     * 처리되지 않아 서브카테고리 목록이 조용히 비었다.
     *
     * 서브카테고리는 <b>카테고리</b>에만 달려 있다. 이름과는 아무 관계가 없다.
     */
    useEffect(() => {
        if (!product.category) {
            setSubs([]);
            return undefined;
        }

        let cancelled = false;

        (async () => {
            try {
                const { data } = await getRequest(
                    "/catalog-service/api/category/product/subcategories", {
                        params: { category: product.category },
                    });
                if (!cancelled) {
                    setSubs(data ?? []);
                }
            } catch (e) {
                if (!cancelled) {
                    setSubs([]);
                    setLoadError(describeError(e, "admin.index.load_subcategories_failed"));
                }
            }
        })();

        return () => { cancelled = true; };
    }, [product.category]);

    

    return (
        <>
            {/* 이미지 업로드가 죽어 있으면 폼을 채우기 전에 알려 준다 — 등록은 전부
                이미지에 걸려 있고, 실패는 제출 뒤에야 나타난다. */}
            <div className="px-4 pt-4">
                <StorageHealthBanner />
                {loadError && (
                    <div className="mt-2 flex items-center justify-between gap-3 rounded border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-700">
                        <span>{loadError}</span>
                        <button
                            type="button"
                            className="shrink-0 underline"
                            onClick={() => setLoadError("")}
                        >
                            {t("admin.index.close")}
                        </button>
                    </div>
                )}
            </div>
            <CreateProduct
                parents={parents}
                product={product}
                setProduct={setProduct}
                categories={categories}
                subs={subs}
                images={images}
                setImages={setImages}
                setColorImage={setColorImage}
                colorImage={colorImage}
                setLoading={setLoading}
                loading={loading}
                initialProduct={initialState}
            />
        </>
    );
};

export default AdminProduct;

