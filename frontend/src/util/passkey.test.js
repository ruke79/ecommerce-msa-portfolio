import {
  base64urlToBuffer,
  bufferToBase64url,
  credentialToJSON,
  isUserCancel,
  toCreationOptions,
  toRequestOptions,
} from "util/passkey";

/**
 * 패스키 변환 — 서버의 base64url ↔ 브라우저의 ArrayBuffer(user-service docs/68 §20).
 * 한 바이트라도 어긋나면 서명 검증이 실패한다. "+" "/" "=" 가 섞이는 값으로 본다.
 */
const bytes = (...values) => new Uint8Array(values).buffer;

describe("passkey 변환", () => {
  it("base64url 왕복 — 패딩 없이, URL 안전 문자로", () => {
    const raw = bytes(0xfb, 0xff, 0xbf, 0x00, 0x01);
    const encoded = bufferToBase64url(raw);
    expect(encoded).toBe("-_-_AAE");
    expect(encoded).not.toMatch(/[+/=]/);
    expect(new Uint8Array(base64urlToBuffer(encoded))).toEqual(new Uint8Array(raw));
  });

  it("등록 옵션의 challenge · user.id · excludeCredentials 를 바이트로 바꾼다", () => {
    const options = toCreationOptions({
      challenge: "AAEC",
      user: { id: "AwQF", name: "a@example.com", displayName: "A" },
      excludeCredentials: [{ type: "public-key", id: "BgcI" }],
      rp: { id: "shop.example.com", name: "Shop" },
    });
    expect(new Uint8Array(options.challenge)).toEqual(new Uint8Array([0, 1, 2]));
    expect(new Uint8Array(options.user.id)).toEqual(new Uint8Array([3, 4, 5]));
    expect(new Uint8Array(options.excludeCredentials[0].id)).toEqual(new Uint8Array([6, 7, 8]));
    expect(options.rp.id).toBe("shop.example.com");
  });

  it("로그인 옵션 — allowCredentials 가 비어 있어도 된다(기기가 계정을 고른다)", () => {
    const options = toRequestOptions({ challenge: "AAEC", rpId: "shop.example.com", allowCredentials: [] });
    expect(options.allowCredentials).toEqual([]);
    expect(new Uint8Array(options.challenge)).toEqual(new Uint8Array([0, 1, 2]));
  });

  it("등록 결과를 서버가 읽는 JSON 으로 — attestationObject · transports", () => {
    const json = credentialToJSON({
      id: "AQI", rawId: bytes(1, 2), type: "public-key",
      getClientExtensionResults: () => ({}),
      response: { clientDataJSON: bytes(9), attestationObject: bytes(7), getTransports: () => ["internal"] },
    });
    expect(json).toMatchObject({
      id: "AQI", rawId: "AQI", type: "public-key",
      response: { clientDataJSON: "CQ", attestationObject: "Bw", transports: ["internal"] },
    });
    expect(json.response.signature).toBeUndefined();
  });

  it("로그인 결과 — authenticatorData · signature · userHandle", () => {
    const json = credentialToJSON({
      id: "AQI", rawId: bytes(1, 2), type: "public-key",
      response: { clientDataJSON: bytes(9), authenticatorData: bytes(5), signature: bytes(6), userHandle: bytes(4) },
    });
    expect(json.response).toEqual({ clientDataJSON: "CQ", authenticatorData: "BQ", signature: "Bg", userHandle: "BA" });
    expect(json.clientExtensionResults).toEqual({});
  });

  it("기기 창을 닫은 것은 취소다 — 오류로 보여 주지 않는다", () => {
    expect(isUserCancel({ name: "NotAllowedError" })).toBe(true);
    expect(isUserCancel({ name: "AbortError" })).toBe(true);
    expect(isUserCancel({ name: "SecurityError" })).toBe(false);
    expect(isUserCancel(undefined)).toBe(false);
  });
});
