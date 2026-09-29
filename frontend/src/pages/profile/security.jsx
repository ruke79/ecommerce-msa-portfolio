import Layout from "components/profile/Layout";
import { useTranslation } from "i18n/I18nProvider";
import ButtonInput from "pages/user/ButtonInput";
import LoginInput from "pages/user/LoginInput";
import { Form, Formik } from "formik";
import { useState } from "react";
import * as Yup from "yup";
import DotLoaderSpinner from "components/loader/Loading"
import { putRequest } from "util/api";
import { useLoaderData, useNavigate } from "react-router-dom";
import { useAuthContext } from "store/AuthContext";
import SmsVerification from "components/notification/SmsVerification";
import AccountWithdrawal from "components/profile/AccountWithdrawal";
import LoginDevices from "components/profile/LoginDevices";
import SignOutEverywhere from "components/profile/SignOutEverywhere";
import toast from "react-hot-toast";
import tokenUtil from "util/tokenUtil";

const initialPassword = {
    current_password: "",
    new_password: "",
    conf_password: "",
    success: "",
    error: "",
};

const Security = () => {
    const { t } = useTranslation();

    const { tab } = useLoaderData();
    const { user, logout } = useAuthContext();
    const navigate = useNavigate();

    // 모든 기기에서 로그아웃한 뒤 — 이 기기도 로그아웃이다. 일반 로그아웃(useLogout)과 같은 정리를 한다.
    const afterSignOutEverywhere = () => {
        tokenUtil.remove();
        logout();
        navigate("/signin");
        toast.success(t("profile.sessions.done"));
    };

    
   

    const [loading, setLoading] = useState(false);
    const [phoneVerified, setPhoneVerified] = useState(false);
    const [newPassword, setNewPassword] = useState(initialPassword);
    const { current_password, new_password, conf_password , success, error} = newPassword;

    const validation = Yup.object({
        current_password: Yup.string()
            .required(t("profile.security.error.current_required"))
            .min(6, t("auth.reset.too_short", { min: 6 }))
            .max(36, t("user.validation.password_max", { max: 36 })),
        new_password: Yup.string()
            .required(t("profile.security.error.new_required"))
            .min(6, t("auth.reset.too_short", { min: 6 }))
            .max(36, t("user.validation.password_max", { max: 36 })),
        conf_password: Yup.string()
            .required(t("profile.security.error.confirm_required"))
            .oneOf([Yup.ref("new_password")], t("profile.security.error.mismatch")),
    });

    // Formik 이 검증을 통과시킨 값을 그대로 받는다. 페이지 state 는 더 이상 폼 값을 갖지
    // 않으므로(성공/실패 메시지 전용) 여기서 values 를 쓰지 않으면 항상 빈 값이 전송된다.
    const changePasswordHandler = async (values, { resetForm }) => {
        try {
            setLoading(true);

            const { data } = await putRequest("/user-service/api/user/profile/update-password", {
                current_password: values.current_password,
                new_password: values.new_password,
            });

            resetForm();

            setNewPassword({
                new_password: "",
                current_password: "",
                conf_password: "",
                success: data.message,
                error: "",
            });
            setLoading(false);
        } catch (error) {
            setLoading(false);
            setNewPassword({
                new_password: "",
                current_password: "",
                conf_password: "",
                success: "",
                error: error?.response?.data?.message ?? t("profile.security.error.failed"),
            });
        }
    };
    return (
        <>
            {loading && <DotLoaderSpinner loading={loading} />}
            <Layout user={user} tab={tab} title={t("profile.security.doc_title")}>
                <div className="text-center">
                    <h2 className="text-4xl font-bold mb-6">
                        {t("profile.security.title")}
                    </h2>
                </div>
                <div className="mx-auto md:w-1/3">
                    <Formik
                        // ⚠️ 폼 값은 Formik 이 소유한다. 페이지 state 를 initialValues 로
                        // 넘기고 입력마다 onChange 로 Formik 의 onChange 를 덮어쓰면 값의
                        // 출처가 둘이 되고, enableReinitialize 가 키 입력마다 폼을 초기화해
                        // **입력한 글자가 유실된다**(SignInPage/RegisterPage 와 같은 버그).
                        initialValues={{
                            current_password: "",
                            new_password: "",
                            conf_password: "",
                        }}
                        validationSchema={validation}
                        onSubmit={changePasswordHandler}
                    >
                        {(form) => (
                            <Form>
                                <LoginInput
                                    id="current_password"
                                    type="password"
                                    icon="password"
                                    name="current_password"
                                    placeholder={t("profile.security.current_password")}
                                />

                                <LoginInput
                                    id="password"
                                    type="password"
                                    icon="password"
                                    name="new_password"
                                    placeholder={t("profile.security.new_password")}
                                />
                                <LoginInput
                                    id="password-conf"
                                    type="password"
                                    icon="password"
                                    name="conf_password"
                                    placeholder={t("profile.security.confirm_password")}
                                />
                                <ButtonInput
                                    type="submit"
                                    text={t("profile.security.submit")}
                                />
                            </Form>
                        )}
                    </Formik>
                    <div className="mt-2 flex justify-center">
                        {error ? (
                            <p className="text-red-500">{error}</p>
                        ) : success ? (
                            <p className="text-green-500">{success}</p>
                        ) : (
                            ""
                        )}
                    </div>

                    {/* 휴대폰 인증. notification-service 의 SMS 인증을 쓰는 화면이 지금까지
                        어디에도 없었다 — 컴포넌트는 있는데 붙일 자리가 없어 사용자가
                        번호를 등록할 방법 자체가 없었다. 보안 설정이 있어야 할 자리다. */}
                    <div className="mt-10 border-t pt-6">
                        <SmsVerification
                            title={t("profile.security.sms_title")}
                            onVerified={() => setPhoneVerified(true)}
                        />
                        {phoneVerified && (
                            <p className="mt-2 text-green-600 text-sm">
                                {t("profile.security.sms_enabled")}
                            </p>
                        )}
                    </div>

                    {/* 로그인 기기. "새로운 기기에서 로그인되었습니다" 알림을 받은
                        사용자가 갈 곳이다 — 알림만 있고 확인할 화면이 없으면 정말
                        조치가 필요한 상황인지 판단할 근거가 없다. */}
                    <LoginDevices />

                    {/* 모든 기기에서 로그아웃 — 위 "기억 지우기" 는 로그아웃이 아니다. 알림을 받은 사용자가 실제로 끊을 수 있는 곳(docs/68 §8). */}
                    <SignOutEverywhere onSignedOut={afterSignOutEverywhere} />

                    {/* 회원 탈퇴. 계정을 없애는 동작이므로 보안 설정 화면에 둔다. 신청
                        중이면 이 자리가 "N일 뒤 삭제 / 취소하기" 패널로 바뀐다. */}
                    <AccountWithdrawal />
                </div>
            </Layout>
        </>
    );
};

export const loader = async ({params, request}) => {
       
        const searchParams = new URL(request.url).searchParams;
        const tab = Number(searchParams.get('tab')) || 0;

        return { tab : tab };   
        
};



export default Security;

