import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import ButtonInput from "pages/user/ButtonInput";

/**
 * 🐛 회귀 대상: `ButtonInput` 이 `...props` 를 받아 놓고 <b>button 에 펼치지 않았다.</b>
 *
 * 그래서 호출부의 `disabled={isPending}` 이 아무 효과가 없었고, 로그인·회원가입 화면에는
 * "중복 제출을 막는다"는 주석까지 달려 있었지만 실제로는 막히지 않았다. 버튼을 두 번 누르면
 * 요청이 두 번 나갔다 — 회원가입에서는 계정이, 주문에서는 주문이 두 번 만들어질 수 있다.
 *
 * 단언 한 줄이면 잡히는 버그였다는 점이 이 테스트 파일의 존재 이유다.
 */
describe("ButtonInput", () => {
  it("renders its label", () => {
    render(<ButtonInput type="submit" text="Sign in" />);
    expect(screen.getByRole("button", { name: /Sign in/ })).toBeInTheDocument();
  });

  it("actually applies the disabled prop", () => {
    render(<ButtonInput type="submit" text="Sign in" disabled />);
    // 고치기 전에는 이 단언이 실패했다 — disabled 가 DOM 에 닿지 않았다.
    expect(screen.getByRole("button", { name: /Sign in/ })).toBeDisabled();
  });

  it("does not fire onClick while disabled", async () => {
    const onClick = jest.fn();
    render(<ButtonInput type="button" text="확인" disabled onClick={onClick} />);

    await userEvent.click(screen.getByRole("button", { name: /확인/ }));

    expect(onClick).not.toHaveBeenCalled();
  });

  it("forwards onClick when enabled", async () => {
    const onClick = jest.fn();
    render(<ButtonInput type="button" text="확인" onClick={onClick} />);

    await userEvent.click(screen.getByRole("button", { name: /확인/ }));

    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("honours the requested button type", () => {
    // type 을 넘기지 못하면 폼 안의 버튼이 전부 submit 으로 동작해 의도치 않은 제출이 난다.
    render(<ButtonInput type="button" text="취소" />);
    expect(screen.getByRole("button", { name: /취소/ })).toHaveAttribute("type", "button");
  });
});
