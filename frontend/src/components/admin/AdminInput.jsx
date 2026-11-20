import { ErrorMessage, useField } from "formik";

const AdminInput = ({ type, placeholder, label, onChange, ...props }) => {
    const [field, meta] = useField(props);
    // 🐛 2026-11-14: field 를 뒤에 펼쳐 Formik 은 입력을 받게 됐지만(docs/28 §2), 그 대신 호출부의 onChange 가
    // **한 번도 불리지 않았다.** 세 화면(CreateCategory · CreateSubCategory · CreateProduct)은 제출할 때 Formik 값이
    // 아니라 그 onChange 가 채우는 화면 상태(name · product)를 읽는다 — 카테고리 추가는 slugify(undefined) 로 늘
    // "추가 실패", 상품 등록은 빈 이름 · 브랜드로 검증에 걸렸다. 그래서 둘 다 부른다: Formik 먼저(검증 · 값), 그다음 화면.
    // [JA] Formik と呼び出し側の onChange の両方を呼ぶ。以前は field を後ろに展開して Formik は受けたが、画面の
    //      onChange が呼ばれず、提出時に画面の状態を読む三画面(カテゴリー · サブカテゴリー · 商品)が空の値を送った。
    const handleChange = (e) => {
        field.onChange(e);
        if (onChange) {
            onChange(e);
        }
    };
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
                    // ⚠️ 전개 순서: props 먼저, field 나중, onChange 는 맨 끝에 둘을 함께 부르는 것으로.
                    // 호출부의 onChange 가 Formik 의 것을 덮으면 입력이 Formik 에 닿지 않아 컨트롤드 인풋이
                    // 밀리고 **입력한 글자가 유실된다**(docs/28 §2) — 반대로 field 만 두면 화면 상태가 빈다(위).
                    // [JA] ⚠️ 展開の順序: props が先、field が後、onChange は最後に両方を一緒に呼ぶ形で。
                    //      呼び出し側の onChange が Formik のものを上書きすると入力が Formik に届かず制御された入力が
                    //      ずれて**入力した文字が失われる**(docs/28 §2) — 逆に field だけにすると画面の状態が空になる(上)。
                    {...props}
                    {...field}
                    onChange={handleChange}
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