package com.project.common.util;


import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.project.common.constants.FileType;
import com.project.common.exception.ErrorCode;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public class FileUtil {

    private static final String BASE_DIRECTORY = "image";

    public static String getRandomFilename() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String getFilePath(MultipartFile file, String filename) {
        return getFilePath(file, filename, BASE_DIRECTORY);
    }

    /**
     * 확장자를 검사하고 S3 키를 만든다.
     *
     * <p>{@code baseDirectory} 를 받는 이유: 버킷의 <b>공개 읽기 정책이 접두사에 걸려
     * 있다.</b>
     *
     * <pre>
     *   { "Effect": "Allow", "Principal": "*", "Action": "s3:GetObject",
     *     "Resource": "arn:aws:s3:::&lt;버킷&gt;/images/*" }
     * </pre>
     *
     * 그런데 이 클래스의 기본값은 {@code "image"}(단수)라, 그대로 쓰면 키가
     * {@code image/…} 가 되어 <b>업로드는 성공하는데 브라우저에서 403</b> 이 된다.
     * 일괄 임포트가 올리는 이미지는 CSV 의 경로({@code images/food/Image_2.jpg})를
     * 그대로 키로 쓰므로 정책에 맞고, 그래서 그쪽만 잘 보였다.
     *
     * <p>기본값은 바꾸지 않는다 — 다른 호출부가 생겼을 때 조용히 경로가 달라지는 것보다,
     * 접두사를 필요로 하는 쪽이 명시하는 편이 낫다.
     */
    public static String getFilePath(MultipartFile file, String filename, String baseDirectory) {
        String original = file.getOriginalFilename();
        String extension = original == null ? null : StringUtils.getFilenameExtension(original);

        // 예전에는 Objects.requireNonNull(getOriginalFilename()) 이었다. 파일명이 없는
        // 업로드는 드물지만 가능하고, 그때 NullPointerException 이 500 으로 나갔다.
        if (extension == null || !isValidFileType(extension)) {
            throw ErrorCode.throwUnSupportedFile();
        }

        String prefix = baseDirectory == null || baseDirectory.isBlank()
                ? ""
                : baseDirectory.replaceAll("/+$", "") + "/";

        return prefix + filename + "." + extension.toLowerCase();
    }

    /** 확장자 비교는 대소문자를 가리지 않는다 — 카메라가 {@code .JPG} 로 주는 경우가 흔하다. */
    private static boolean isValidFileType(String extension) {
        String normalized = extension.toLowerCase();
        return Arrays.stream(FileType.values())
                .anyMatch(type -> type.getExtension().equals(normalized));
    }

}