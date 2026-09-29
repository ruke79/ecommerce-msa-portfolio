import { useState } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Provider } from "react-redux";
import { configureStore } from "@reduxjs/toolkit";

import CreateProduct from "components/admin/CreateProduct";
import dialogReducer from "redux/DialogSlice";
import { postRequest } from "util/api";
import { withI18n } from "i18n/testing";

jest.mock("util/api", () => ({
  __esModule: true,
  postRequest: jest.fn(),
}));

// 이 화면이 고장 나 있던 방식이 특이했다. 예외도 없고 요청도 없고 화면도 안 바뀌었다 —
// setLoading(true) 뒤의 블록이 통째로 주석 처리되어 있어서 **버튼이 '불러오는 중…' 에서
// 영원히 멈췄다.** 그런 고장은 "아무 일도 안 일어난다"로 보여서 눈치채기 어렵다.
// 그래서 이 테스트는 화면 렌더링이 아니라 **실제로 나가는 요청**을 검사한다.

const PNG = "data:image/png;base64,aGVsbG8=";   // 'hello'
const JPG = "data:image/jpeg;base64,d29ybGQ=";  // 'world'

const baseProduct = {
  name: "테스트 상품 이름입니다",
  description: "설명",
  brand: "브랜드",
  sku: "SKU-1",
  discount: 0,
  images: [],
  parent: "",
  category: "1",
  subCategories: ["2"],
  shippingFee: "0",
  color: { color: "#fff", image: JPG },
  sizes: [{ size: "M", quantity: "1", price: "1000" }],
  details: [{ name: "소재", value: "면" }],
  questions: [{ question: "q", answer: "a" }],
};

function Harness({ product = baseProduct, images = [PNG, PNG, PNG] }) {
  const [loading, setLoading] = useState(false);
  const store = configureStore({ reducer: { dialog: dialogReducer } });
  return (
    <Provider store={store}>
      <CreateProduct
        parents={[]}
        product={product}
        setProduct={jest.fn()}
        categories={[]}
        subs={[]}
        images={images}
        setImages={jest.fn()}
        setColorImage={jest.fn()}
        colorImage=""
        setLoading={setLoading}
        loading={loading}
        initialProduct={baseProduct}
      />
    </Provider>
  );
}

// setProduct 호출을 밖에서 관찰해야 하는 테스트용 — 배너 클릭이 parent 를 채우는지.
function HarnessWithSetter({ setProduct }) {
  const [loading, setLoading] = useState(false);
  const store = configureStore({ reducer: { dialog: dialogReducer } });
  return (
    <Provider store={store}>
      <CreateProduct
        parents={[]}
        product={baseProduct}
        setProduct={setProduct}
        categories={[]}
        subs={[]}
        images={[PNG, PNG, PNG]}
        setImages={jest.fn()}
        setColorImage={jest.fn()}
        colorImage=""
        setLoading={setLoading}
        loading={loading}
        initialProduct={baseProduct}
      />
    </Provider>
  );
}

// jsdom 의 Blob 에는 .text() 가 없다. FileReader 로 읽는다.
const readBlob = (blob) =>
  new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result);
    r.onerror = reject;
    r.readAsText(blob);
  });

const submit = async () => {
  await userEvent.click(screen.getByRole("button", { name: "상품 등록" }));
};

