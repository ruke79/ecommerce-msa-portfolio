import { ChevronRightIcon } from "@heroicons/react/24/solid";
import { Form, Formik } from "formik";
import { useState, useEffect, useRef } from "react";
import { useNavigate, Link } from "react-router-dom";
import BrandMark from "components/common/BrandMark";
import { BRAND_NAME } from "constants/brand";
import * as Yup from "yup";
import api, { postRequest } from '../../util/api'
import LoginInput from "./LoginInput";
import ButtonInput from "./ButtonInput";
import { useAuthContext } from "../../store/AuthContext";
import toast from "react-hot-toast";
import { useMutation } from "@tanstack/react-query";
import { queryClient } from "../../util/api";
import { useReRenderer } from "hook/hooks";
import { useTranslation } from "i18n/I18nProvider";

const initialUser = {
    username: "",
    email: "",
    password: "",
    conf_password: "",
    success: "",
    error: ""
};

// SignInPage 의 signin 과 똑같은 문제가 있었다. catch 가 로그만 찍고 끝나 함수가 undefined
// 를 반환하므로 useMutation 은 **가입 실패를 성공으로 받는다** — "인증 메일을 보냈습니다"
// 토스트가 뜨고 로그인 화면으로 이동한다. 오지 않는 메일을 기다리게 만드는 최악의 실패다.
// (이미 가입된 이메일, 비밀번호 정책 위반 같은 서버 메시지는 콘솔에만 남았다.)
// console.log(userData) 도 지웠다 — 평문 비밀번호를 콘솔에 찍고 있었다.
const signup = (userData) =>
    postRequest("/user-service/api/auth/public/register", userData);

const REGISTER_QUERY_KEY = 'register';



