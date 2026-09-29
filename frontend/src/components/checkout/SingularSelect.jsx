import { MenuItem, TextField } from "@mui/material";
import { ErrorMessage, useField } from "formik";
import { useTranslation } from "i18n/I18nProvider";

const SingularSelect = ({data, handleChange, placeholder, ...rest}) => {
    const { t } = useTranslation();
    const [field, meta] = useField(rest);
    return ( 
        <div className="mx-3 ">
            <TextField
                sx={{outline: 'none'}}
                name={field.name}
                select
                label={placeholder}
                value={field.value}
                onChange={handleChange}
                className={`w-full rounded-xl outline outline-2 focus:outline focus:outline-slate-300 focus:outline-4  ${
                    meta.touched && meta.error
                        ? "text-red-500 outline-red-200 bg-red-50 "
                        : "outline-slate-300"
                }`}
            >
                <MenuItem key="" value="">
                    {t("checkout.address.select_none")}
                </MenuItem>
                {/* 🐛 key={option.id} 였다. countries 항목은 {name, code} 라 id 가 없어서
                    약 250개 전부 key 가 undefined 였다 — React 가 목록 항목을 구분하지
                    못하고, 개발 콘솔에는 매 렌더마다 경고가 찍혔다. */}
                {/* 값은 나라 코드, 보이는 것은 화면 언어의 이름(util/country · docs/60 §11).
                    예전에는 영어 이름이 값이라 일본어 화면에서도 목록·저장값이 영어였다. */}
                {data.map((option) => (
                    <MenuItem key={option.code ?? option.name} value={option.code ?? option.name}>
                        {option.name}
                    </MenuItem>
                ))}
            </TextField>
            {
                meta.touched && meta.error && (
                    <p className="mt-1 ml-2 text-red-500 text-sm"><ErrorMessage name={field.name} /></p>
                )
            }
        </div>
     );
}
 
export default SingularSelect;