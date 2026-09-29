import { useState } from "react";
import { Form, Formik } from "formik";
import * as Yup from "yup";
import InputAdmin from "./AdminInput";
import api from "../../util/api";
import SelectInput from "./SelectInput";
import slugify from "slugify";
import { useTranslation } from "i18n/I18nProvider";

const CreateSubCategory = (categories) => {
    const { t } = useTranslation();
    const [name, setName] = useState();
    const [parent, setParent] = useState();
    const validate = Yup.object({
        name: Yup.string()
            .required(t("admin.category.sub_name_required"))
            .min(3, t("admin.category.sub_name_length"))
            .max(32, t("admin.category.sub_name_length"))
            .matches(
                /^[aA-zZ]/,
                t("admin.category.name_pattern")
            ),
        parent: Yup.string().required(t("admin.category.parent_required")),
    });

    const submitHandler = async () => {
        
        try {
            const slug = slugify(name);            
            const { data } = await api.post("/catalog-service/api/category/subcategory", {
                name,
                parent,
                slug
            });

            //setSubCategories(data.subCategory);
            setName("");
            setParent("");
        } catch (error) {
            // console.log 는 운영 번들에서 지워진다 — 실패가 **아무 데도** 남지 않았다.
            console.error("하위 카테고리 추가 실패", error);
        }
    };

    const handleChange = (e) => {
        setName(e.target.value);
    };

    return (
        <div className="mt-4">
            <div className="flex p-2 border-b pb-1 font-semibold">
                {t("admin.category.create_sub_title")}
            </div>
            <Formik
                enableReinitialize
                initialValues={{ name, parent }}
                validationSchema={validate}
                onSubmit={() => submitHandler()}
            >
                {(form) => (
                    <Form className="flex-1 flex-col">
                        <InputAdmin
                            type="text"
                            name="name"
                            label={t("admin.category.sub_label")}
                            placeholder={t("admin.category.sub_name_placeholder")}
                            icon="category"
                            onChange={handleChange}
                        />
                        <SelectInput
                            name="parent"
                            value={parent}
                            data={categories}
                            label={t("admin.category.parent_label")}
                            handleChange={(e) => setParent(e.target.value)}
                        />
                        <button className="mt-4 bg-green-500 py-2 px-4 rounded mt-2 text-white">
                            {t("admin.category.add")}
                        </button>
                    </Form>
                )}
            </Formik>
        </div>
    );
};

export default CreateSubCategory;
