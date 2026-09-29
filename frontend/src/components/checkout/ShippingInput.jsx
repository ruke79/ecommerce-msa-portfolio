import { ErrorMessage, useField } from "formik";
import { useEffect, useRef, useState } from "react";

/**
 * Formik 에 연결된 텍스트 입력.
 *
 * <p><b>전개 순서가 중요하다.</b> 예전에는 `{...field}` 뒤에 `{...props}` 를 펼쳤다. 그래서
 * 호출부가 실수로 `onChange` 를 넘기면 <b>Formik 의 onChange 를 조용히 덮어썼고</b>, 값은
 * 여전히 Formik 이 소유한 채(`value` 는 field 에서 온다) 입력만 Formik 에 도달하지 않는
 * 상태가 됐다. 여기에 부모의 `enableReinitialize` 가 겹치면서 <b>입력한 글자가 유실됐다</b>
 * (docs/28 §2).
 *
 * <p>이제 `{...props}` 를 먼저 펼치고 `{...field}` 를 나중에 펼친다. 호출부는 className·type
 * 같은 것을 자유롭게 넘기되, <b>name/value/onChange/onBlur 는 언제나 Formik 이 이긴다.</b>
 * 같은 실수를 구조적으로 불가능하게 만드는 것이 주석으로 경고하는 것보다 낫다.
 */
const ShippingInput = ({ placeholder, ...props }) => {
    const inputRef = useRef(null);
    const [field, meta] = useField(props);
    const [move, setMove] = useState(false);

    useEffect(() => {
        // 값이 undefined 인 순간이 있으면(초기값 누락) .length 에서 터진다.
        if ((field.value ?? "").length > 0) {
            setMove(true);
        } else {
            setMove(false);
        }
    }, [field.value]);

    return (
        <div className="mx-3">
            <div
                className={`relative `}
                onFocus={() => setMove(true)}
                onBlur={() => setMove((field.value ?? "").length > 0)}
            >
                <input
                    ref={inputRef}
                    type={props.type}
                    // 떠 있는 라벨(span)은 input 과 연결돼 있지 않아 스크린리더에는 이름
                    // 없는 입력으로 들린다. placeholder 문구를 접근 가능한 이름으로 준다.
                    aria-label={placeholder}
                    {...props}
                    {...field}
                    className={`w-full rounded-xl py-4 px-3 outline outline-2  focus:outline focus:outline-slate-300 focus:outline-4  ${meta.touched && meta.error
                            ? "text-red-500 outline-red-200 bg-red-50 "
                            : "outline-slate-300"
                        }`}
                />
                <span
                    onClick={() => {
                        inputRef.current?.focus();
                        setMove(true);
                    }}
                    className={`absolute top-4 left-3 text-slate-500 ${move ? "move " : ""
                        }`}
                >
                    {placeholder}
                </span>
            </div>

            {meta.touched && meta.error && (
                <p className="mt-1 ml-2 text-red-500 text-sm">
                    <ErrorMessage name={field.name} />
                </p>
            )}
        </div>
    );
};

export default ShippingInput;
