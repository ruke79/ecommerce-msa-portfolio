import { MinusCircleIcon, PlusCircleIcon } from "@heroicons/react/24/outline";
import { useTranslation } from "i18n/I18nProvider";

const Questions = ({ questions, product, setProduct }) => {
    const { t } = useTranslation();

    const handleQuestions = (i, e) => {
        const values = [...questions];
        values[i][e.target.name] = e.target.value;
        setProduct({
            ...product,
            questions: values,
        });
    };
    const handleRemove = (i) => {
        if (questions.length > 0) {
            const values = [...questions];
            values.splice(i, 1);
            setProduct({
                ...product,
                questions: values,
            });
        }
    };

    return (
        <div>
            <div className="my-2 pb-2 font-semibold border-b">{t("admin.product_form.questions")}</div>

            {questions?.length === 0 ? (
                <PlusCircleIcon
                    className="cursor-pointer w-8 h-8 hover:scale-[1.2] transition"
                    onClick={() =>
                        setProduct({
                            ...product,
                            questions: [
                                ...questions,
                                {
                                    question: "",
                                    answer: "",
                                },
                            ],
                        })
                    }
                />
            ) : (
                ""
            )}

            {questions
                ? questions.map((item, i) => (
                      <div className="my-5 flex items-center" key={i}>
                          <input
                              type="text"
                              name="question"
                              placeholder={t("admin.product_form.question")}
                              value={item.question}
                              onChange={(e) => handleQuestions(i, e)}
                              className="ml-4 mr-2 border  rounded p-2 w-44  focus:bg-slate-200"
                          />
                          <input
                              type="text"
                              name="answer"
                              placeholder={t("admin.product_form.answer")}
                              value={item.answer}
                              onChange={(e) => handleQuestions(i, e)}
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
                                          questions: [
                                              ...questions,
                                              {
                                                  question: "",
                                                  answer: "",
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

export default Questions;
