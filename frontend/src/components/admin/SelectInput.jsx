import TextField from "@mui/material/TextField";
import MenuItem from "@mui/material/MenuItem";

import { ErrorMessage, useField } from "formik";
import Product from "components/cart/Product";
import { useTranslation } from "i18n/I18nProvider";

const SelectInput = ({
    data,
    label,
    handleChange,
    ...rest
}) => {
    const [field, meta] = useField(rest);
    const { t } = useTranslation();

            
    return (
        <div className="mt-4">
            <h3 className={`font-semibold border-b pb-1 mb-3 ${meta.touched && meta.error ? "text-red-500 border-red-500" : ""}`}>{label}</h3>
            <TextField sx={{ width: 300 }}
                select
                label={label}
                name={field.name}
                value={field.value}
                onChange={handleChange}
                disabled={rest.disabled ? true : false}
            >
                <MenuItem key={""} value={""}>
                    {t("admin.product_form.no_selection")}
                </MenuItem>
                {data.map((option) => (
                    <MenuItem key={option.id} value={option.id}>
                        {option.name } 
                    </MenuItem>
                ))}
            </TextField>
            {meta.touched && meta.error && (
                <div className="text-sm text-red-500">
                    <ErrorMessage name={field.name} />
                </div>
            )}
        </div>
    );
};

export default SelectInput;



