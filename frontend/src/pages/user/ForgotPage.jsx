import { Form, Formik } from "formik";
import { Link } from "react-router-dom";
import { useState } from "react";
import BrandMark from "components/common/BrandMark";
import LoginInput from "./LoginInput";
import * as Yup from "yup";
import ButtonInput from "./ButtonInput";
import api, { postRequest } from "util/api";
import { useTranslation } from "i18n/I18nProvider";

import DotLoaderSpinner from 'components/loader/Loading'

import { ChevronRightIcon } from "@heroicons/react/24/outline";

const initialUser = {
    email: "",
    success: "",
    error: ""
};

const ForgotPage = ({ providers }) => {
    const { t } = useTranslation();
    const [loading, setLoading] = useState(false);
    const [user, setUser] = useState(initialUser);
    const { email, success, error } = user;

    const handleChange = (e) => {
        const { name, value } = e.target;
        setUser({
            ...user,
            [name]: value,
        });
    };

    const emailValidation = Yup.object({
        email: Yup.string()
            .required(t("user.validation.email_required"))
            .email(t("user.validation.email_invalid")),
    });


    // Formik 이 소유한 값을 받는다 — 페이지 state 는 더 이상 폼 값을 갖지 않는다.
    const forgotHandler = async (values, { resetForm }) => {
        try {
            setLoading(true)

            const { data } = await postRequest("/user-service/api/auth/public/forgot-password", {
                email: values.email
            });

            setUser({
                ...user, email: "", success: data.message, error: ""
            })

            setLoading(false);

        } catch (error) {
            setLoading(false)
            setUser({
                ...user, success: "", error: error.response?.data.message
            })
        }
    }

    return (
        <>
            {
                loading && (
                    <DotLoaderSpinner loading={loading} />
                )
            }
            <div className="flex flex-col mx-auto w-full px-4 sm:w-3/5 md:w-3/5 lg:w-2/5  pt-8 pb-16">
                <div className="mx-auto my-2">
                    <Link to="/">
                        {/* 예전에는 Amazon 로고 png 였다 — 계정 생성·로그인 화면에
                            남의 로고를 거는 것이라 교체했다(constants/brand.js 주석). */}
                        <BrandMark variant="dark" size="lg" className="pt-2" />
                    </Link>
                </div>
                <div className="flex flex-col p-4 my-4 bg-white rounded border space-y-4">
                    <h3 className="text-xl font-bold">{t("user.forgot.heading")}</h3>
                    <Formik
                        // ⚠️ 폼 값은 Formik 이 소유한다. 페이지 state 를 initialValues 로
                        // 넘기고 입력마다 onChange 로 Formik 의 onChange 를 덮어쓰면 값의
                        // 출처가 둘이 되고, enableReinitialize 가 키 입력마다 폼을 초기화해
                        // **입력한 글자가 유실된다**(SignInPage/RegisterPage 와 같은 버그).
                        initialValues={{
                            email: "",
                        }}
                        validationSchema={emailValidation}
                        onSubmit={forgotHandler}
                    >
                        {(form) => (
                            <Form>
                                <LoginInput
                                    id="input-email"
                                    type="text"
                                    icon="email"
                                    name="email"
                                    placeholder={t("user.email_placeholder")}
                                />
                                <ButtonInput type="submit" text={t("user.forgot.submit")} />
                            </Form>
                        )}
                    </Formik>

                    <div className="flex">
                        {error ? (
                            <p className="text-red-500">{error}</p>
                        ) : success ? (
                            <p className="text-green-500">{success}</p>
                        ) : ''}
                    </div>

                    <div className="text-sm flex items-center pt-4">
                        <span className="text-black ml-1">
                            {t("user.already_have_account")}
                        </span>
                        <Link
                            className="flex items-center text-blue-500 hover:text-amazon-orange hover:underline ml-2"
                            to="/signin"
                        >
                            {t("user.signin_link")}
                            <ChevronRightIcon className="h-3 text-gray-500" />
                        </Link>
                    </div>
                </div>

            </div>
        </>
    );
};

export default ForgotPage;
