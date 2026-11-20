// 이 파일에는 URL → File 변환만 남는다.
//
// 예전에는 Cloudinary 업로드(`uploadImages`)와 그 전용 axios 인스턴스가 여기 있었다.
// 이미지 업로드는 **catalog-service 가 S3 로 처리한다**(ImageService·AmazonS3Config,
// MultipartFile 엔드포인트). 프론트엔드에서 외부 스토리지로 직접 올리는 경로는 남아
// 있지 않았고 — CreateProduct 의 호출부는 통째로 주석 처리되어 있었으며 Review·loadFake
// 는 import 만 하고 부르지 않았다 — REACT_APP_CLOUDINARY_* 를 읽는 코드가 남아 있으면
// 배포에 그 계정이 필요한 것처럼 보인다. 그래서 지웠다.
// [JA] このファイルには URL → File 変換だけが残る。
//
//      以前は Cloudinary アップロード(`uploadImages`)とその専用 axios インスタンスがここにあった。
//      画像アップロードは **catalog-service が S3 で処理する**(ImageService・AmazonS3Config、
//      MultipartFile エンドポイント)。フロントエンドから外部ストレージへ直接上げる経路は残って
//      おらず — CreateProduct の呼び出し部はまるごとコメントアウトされ、Review・loadFake
//      は import するだけで呼んでいなかった — REACT_APP_CLOUDINARY_* を読むコードが残っていると
//      デプロイにそのアカウントが必要なように見える。だから削除した。

export const convertURLtoFile = async (url) => {
    const response = await fetch(url);
    const data = await response.blob();
    const ext = url.split(".").pop(); // url 구조에 맞게 수정할 것 ／ [JA] url の構造に合わせて修正すること
    const filename = url.split("/").pop(); // url 구조에 맞게 수정할 것 ／ [JA] url の構造に合わせて修正すること
    const metadata = { type: `image/${ext}` };
    return new File([data], filename, metadata);
  };

/**
 * 예전에는 `new Promise(async (resolve, reject) => { ... })` 로 감싸고 있었다. 이 패턴은
 * async 함수가 던진 오류를 <b>아무도 못 받는다</b> — reject 를 부르는 곳이 없어서 프라미스가
 * 영원히 대기 상태로 남고, 호출부의 Promise.all 도 같이 멈춘다(unhandled rejection 만 콘솔에
 * 찍힌다). 이미 프라미스를 돌려주는 함수를 프라미스로 또 감쌀 이유가 없다.
 *
 * <p>[JA] 以前は `new Promise(async (resolve, reject) => { ... })` で包んでいた。このパターンは
 * async 関数が投げたエラーを<b>誰も受け取れない</b> — reject を呼ぶ箇所がないのでプロミスが
 * 永遠に待機状態のまま残り、呼び出し側の Promise.all も一緒に止まる(unhandled rejection がコンソールに
 * 出るだけ)。すでにプロミスを返す関数をさらにプロミスで包む理由はない。
 */
export const fetchImageFromURL = (url) => convertURLtoFile(url);