const RegisterPage = () => {
    const { t } = useTranslation();
    const [loading, setLoading] = useState(false);
    const [user, setUser] = useState(initialUser);
    const [role, setRole] = useState();
    const { username, email, password, conf_password, success, error } = user;
    const { token } = useAuthContext();
    const navigate = useNavigate();

    const isLoadingRef = useRef(false);
    const reRender = useReRenderer();



    useEffect(() => {
        setRole("ROLE_USER");
    }, []);

    useEffect(() => {
        if (token) navigate("/");
    }, [navigate, token]);

    const { mutate } = useMutation({
        mutationFn: signup,
        throwOnError: false,
        onSuccess: (response) => {

            setUser({
                ...user, error: "", success: response.message
            })

            setLoading(false);
            toast.success(t("user.register.sent"));

            queryClient.invalidateQueries({ queryKey: [REGISTER_QUERY_KEY] });
            navigate("/signin");
        },
        onError: (error) => {

            setLoading(false);
            const message = error?.response?.data?.message;
            setUser({
                ...user, success: "", error: message ?? t("user.register.failed")
            })
            // 서버가 알려준 이유가 있으면 그것을 보여 준다. "Failed to register user" 만
            // 띄우면 사용자는 이미 가입된 이메일인지 비밀번호가 약한지 알 수 없다.
            toast.error(message ?? t("user.register.failed_toast"));
        }
    });

    const registerValidation = Yup.object({
        // 🐛 예전 규칙은 `/^[aA-zZ]/` 였다. 두 가지가 틀렸다.
        //
        // 1. **한글 이름을 거부했다.** 한국어 UI 에, 한국 택배사와 한국 SMS 를 쓰는
        //    서비스인데 "홍길동"으로는 가입이 되지 않았다.
        // 2. 문자 클래스 `A-z` 는 Z 와 a 사이의 `[ \ ] ^ _ \`` 까지 포함한다. 즉 "특수문자
        //    금지"라면서 `_홍길동` 같은 값은 통과시켰다.
        //
        // 의도("숫자·특수문자 금지")를 그대로 옮긴다: 유니코드 글자와 공백만 허용하므로
        // 한글·일본어·라틴 문자가 모두 통과하고 숫자와 기호는 걸린다.
        username: Yup.string()
            .required(t("user.register.name_required"))
            .min(2, t("user.register.name_length", { min: 2, max: 16 }))
            .max(16, t("user.register.name_length", { min: 2, max: 16 }))
            .matches(/^[\p{L}][\p{L}\s]*$/u, t("user.register.name_invalid")),
        email: Yup.string()
            .required(t("user.validation.email_required"))
            .email(t("user.validation.email_invalid")),
        password: Yup.string()
            .required(t("user.validation.password_required"))
            .min(6, t("auth.reset.too_short", { min: 6 }))
            .max(36, t("user.validation.password_max", { max: 36 })),
        conf_password: Yup.string()
            .required(t("user.register.confirm_required"))
            .oneOf([Yup.ref("password")], t("user.register.password_mismatch"))
    });

    const signUpHandler = async (data) => {
        // console.log(data) 를 지웠다 — 평문 비밀번호를 브라우저 콘솔에 찍고 있었다.
        const { username, email, password } = data;
        const sendData = {
            username,
            email,
            password,
            role: [role],
        };

        mutate(sendData);

    }



    return (
        <>
            <div className="flex flex-col mx-auto w-full px-4 sm:w-3/5 md:w-3/5 lg:w-2/5  pt-8 pb-16">
                <div className="mx-auto my-2">
                    <Link to="/">
                        {/* 예전에는 Amazon 로고 png 였다 — 계정 생성·로그인 화면에
                            남의 로고를 거는 것이라 교체했다(constants/brand.js 주석). */}
                        <BrandMark variant="dark" size="lg" className="pt-2" />
                    </Link>
                </div>
                <div className="flex flex-col p-4 my-4 bg-white rounded border space-y-4">
                    <h3 className="text-xl font-bold">{t("user.register.heading")}</h3>
                    <Formik
                        // ⚠️ 폼 값은 Formik 이 소유한다. 예전에는 여기 initialValues 가
                        // 페이지 state 를 가리키고 각 입력이 페이지의 onChange 로 Formik 의
                        // onChange 를 덮어써서, 값의 출처가 둘이 됐다. enableReinitialize 가
                        // 키 입력마다 폼을 초기화하는 바람에 **입력한 글자가 유실됐다** —
                        // "a@example.com" 을 치면 "@xml.o" 가 남았다(회원가입 불가).
                        initialValues={{
                            username: "",
                            email: "",
                            password: "",
                            conf_password: "",
                        }}
                        //disabled={isLoadingRef.current}
                        validationSchema={registerValidation}
                        // onSubmit={() => {
                        //     if (isLoadingRef.current) {
                        //         return;
                        //     }
                        //     isLoadingRef.current = true;
                        //     reRender();
                        //     signUpHandler();
                        //     isLoadingRef.current = false;
                        //     reRender();
                        // }
                        onSubmit={signUpHandler}                        
                    >
                        {(form) => (
                            <Form>
                                <LoginInput
                                    id="input-name"
                                    type="text"
                                    icon="user"
                                    name="username"
                                    placeholder={t("user.register.name_placeholder")}
                                />
                                <LoginInput
                                    id="input-email"
                                    type="text"
                                    icon="email"
                                    name="email"
                                    placeholder={t("user.email_placeholder")}
                                />

                                <LoginInput
                                    id="input-password"
                                    type="password"
                                    icon="password"
                                    name="password"
                                    placeholder={t("user.register.password_placeholder")}

                                />
                                <LoginInput
                                    id="input-password-conf"
                                    type="password"
                                    icon="password"
                                    name="conf_password"
                                    placeholder={t("user.register.confirm_placeholder")}

                                />
                                <ButtonInput type="submit" text={t("user.register.submit")} />
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

                    <p className="text-xs my-2">
                        {t("user.terms_notice", { brand: BRAND_NAME })}
                    </p>


                    <span className="pt-1 relative flex justify-center text-sm 
                before:left-1 before:top-[50%] before:absolute before:bg-slate-200 before:h-[1px] before:w-[10%] sm:before:w-[18%] md:before:w-[22%]
                after:right-1 after:top-[50%] after:absolute after:bg-slate-200 after:h-[1px] after:w-[10%] sm:after:w-[18%] md:after:w-[22%]">
                        {t("user.register.other_accounts")}
                    </span>

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
}

export default RegisterPage;