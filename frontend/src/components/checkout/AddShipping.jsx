import { Form, Formik } from "formik";
import { useEffect } from "react";
import * as Yup from "yup";
import "yup-phone";

import ShippingInput from "./ShippingInput";
import SingularSelect from "./SingularSelect";
import { saveShippingAddress, updateShippingAddress } from "util/api";
import { useTranslation } from "i18n/I18nProvider";
import { countryOptions, toCountryCode } from "util/country";

/**
 * 저장된 배송지를 폼 값으로. id·active 같은 폼에 없는 키는 싣지 않는다.
 *
 * <p>나라는 코드로 바꾼다 — 옛 주소는 영어 이름("Korea, Republic of")으로 저장돼 있어, 그대로 두면 선택지(코드)와
 * 맞지 않아 칸이 비어 보인다. 고쳐 저장하면 코드로 바뀐다. 목록에 없는 값은 그대로 둔다.
 */
const toFormValues = (address, blank) => {
    const values = Object.fromEntries(Object.keys(blank).map((k) => [k, address?.[k] ?? ""]));
    if ("country" in values) values.country = toCountryCode(values.country) ?? values.country;
    return values;
};



const AddShipping = ({
    setLoading,
    shipping,
    setShipping,       
    invalidate, 
    initialValue,    
    /** 고치는 중인 배송지. null 이면 새로 등록한다. */
    editing = null,
    onCancelEdit,
}) => {
    const { t, lang } = useTranslation();
    // 안내 문구는 렌더마다 지금 언어로 만든다 — 스키마를 모듈에 두면 처음 언어로 굳는다.
    const validate = Yup.object({
        // 🐛 이름 최소 길이가 3이었다. 한국 이름은 성이 1글자("홍"), 이름이 2글자("길동")인
        // 경우가 가장 흔하므로 **정상적인 한국 이름이 거의 다 거부됐다.** 회원가입의
        // username 정규식과 같은 부류의 문제다(docs/28 §3) — 서구식 이름을 전제한 검증이
        // 한국어 서비스에 그대로 남아 있었다.
        //
        // 문구도 한국어로 옮겼다. 원래는 "charactes"·"atleast" 같은 오타가 그대로
        // 사용자에게 보였고(docs/40 #16), 한국어 화면에 영어 안내만 섞여 있었다.
        firstname: Yup.string()
            .required(t("checkout.address.error.firstname_required"))
            .min(1, t("checkout.address.error.firstname_required"))
            .max(20, t("checkout.address.error.firstname_max")),
        lastname: Yup.string()
            .required(t("checkout.address.error.lastname_required"))
            .min(1, t("checkout.address.error.lastname_required"))
            .max(20, t("checkout.address.error.lastname_max")),
        // 메시지가 전부 "First Name ..." 으로 복사돼 있어서, 전화번호가 틀려도 이름이
        // 잘못됐다는 안내가 떴다.
        phoneNumber: Yup.string()
            .required(t("checkout.address.error.phone_required"))
            .min(3, t("checkout.address.error.phone_min"))
            .max(20, t("checkout.address.error.phone_max")),
        state: Yup.string()
            .required(t("checkout.address.error.state_required"))
            .min(2, t("checkout.address.error.state_min"))
            .max(60, t("checkout.address.error.state_max")),
        city: Yup.string()
            .required(t("checkout.address.error.city_required"))
            .min(2, t("checkout.address.error.city_min"))
            .max(60, t("checkout.address.error.city_max")),
        zipCode: Yup.string()
            .required(t("checkout.address.error.zip_required"))
            .min(2, t("checkout.address.error.zip_min"))
            .max(30, t("checkout.address.error.zip_max")),
        // 🐛 address1 의 안내가 전부 "Address 2" 였다 — 기본 주소를 비우면 상세 주소가
        // 잘못됐다는 말이 떴다. 위 phoneNumber 와 똑같은 복사 실수다.
        address1: Yup.string()
            .required(t("checkout.address.error.address1_required"))
            .min(5, t("checkout.address.error.address1_min"))
            .max(100, t("checkout.address.error.address1_max")),
        address2: Yup.string()
            .min(5, t("checkout.address.error.address2_min"))
            .max(100, t("checkout.address.error.address2_max")),
        country: Yup.string()
            .required(t("checkout.address.error.country_required"))
            .min(2, t("checkout.address.error.country_min"))
            .max(40, t("checkout.address.error.country_max")),
    });

    
            
    /**
     * 주소를 저장한다.
     *
     * <p>🐛 예전에는 Formik 의 values 대신 페이지 state(`shipping`)를 저장했다. 그런데 그
     * state 는 각 입력이 Formik 의 onChange 를 덮어쓰는 방식으로만 갱신됐고, 그 경합 때문에
     * <b>입력한 글자가 유실됐다</b>(docs/28 §2). 즉 사용자가 본 주소와 저장된 주소가 달랐다.
     *
     * <p>주소는 결제 후 delivery-service 가 스냅샷으로 복사해 가는 값이라, 한 번 잘못
     * 저장되면 주문·배송·분쟁 기록에 그대로 남고 물건도 엉뚱한 곳으로 간다. 이제 Formik 이
     * 검증한 values 를 그대로 저장한다.
     */
    const submitHandler = async (values, { resetForm }) => {
        if (editing?.id) {
            await updateShippingAddress(editing.id, values);
            onCancelEdit?.();
        } else {
            await saveShippingAddress(values);
        }

        invalidate();
        resetForm();
        setShipping(initialValue);
    };

    // 고칠 배송지가 바뀌면 폼을 그 값으로 **다시 마운트**한다(key). enableReinitialize 는
    // 쓰지 않는다 — 부모 상태가 바뀔 때마다 폼을 초기화해 입력이 유실되던 버그가 그것이었다.
    const formValues = editing ? toFormValues(editing, initialValue) : initialValue;

    return (
        <Formik
            key={editing?.id ?? "new"}
            // 폼 값의 주인은 Formik 하나다. 부모 state 를 initialValues 로 넘기고
            // enableReinitialize 를 켜면 키 입력마다 폼이 초기화되어 입력이 유실된다.
            initialValues={formValues}
            validationSchema={validate}
            onSubmit={submitHandler}
        >
            {(formik) => (
                <Form className="grid grid-cols-1 gap-6">
                    {editing && (
                        <div className="rounded-lg border border-amazon-blue_light/40 bg-slate-50 px-4 py-2 text-sm text-slate-700"
                             role="status">
                            {t("checkout.address.editing", {
                                firstname: editing.firstname ?? "",
                                lastname: editing.lastname ?? "",
                                address: editing.address1 ?? "",
                            })}
                        </div>
                    )}
                    <div className="grid md:grid-cols-2 gap-6">
                        <SingularSelect
                            name="country"
                            value={formik.values.country}
                            placeholder={t("checkout.address.country")}
                            handleChange={formik.handleChange}
                            data={countryOptions(lang)}
                        />
                    </div>
                    <div className="grid md:grid-cols-2 gap-6">
                        <ShippingInput
                            type="text"
                            name="firstname"
                            placeholder={t("checkout.address.first_name")}
                        />
                        <ShippingInput
                            type="text"
                            name="lastname"
                            placeholder={t("checkout.address.last_name")}
                        />
                    </div>
                    <div className="grid md:grid-cols-2 gap-6">
                        <ShippingInput
                            type="text"
                            name="state"
                            placeholder={t("checkout.address.state")}
                        />
                        <ShippingInput
                            type="text"
                            name="city"
                            placeholder={t("checkout.address.city")}
                        />
                    </div>
                    <div className="grid md:grid-cols-2 gap-6">
                        <ShippingInput
                            type="text"
                            name="zipCode"
                            placeholder={t("checkout.address.zip_code")}
                        />
                        <ShippingInput
                            type="text"
                            name="phoneNumber"
                            placeholder={t("checkout.address.phone")}
                        />
                    </div>
                    <ShippingInput
                        type="text"
                        name="address1"
                        placeholder={t("checkout.address.address1")}
                    />
                    <ShippingInput
                        type="text"
                        name="address2"
                        placeholder={t("checkout.address.address2")}
                    />
                    <div className="flex gap-3 mx-3 mb-4">
                        <button
                            type="submit"
                            className={`flex-1 py-4 rounded-xl bg-amazon-orange text-amazon-blue_dark font-bold bg-gradient-to-r from-amazon-orange to-yellow-300 text-amazon-blue_dark  hover:text-slate-100 hover:from-amazon-blue_light hover:to-slate-300  `}
                        >
                            {editing ? t("checkout.address.update") : t("checkout.address.save")}
                        </button>
                        {editing && (
                            <button
                                type="button"
                                onClick={() => { formik.resetForm(); onCancelEdit?.(); }}
                                className="px-6 py-4 rounded-xl border border-slate-300 font-bold text-slate-700 hover:bg-slate-100"
                            >
                                {t("checkout.address.cancel")}
                            </button>
                        )}
                    </div>
                </Form>
            )}
        </Formik>
    );
};

export default AddShipping;