describe("CreateProduct 제출", () => {
  beforeEach(() => jest.clearAllMocks());

  it("catalog-service 로 multipart 요청을 보낸다", async () => {
    postRequest.mockResolvedValue({ data: { sku: "SKU-1" } });
    render(withI18n(<Harness />));
    await submit();

    await waitFor(() => expect(postRequest).toHaveBeenCalledTimes(1));
    const [url, body, contentType] = postRequest.mock.calls[0];

    expect(url).toBe("/catalog-service/api/product");
    expect(body).toBeInstanceOf(FormData);

    // ⚠️ 이 인자가 핵심이다. api 인스턴스의 기본 Content-Type 이 application/json 인데,
    //    axios 1.x 는 Content-Type 이 JSON 이면 FormData 를 JSON 으로 바꿔 버린다.
    //    Blob 은 {} 가 되어 **빈 본문이 200 으로 성공한다.** 그 사고를 막는 검사다.
    expect(contentType).toBe("multipart/form-data");
  });

  it("서버 계약대로 product/images/colorImage 파트를 담는다", async () => {
    postRequest.mockResolvedValue({ data: { sku: "SKU-1" } });
    render(withI18n(<Harness />));
    await submit();
    await waitFor(() => expect(postRequest).toHaveBeenCalled());

    const body = postRequest.mock.calls[0][1];
    expect(body.getAll("images")).toHaveLength(3);
    expect(body.get("colorImage")).toBeTruthy();
    expect(body.get("product")).toBeTruthy();

    // 파일명이 없으면 브라우저가 "blob" 으로 보낸다 — 확장자로 타입을 정하는 경로가 깨진다.
    expect(body.getAll("images")[0].name).toMatch(/^image-1\.png$/);
    expect(body.get("colorImage").name).toMatch(/^color\.jpg$/);
  });

  it("base64 색상 이미지를 JSON 파트에 중복으로 싣지 않는다", async () => {
    postRequest.mockResolvedValue({ data: { sku: "SKU-1" } });
    render(withI18n(<Harness />));
    await submit();
    await waitFor(() => expect(postRequest).toHaveBeenCalled());

    const json = JSON.parse(await readBlob(postRequest.mock.calls[0][1].get("product")));
    // 같은 이미지가 파일 파트로 이미 가고 있다. JSON 에도 실으면 본문이 두 배가 되고
    // Cloudflare 무료 플랜의 100MB 제한에 훨씬 빨리 걸린다(엣지에서 잘려 로그가 없다).
    expect(json.color.image).toBeUndefined();
    expect(json.images).toEqual([]);
    expect(json.slug).toBeTruthy();
  });

  it("실패하면 버튼이 풀린다 — '불러오는 중…' 에서 멈추지 않는다", async () => {
    postRequest.mockRejectedValue({ response: { status: 500, data: { message: "boom" } } });
    render(withI18n(<Harness />));
    await submit();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "상품 등록" })).toBeEnabled()
    );
  });

  it("검증에 걸리면 요청을 보내지 않는다", async () => {
    render(withI18n(<Harness images={[PNG]} />));   // 3장 미만
    await submit();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "상품 등록" })).toBeEnabled()
    );
    expect(postRequest).not.toHaveBeenCalled();
  });

  it("SKU 는 비워도 제출된다 — 서버가 <카테고리 접두사>-<순번> 으로 생성한다", async () => {
    postRequest.mockResolvedValue({ data: { sku: "FAS-100042" } });
    render(withI18n(<Harness product={{ ...baseProduct, sku: "" }} />));
    await submit();

    await waitFor(() => expect(postRequest).toHaveBeenCalledTimes(1));
    const json = JSON.parse(await readBlob(postRequest.mock.calls[0][1].get("product")));
    expect(json.sku).toBe("");
  });

  it("성공 응답의 productId 로 변형 연속 등록 배너가 뜨고, 클릭하면 parent 가 채워진다", async () => {
    postRequest.mockResolvedValue({ data: { sku: "FAS-100001", productId: 77, slug: "s" } });
    const setProduct = jest.fn();
    const { rerender } = render(withI18n(<HarnessWithSetter setProduct={setProduct} />));

    await submit();
    await waitFor(() => expect(postRequest).toHaveBeenCalled());

    const btn = await screen.findByTestId("continue-variant");
    await userEvent.click(btn);

    // 예전에는 응답에 productId 가 없어(엔티티의 @JsonBackReference 에 잘림)
    // 이 흐름 자체를 만들 수 없었다 — 부모를 셀렉트에서 손으로 찾아야 했다.
    expect(setProduct).toHaveBeenCalledWith(
      expect.objectContaining({ parent: "77" })
    );
  });

  it("변형 추가(parent 지정)에서는 배송비 없이도 제출된다 — 배송비는 부모 소유다", async () => {
    postRequest.mockResolvedValue({ data: { sku: "FAS-100002" } });
    render(withI18n(<Harness product={{ ...baseProduct, parent: "77", shippingFee: "" }} />));
    await submit();

    await waitFor(() => expect(postRequest).toHaveBeenCalledTimes(1));
  });

  it("shippingFee 가 비어 있으면 보내지 않는다 — 서버의 BigDecimal 이 500 을 낸다", async () => {
    render(withI18n(<Harness product={{ ...baseProduct, shippingFee: "" }} />));
    await submit();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "상품 등록" })).toBeEnabled()
    );
    expect(postRequest).not.toHaveBeenCalled();
  });
});
