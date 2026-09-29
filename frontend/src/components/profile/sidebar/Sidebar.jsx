import Item from "./Item";
import { profile } from "./profile";
import Avatar from "components/common/Avatar";
import { displayName } from "util/userDisplay";

/**
 * 🐛 예전에는 `data.image` 와 `data.username` 을 그대로 읽었다. 둘 다 JWT 에서 만든
 * 사용자 객체에 <b>없는 필드</b>다(util/jwtUser.js 의 toUser 는 email·userId·roles 만
 * 만든다). 그래서 아바타는 깨진 이미지가 되고 이름 자리는 빈 칸이었다 —
 * "My Profile 화면에 아무것도 안 보인다"의 절반이 이것이다.
 */
const Sidebar = ({ data }) => {
    return (
        <div>
            <div className="flex flex-col items-center ">
                <Avatar
                    user={data}
                    size={100}
                    className="outline outline-2 outline-offset-[3px] outline-slate-300"
                />
                <div className="mt-2 flex flex-col items-center">
                    <span className="font-bold text-xl">{displayName(data)}</span>
                    <span className="text-sm text-slate-600">{data.email}</span>
                </div>
            </div>
            <ul className="mt-4">
                {profile.map((item, i) => (
                    <Item
                        key={i}
                        item={item}
                        visible={data.tab == i.toString()}
                        index={i.toString()}
                    />
                ))}
            </ul>
        </div>
    );
};

export default Sidebar;
