package com.project.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 업로드 파일의 확장자 검사와 S3 키 조립.
 *
 * <p>이 클래스는 <b>업로드를 거르는 유일한 관문</b>이다. 그런데 프로필 이미지 업로드가
 * 생기기 전까지 {@code getFilePath} 를 부르는 코드가 한 곳도 없었고, 그래서 아래 두 가지
 * 결함이 드러나지 않았다.
 */
class FileUtilTest {

    private static MultipartFile file(String name) {
        return new MockMultipartFile("image", name, "image/png", new byte[] { 1, 2, 3 });
    }

    // ── 확장자 허용 목록 ────────────────────────────────────────────────────

    /**
     * 🐛 {@code FileType.GIF} 의 값이 {@code "git"} 이었다 — 오타다. 두 방향으로 틀렸다:
     * {@code .gif} 이미지는 거부되고, {@code .git} 으로 끝나는 파일은 이미지로 통과했다.
     */
    @Test
    @DisplayName("gif 를 받아들인다 — 예전에는 오타 때문에 거부했다")
    void acceptsGif() {
        assertThat(FileUtil.getFilePath(file("a.gif"), "key", "images"))
                .isEqualTo("images/key.gif");
    }

    @Test
    @DisplayName("'.git' 은 이미지가 아니다 — 예전에는 통과했다")
    void rejectsGit() {
        assertThatThrownBy(() -> FileUtil.getFilePath(file("repo.git"), "key", "images"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("png · jpg · jpeg · webp 를 받아들인다")
    void acceptsCommonImageTypes() {
        for (String ext : new String[] { "png", "jpg", "jpeg", "webp" }) {
            assertThat(FileUtil.getFilePath(file("a." + ext), "key", "images"))
                    .isEqualTo("images/key." + ext);
        }
    }

    @Test
    @DisplayName("대문자 확장자도 받아들이고 소문자로 저장한다 — 카메라가 .JPG 로 준다")
    void normalizesCase() {
        assertThat(FileUtil.getFilePath(file("PHOTO.JPG"), "key", "images"))
                .isEqualTo("images/key.jpg");
    }

    @Test
    @DisplayName("실행 파일·문서는 거부한다")
    void rejectsNonImages() {
        for (String name : new String[] { "x.exe", "x.svg", "x.html", "x.pdf", "x.sh" }) {
            assertThatThrownBy(() -> FileUtil.getFilePath(file(name), "key", "images"))
                    .as(name)
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    @DisplayName("확장자가 없으면 거부한다")
    void rejectsMissingExtension() {
        assertThatThrownBy(() -> FileUtil.getFilePath(file("noextension"), "key", "images"))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * 예전에는 {@code Objects.requireNonNull(file.getOriginalFilename())} 이었다.
     * 파일명 없는 업로드는 드물지만 가능하고, 그때 NPE 가 500 으로 나갔다 — 잘못된
     * 요청은 400 이다.
     */
    @Test
    @DisplayName("파일명이 없어도 NPE 가 아니라 '지원하지 않는 형식'이다")
    void nullFilenameIsRejectedNotCrash() {
        MultipartFile noName = new MockMultipartFile("image", null, "image/png", new byte[] { 1 });

        assertThatThrownBy(() -> FileUtil.getFilePath(noName, "key", "images"))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(NullPointerException.class);
    }

    // ── 키 접두사 ───────────────────────────────────────────────────────────

    /**
     * 🔑 버킷의 공개 읽기 정책이 {@code arn:aws:s3:::<버킷>/images/*} 에 걸려 있다.
     * 접두사가 어긋나면 <b>업로드는 성공하는데 브라우저에서 403</b> 이 된다 — 가장
     * 알아채기 어려운 실패 방식이다.
     */
    @Test
    @DisplayName("접두사를 지정하면 그것을 쓴다")
    void usesGivenPrefix() {
        assertThat(FileUtil.getFilePath(file("a.png"), "profile/abc", "images"))
                .isEqualTo("images/profile/abc.png");
    }

    @Test
    @DisplayName("접두사 끝의 슬래시가 겹치지 않는다")
    void doesNotDoubleSlash() {
        assertThat(FileUtil.getFilePath(file("a.png"), "abc", "images/"))
                .isEqualTo("images/abc.png");
    }

    @Test
    @DisplayName("접두사가 비면 붙이지 않는다")
    void emptyPrefixMeansNoPrefix() {
        assertThat(FileUtil.getFilePath(file("a.png"), "abc", ""))
                .isEqualTo("abc.png");
        assertThat(FileUtil.getFilePath(file("a.png"), "abc", null))
                .isEqualTo("abc.png");
    }

    @Test
    @DisplayName("기본 접두사는 그대로 'image' 다 — 기존 동작을 바꾸지 않는다")
    void defaultPrefixUnchanged() {
        assertThat(FileUtil.getFilePath(file("a.png"), "abc"))
                .isEqualTo("image/abc.png");
    }

    @Test
    @DisplayName("무작위 파일명은 매번 다르고 경로 구분자를 담지 않는다")
    void randomFilenameIsSafe() {
        String a = FileUtil.getRandomFilename();
        String b = FileUtil.getRandomFilename();

        assertThat(a).isNotEqualTo(b);
        // 사용자 입력이 아니지만, 키에 들어가는 값이 경로를 만들지 않는다는 것을 고정한다.
        assertThat(a).doesNotContain("/").doesNotContain("..");
    }
}
