import { useEffect, useState } from "react";
import { Form, Formik } from "formik";
import * as Yup from "yup";
import AdminInput from "./AdminInput";
import { getRequest, postRequest } from "../../util/api";
import SelectInput from "./SelectInput";
import slugify from "slugify";
import toast from "react-hot-toast";
import { translate, useTranslation } from "i18n/I18nProvider";


export const loadCategories = async(setCategories) => {
    try {
        const response = await getRequest("/catalog-service/api/category/categories");
      
            
      setCategories(response.data);            

       } catch(error) {
           // 컴포넌트 밖의 함수다 — 토스트를 띄우는 그 순간의 언어로 만든다.
           toast.error(translate("admin.category.load_failed"));
           
       }  
}

export const loadSubCategories = async(setSubCategories) => {
    try {
        const response = await getRequest("/catalog-service/api/category/product/allsubcategories");      
      
      
      setSubCategories(response.data);            

       } catch(error) {
            toast.error(translate("admin.category.load_sub_failed"));
           
       }  
}

const CreateCategory = () => {
    const { t } = useTranslation();
    const [name, setName] = useState();
    const [subcategoryName,  setSubcategoryName] = useState();
    
    const [parent, setParent] = useState("");
    
    const [categories, setCategories ] = useState([]);
    const [subCategories, setSubCategories ] = useState([]);
    const [refresh, setRefresh] = useState(false);
    const [refresh2, setRefresh2] = useState(false);

    useEffect( () => {
                
        loadCategories(setCategories);                
        setRefresh(false);
        
      }, [refresh]);

      useEffect( () => {
                
        loadSubCategories(setSubCategories);                
        setRefresh2(false);
        
      }, [refresh2]);

    const validate = Yup.object({
        name: Yup.string()
        .required(t("admin.category.name_required"))
        .min(3,t("admin.category.name_length"))
        .max(32,t("admin.category.name_length"))
        .matches(/^[aA-zZ]/,t("admin.category.name_pattern")),
    });

    const validate2 = Yup.object({
        subcategoryName: Yup.string()
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
            const reqData = {
               name, slug
            };            
            const { response } = await postRequest("/catalog-service/api/category/category", reqData);

            //  let categoryNames = [] ;     

                        
            //  categoryNames.push({"id" : response.data.id, "name" : response.data.name});
              
            //   setCategories(categories || categoryNames );     

            //setName("")
        } catch (error ) {            
            toast.error(t("admin.category.add_failed"));
        }

        setRefresh(true);
    };
    const handleCategoryChange = (e) => {
        setName(e.target.value)
    }

    const handleSubCategoryChange = (e) => {
        setSubcategoryName(e.target.value)
    }

    
    const submitSubCategoryHandler = async () => {

        
        try {
            
            const slug = slugify(subcategoryName);
            const { response } = await postRequest("/catalog-service/api/category/subcategory", {
                subcategoryName,
                parent,
                slug
            });

                        
            setSubcategoryName("");
            setParent("");

            setRefresh2(true);
        } catch (error) {
            console.error("하위 카테고리 추가 실패", error);
            toast.error(t("admin.category.add_sub_failed"));
            
        }
       
    };


    return (
        <>
        <div className="mt-4">
            <div className="flex p-2 border-b pb-1 font-semibold">
                {t("admin.category.create_title")}
            </div>
            <Formik
                enableReinitialize
                initialValues={{ name }}
                validationSchema={validate}
                onSubmit={() => submitHandler()}
            >
                {(form) => (
                    <Form className="flex-1 flex-col">
                        <AdminInput
                            type="text"
                            name="name"
                            label={t("admin.category.label")}
                            placeholder={t("admin.category.name_placeholder")}
                            icon="category"
                            onChange={handleCategoryChange}
                        />
                        <button className="mt-4 bg-green-500 py-2 px-4 rounded mt-2 text-white">
                            {t("admin.category.add")}
                        </button>
                    </Form>
                )}
            </Formik>            
            {categories?.map((cat, i) => (
                    <div key={i}>{cat.name}</div>
                ))}
        </div>
        
        
          <div className="mt-4">
          <div className="flex p-2 border-b pb-1 font-semibold">
              {t("admin.category.create_sub_title")}
          </div>
          <Formik
              enableReinitialize
              initialValues={{ subcategoryName, parent }}
              validationSchema={validate2}
              onSubmit={() => submitSubCategoryHandler()}
          >
              {(form) => (
                  <Form className="flex-1 flex-col ">
                      <AdminInput
                          type="text"
                          name="subcategoryName"
                          label={t("admin.category.sub_label")}
                          placeholder={t("admin.category.sub_name_placeholder")}
                          icon="category"
                          onChange={handleSubCategoryChange}
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
          {subCategories?.map((cat, i) => (
                    <div key={i}>{t("admin.category.sub_row", { parent: cat.parentName, name: cat.name })}</div>
                ))}
      </div>
      </>        
    );
};

export default CreateCategory;
