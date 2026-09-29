import { MagnifyingGlassIcon } from "@heroicons/react/24/outline";
import { useState } from "react";
import { useSearchParams, useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "i18n/I18nProvider";

const Search = ({searchHandler}) => {
    const { t } = useTranslation();

    const [searchParams, setSearchParams] = useSearchParams();
    const {pathname} = useLocation();
    const navigate = useNavigate();
    const [query, setQuery] = useState(searchParams.get('search') ||"");

    const handleSearch = (e) => {
        e.preventDefault();
        
        if (query.length >= 1) {
            if (pathname !== "/browse") {                
                navigate(`/browse?search=${query}`);
                
            } else {
                searchHandler(query);
            }   
        } else if(query.length == 0 && pathname == "/browse") {
            searchHandler(query);
        }
    };

    return (
        <div className="flex-grow relative">
        <form
            onSubmit={(e) => handleSearch(e)}
            className="bg-amazon-orange flex flex-grow items-center rounded-md max-md:mx-4 max-md:mb-2"
        >
            <select
                defaultValue="All"
                className="hidden md:inline h-11 w-16 rounded-l text-gray-700 px-2 text-sm bg-gray-100 border-r border-gray-300 cursor-pointer outline-none"
            >
                {/* value 는 코드 값이라 그대로 두고, 보이는 이름만 번역한다. */}
                <option disabled value="All">
                    {t("header.search.all")}
                </option>
                <option value="Computers">{t("header.search.computers")}</option>
                <option value="Arts & Crafts">{t("header.search.arts_crafts")}</option>
                <option value="Baby">{t("header.search.baby")}</option>
                <option value="Book">{t("header.search.book")}</option>
            </select>
            <input
                 //onClick={() => setShowSearch((prev) => !prev)}
                type="text"
                className="outline-none w-full h-11 text-black pl-3 max-md:rounded-l"
                placeholder={t("header.search.placeholder")}
                 onChange={(e) => setQuery(e.target.value)}
                 defaultValue={query}
            />
            <button type="submit">
                <MagnifyingGlassIcon className="text-amazon-blue_dark h-8 w-8 my-1 mx-2 cursor-pointer" />
            </button>
        </form>
       </div>
    );

}

export default Search;