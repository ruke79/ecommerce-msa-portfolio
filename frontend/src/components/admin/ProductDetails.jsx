import { MinusCircleIcon, PlusCircleIcon } from "@heroicons/react/24/outline";
import { useTranslation } from "i18n/I18nProvider";

const Details = ({ details, product, setProduct }) => {
    const { t } = useTranslation();
    const handleDetails = (i, e) => {
        const values = [...details];
        values[i][e.target.name] = e.target.value;
        setProduct({
            ...product,
            details: values,
        });
    };
    const handleRemove = (i) => {
        if (details.length > 0) {
            const values = [...details];
            values.splice(i, 1);
            setProduct({
                ...product,
                details: values,
            });
        }
    };

    return (
        <div>
            <div className="my-2 pb-2 font-semibold border-b">{t("admin.product_form.details")}</div>

            {details.length === 0 ? (
                <PlusCircleIcon
                    className="cursor-pointer w-8 h-8 hover:scale-[1.2] transition"
                    onClick={() =>
                        setProduct({
                            ...product,
                            details: [
                                ...details,
                                {
                                    name: "",
                                    value: "",
                                },
                            ],
                        })
                    }
                />
            ) : (
                ""
            )}

            {details
                ? details.map((detail, i) => (
                      <div className="my-5 flex items-center" key={i}>
                          <input
                              type="text"
                              name="name"
                              placeholder={t("admin.product_form.detail_name")}
                              value={detail.name}
                              onChange={(e) => handleDetails(i, e)}
                              className="ml-4 mr-2 border  rounded p-2 w-44  focus:bg-slate-200"
                          />
                          <input
                              type="text"
                              name="value"
                              placeholder={t("admin.product_form.detail_value")}
                              value={detail.value}
                              onChange={(e) => handleDetails(i, e)}
                              className="ml-4 mr-2 border  rounded p-2 w-44  focus:bg-slate-200"
                          />

                          <div className="flex">
                              <MinusCircleIcon
                                  className="cursor-pointer w-8 h-8 hover:scale-[1.2] transition"
                                  onClick={() => handleRemove(i)}
                              />

                              <PlusCircleIcon
                                  className="cursor-pointer w-8 h-8 hover:scale-[1.2] transition"
                                  onClick={() =>
                                      setProduct({
                                          ...product,
                                          details: [
                                              ...details,
                                              {
                                                  name: "",
                                                  value: "",
                                              },
                                          ],
                                      })
                                  }
                              />
                          </div>
                      </div>
                  ))
                : ""}
        </div>
    );
};

export default Details;
