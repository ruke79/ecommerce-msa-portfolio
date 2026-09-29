package com.project.common.constants;

public enum FileType {

    PNG("png"),
    JPEG("jpeg"),
    JPG("jpg"),
    /**
     * 🐛 예전 값이 {@code "git"} 이었다 — 오타다.
     *
     * <p>결과가 두 방향으로 틀렸다: {@code .gif} 이미지는 <b>허용 목록에 없어 거부</b>되고,
     * {@code .git} 으로 끝나는 파일은 <b>이미지로 통과</b>했다. 확장자 검사는 업로드를
     * 거르는 유일한 관문이라 두 번째가 특히 나쁘다.
     *
     * <p>지금까지 드러나지 않은 이유는 이 목록을 쓰는 {@code FileUtil.getFilePath} 를
     * 부르는 코드가 한 곳도 없었기 때문이다(프로필 이미지 업로드가 첫 사용처다).
     */
    GIF("gif"),
    WEBP("webp");

    private final String extension;
    
    FileType(String extension) {
        this.extension = extension;
    }

    public String getExtension() {
        return extension;
    }
}