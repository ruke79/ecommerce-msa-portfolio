import { ErrorMessage, useField } from "formik";

const AdminInput = ({ type, placeholder, label, ...props }) => {
    const [field, meta] = useField(props);
    return (
        <div className="mt-4">
            <label className={`flex ${meta.touched && meta.error ? "error-input-admin" : ""}`}>
                <span className="w-[135px] px-4 py-2 bg-blue-500 rounded-l text-white">
                    {label}
                </span>
                <input  
                    className="border  rounded-r px-4 py-2 outline-none "
                    type={type}
                    placeholder={placeholder}
                    // ⚠️ 전개 순서: props 먼저, field 나중. 반대로 두면 호출부가 넘긴
                    // onChange 가 Formik 의 onChange 를 덮어써서 입력이 Formik 에 도달하지
                    // 않는다. value 는 여전히 field 에서 오므로 컨트롤드 인풋이 밀리고
                    // **입력한 글자가 유실된다**(docs/28 §2). CreateCategory·
                    // CreateSubCategory·CreateProduct 가 모두 이 컴포넌트를 쓰므로 여기서
                    // 한 번 고치면 세 화면이 함께 낫는다.
                    {...props}
                    {...field}
                />
            </label>
            {meta.touched && meta.error && (
                <div className="text-sm text-slate-700">
                    <ErrorMessage name={field.name} />
                </div>
            )}
        </div>
    );
};

export default AdminInput;