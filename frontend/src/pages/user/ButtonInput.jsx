import { ChevronRightIcon } from "@heroicons/react/24/solid";

/**
 * 폼 제출 버튼.
 *
 * <p>예전에는 `...props` 를 받아 놓고 <b>button 에 펼치지 않았다.</b> 그래서 호출부가
 * `disabled={isPending}` 을 넘겨도 아무 효과가 없었다 — 로그인·회원가입 화면에 "중복 제출을
 * 막는다"는 주석까지 달려 있었지만 실제로는 막히지 않았고, 버튼을 두 번 누르면 요청이 두 번
 * 나갔다. onClick 같은 다른 핸들러도 전부 조용히 버려졌다.
 */
const ButtonInput = ({ type, text, className = "", ...props }) => {
    return (
        <button
            type={type}
            className={`flex items-center justify-center w-full mt-4 button-orange py-[0.5rem] text-sm text-gray-900 active:from-amazon-orange active:to-yellow-200 disabled:opacity-50 disabled:cursor-not-allowed ${className}`}
            {...props}
        >
            {text}
            <ChevronRightIcon className="h-3 ml-1" />
        </button>
    );
};

export default ButtonInput;
