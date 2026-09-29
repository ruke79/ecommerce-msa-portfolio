import { useState } from "react";
import CreateSubCategory from 'components/admin/CreateSubCategory';

const SubCategories = ({ categories, subCategories }) => {
    const [data, setData] = useState(subCategories);

    return (
        <>
            <CreateSubCategory setSubCategories={setData} categories={categories} />
            <div className="mt-4">
                {data?.map((cat, i) => (
                    <div key={i}>{cat.name}</div>
                ))}
            </div>
        </>
    );
};

export default SubCategories;