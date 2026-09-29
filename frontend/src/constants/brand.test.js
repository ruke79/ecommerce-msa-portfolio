const fs = require("fs");
const path = require("path");

/**
 * 🔒 남의 상표가 다시 들어오지 못하게 막는다.
 *
 * <p>이 프로젝트는 아마존 클론 템플릿에서 출발했고, 브랜드 문자열과 로고가 교체되지 않은
 * 채 <b>공개 도메인에 배포돼 있었다</b>. 로그인·가입·비밀번호 찾기 화면에 Amazon 로고가
 * 걸려 있었고, 가입 버튼 아래에는 "By continuing, you agree to <b>Amazon's</b> Conditions
 * of Use and Privacy Notice." 라고 적혀 있었다 — 사실이 아닌 약관 고지다.
 *
 * <p>한 번 지우는 것으로는 부족하다. 템플릿에서 코드를 더 가져오거나, 되돌리는 커밋
 * 하나로 조용히 되살아난다. 실제로 배포될 코드에 그 문자열이 없다는 것을 고정한다.
 *
 * <p>두 가지는 막지 않는다.
 *
 * <ul>
 *   <li><b>Tailwind 색상 토큰</b>({@code amazon-blue_dark} 등) — 화면에 보이는 글자가
 *       아니라 CSS 클래스 이름이다. 여기까지 바꾸면 스타일 전면 교체가 되는데, 지금
 *       막으려는 것(사용자에게 남의 상호로 <b>보이는</b> 것)과는 다른 문제다.</li>
 *   <li><b>{@code AMAZON_ACCESS_KEY} 같은 환경변수 이름</b> — 이 시스템은 실제로 AWS S3
 *       를 쓰고, 저장소 배너가 관리자에게 "이 키를 확인하라"고 알려 준다. 진짜 설정
 *       키를 가리키는 것이지 남의 브랜드를 사칭하는 것이 아니다. 오히려 이름을 바꾸면
 *       안내가 틀린 말이 된다.</li>
 * </ul>
 */

const SRC = path.join(__dirname, "..");

// 색상 토큰과 AWS 환경변수 이름(대문자 + 밑줄)은 제외한다.
const ALLOWED_TOKEN = /amazon[-_](blue|orange)[a-z_]*|AMAZON_[A-Z_]+/g;
const EXEMPT_FILES = new Set([
  path.join(SRC, "constants", "brand.js"),
  path.join(SRC, "constants", "brand.test.js"),
  path.join(SRC, "components", "common", "BrandMark.jsx"),
]);

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "node_modules") continue;
      walk(full, out);
    } else if (/\.(jsx?|html|css)$/.test(entry.name)) {
      out.push(full);
    }
  }
  return out;
}

/** 주석을 걷어낸다 — "예전에는 Amazon 로고였다" 는 설명은 화면에 나가지 않는다. */
function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "")
    .replace(/\{\s*\/\*[\s\S]*?\*\/\s*\}/g, "");
}

describe("남의 상표가 코드에 남아 있지 않다", () => {
  const files = walk(SRC).filter((f) => !EXEMPT_FILES.has(f) && !/\.test\.jsx?$/.test(f));

  it("훑을 파일이 실제로 있다 — 경로가 틀려 0건이면 통과해 버린다", () => {
    expect(files.length).toBeGreaterThan(50);
  });

  it("Amazon 이라는 글자가 코드에 없다 (색상 토큰 제외)", () => {
    const offenders = [];

    for (const file of files) {
      const body = stripComments(fs.readFileSync(file, "utf8")).replace(ALLOWED_TOKEN, "");
      if (/amazon/i.test(body)) {
        const line = body.split("\n").findIndex((l) => /amazon/i.test(l)) + 1;
        offenders.push(`${path.relative(SRC, file)}:${line}`);
      }
    }

    expect(offenders).toEqual([]);
  });

  it("로고 이미지 파일이 없다 — public/ 은 운영에서 그대로 서빙된다", () => {
    // webpack.config.prod 의 CopyPlugin 이 public/ 을 루트로 복사하므로, 코드가
    // 참조하지 않아도 파일이 남아 있으면 /assets/images/amazon-dark.png 로 공개된다.
    for (const dir of [path.join(SRC, "assets", "images"),
                       path.join(SRC, "..", "public", "assets", "images")]) {
      if (!fs.existsSync(dir)) continue;
      const found = fs.readdirSync(dir).filter((f) => /amazon/i.test(f));
      expect(found).toEqual([]);
    }
  });

  it("사이트 이름이 한 곳에 정의돼 있다", () => {
    const { BRAND_NAME } = require("constants/brand");
    expect(typeof BRAND_NAME).toBe("string");
    expect(BRAND_NAME.trim()).not.toBe("");
    expect(BRAND_NAME).not.toMatch(/amazon/i);
  });
});
