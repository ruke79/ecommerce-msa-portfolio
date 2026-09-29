import { useState } from "react";
import CreateCategory from "../../components/admin/CreateCategory";


const Categories = ({ categories }) => {
    const [data, setData] = useState(categories);

    return (      
        <>          
            <CreateCategory setCategories={setData} />
            <div className="mt-4">
                {data?.map((cat, i) => (
                    <div key={i}>{cat.name}</div>
                ))}
            </div>                  
        </>  
    );
};

export default Categories;