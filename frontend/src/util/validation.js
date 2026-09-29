import { translate } from "i18n/I18nProvider";

export const componentColors = [
    "amber",
    "brown",
    "orange",
    "purple",
    "primary",
    "red",
    "green",
    "blue",
    "grey",
    "yellow",
    "secondary",
  ];

export const validateEmail = (email) => {
    const regextSt = /^(([^<>()[\]\\.,;:\s@"]+(\.[^<>()[\]\\.,;:\s@"]+)*)|(".+"))@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\])|(([a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/;
    return regextSt.test(email);
}

export const checkHtml = (value) => {
    return value && /<([A-Za-z][A-Za-z0-9]*)\b[^>]*>(.*?)<\/\1>/.test(value);
  };




const validateCreateProduct = (product, images) => {
    let sizes = product.sizes;
    let details = product.details;
    let questions = product.questions;

    const checks = [{
        msg: translate("validation.product.basics_ok"),
        type: "success"
    }];

    if(images && images.length < 3) {
        checks.push({
            msg: translate("validation.product.images_short", { count: 3 - images.length }),
            type: "error"
        })
    } else {
        checks.push({
            msg: translate("validation.product.images_ok"),
            type: "success"
        })
    } 
    if (!product.color.color) {
        checks.push({
            msg: translate("validation.product.color_missing"),
            type: "error"
        })
    } else {
        checks.push({
            msg: translate("validation.product.color_ok"),
            type: "success"
        })
    }
    if (!product.color.image) {
        checks.push({
            msg: translate("validation.product.style_image_missing"),
            type: "error"
        })
    } else {
        checks.push({
            msg: translate("validation.product.style_image_ok"),
            type: "success"
        })
    }
    // ⚠️ 서버가 `new BigDecimal(request.getShippingFee())` 를 그대로 부른다. 비어 있거나
    //    숫자가 아니면 NumberFormatException 으로 **500** 이 나고, 화면에는 원인이 안 뜬다.
    //    Yup 스키마에도 이 필드가 없어서 여기서 막지 않으면 아무도 안 막는다.
    //    단, 색상 변형 추가(parent 지정)에서는 배송비가 부모 상품 소유라 서버가 읽지
    //    않는다 — 요구하면 변형 등록이 불가능해진다.
    if (!product.parent &&
        (product.shippingFee === undefined || product.shippingFee === "" ||
        Number.isNaN(Number(product.shippingFee)))) {
        checks.push({
            msg: translate("validation.product.shipping_fee_invalid"),
            type: "error"
        })
    } else {
        checks.push({
            msg: translate("validation.product.shipping_fee_ok"),
            type: "success"
        })
    }
    for (let i = 0; i < sizes.length; i++ ) {
        if(sizes[i].quantity === "" || sizes[i].price === "" || sizes[i].size === "" ) {
            checks.push({
                msg: translate("validation.product.sizes_missing"),
                type: "error"
            })
            break;
        } else {
            checks.push({
                msg: translate("validation.product.sizes_ok"),
                type: "success"
            })
        }
    }
    for (let i = 0; i < details.length; i++ ) {
        if(details[i].name === "" || details[i].value === "" ) {
            checks.push({
                msg: translate("validation.product.details_missing"),
                type: "error"
            })
            break;
        } else {
            checks.push({
                msg: translate("validation.product.details_ok"),
                type: "success"
            })
        }
    }
    for (let i = 0; i < questions.length; i++ ) {
        if(questions[i].question === "" || questions[i].answer === "" ) {
            checks.push({
                msg: translate("validation.product.questions_missing"),
                type: "error"
            })
            break;
        } else {
            checks.push({
                msg: translate("validation.product.questions_ok"),
                type: "success"
            })
        }
    }
    let s_test = checks.find((item) => item.type === "error");

    if(s_test) {
        return checks;
    } else {
        return "valid";
    }
}

export default validateCreateProduct;