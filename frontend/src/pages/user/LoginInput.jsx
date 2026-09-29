import { UserIcon } from "@heroicons/react/24/outline";
import { LockClosedIcon, PaperAirplaneIcon } from "@heroicons/react/24/solid";
import { ErrorMessage, useField } from "formik";
import { useTranslation } from "i18n/I18nProvider";

/** 비밀번호 칸의 이름표 — 필드 이름으로 고른다. 없는 이름은 그냥 "비밀번호". */
const PASSWORD_LABEL_KEYS = {
    conf_password: "user.input.confirm_password",
    current_password: "user.input.current_password",
    new_password: "user.input.new_password",
};

const LoginInput = ({ icon, placeholder, type, ...props}) => {
    const { t } = useTranslation();
    const [field, meta] = useField(props);
    return (
        <div className={`flex mt-4 ${meta.touched && meta.error ? 'error-input' : ''}`}>
            <label className="w-full font-semibold ">
                {icon === "email" ? (
                    <div className="flex items-center mb-1">
                        <PaperAirplaneIcon className="h-4 -rotate-45 mr-1" />
                        {t("user.input.email")}
                    </div>
                ) : icon === "password" ? (
                    <div className="flex items-center mb-1">
                        <LockClosedIcon className="h-4 mr-1" />
                        {t(PASSWORD_LABEL_KEYS[field.name] ?? "user.input.password")}
                    </div>
                ) : icon === "user" ? (
                    <div className="flex items-center mb-1">
                        <UserIcon className="h-4 mr-1" />
                        {t("user.input.full_name")}
                    </div>
                ) : ''}
                <input
                    type={type}
                    // name={type}
                    placeholder={placeholder}
                    // ⚠️ 전개 순서: props 를 먼저, field 를 나중에.
                    // 반대로 두면 호출부가 넘긴 onChange 가 Formik 의 onChange 를 덮어써서
                    // 입력이 Formik 에 도달하지 않는다. 값은 여전히 field.value 에서 오므로
                    // 컨트롤드 인풋이 한 렌더씩 밀리고, 결국 **입력한 글자가 유실된다**
                    // (docs/28 §2 — 회원가입에서 "a@example.com" 이 "@xml.o" 가 됐다).
                    {...props}
                    {...field}
                    className="text-sm w-full placeholder:font-normal p-2 outline-none rounded border focus:shadow-amazon-orange"
                />
                {
                    (meta.touched && meta.error) && (
                        <div className="flex text-sm text-red-500">
                            <ErrorMessage name={field.name} />
                        </div>
                    )
                }
            </label>
        </div>
    );
};

export default LoginInput;