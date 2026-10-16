package com.project.catalog_service.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;

import com.project.catalog_service.constants.AwsS3Properties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    private final AwsS3Properties properties;
    private final AmazonS3 client;

    /**
     * 업로드 요청을 <b>한 곳에서</b> 만든다.
     *
     * <p>⚠️ 이 메서드가 존재하는 이유는 사고 하나다. 실제 업로드는
     * {@code .withCannedAcl(PublicRead)} 를 붙였는데 {@link #probe()} 는 붙이지 않았다.
     * 두 호출이 <b>서로 다른 권한</b>을 요구하게 된 것이다:
     *
     * <ul>
     *   <li>probe → {@code s3:PutObject} 만 필요</li>
     *   <li>실제 업로드 → {@code s3:PutObject} <b>와</b> {@code s3:PutObjectAcl}</li>
     *   </ul>
     *
     * <p>IAM 정책에 PutObjectAcl 이 없으면 probe 는 통과하고 등록만 죽는다. 실제로
     * 저장소 배너가 초록불인 상태에서 일괄 임포트가 1초 만에 실패했다:
     *
     * <pre>
     *   User: arn:aws:iam::…:user/webdev is not authorized to perform: s3:PutObjectAcl
     *   … because no identity-based policy allows the s3:PutObjectAcl action (403)
     * </pre>
     *
     * <p>점검의 존재 이유가 "등록이 실패할지 미리 알려 주는 것" 인데, 등록과 다른 요청을
     * 보내면 그 초록불은 아무것도 보장하지 않는다. 그래서 <b>요청 모양을 한 곳에서만</b>
     * 정한다 — 바꾸면 점검과 업로드가 함께 바뀐다.
     *
     * <h2>왜 ACL 을 달지 않는가</h2>
     *
     * <p>예전에는 여기서 {@code .withCannedAcl(PublicRead)} 로 <b>객체마다 공개 ACL</b> 을
     * 달았다. 공개 읽기는 이제 <b>버킷 정책</b>이 맡는다:
     *
     * <pre>
     *   { "Effect": "Allow", "Principal": "*",
     *     "Action": "s3:GetObject",
     *     "Resource": "arn:aws:s3:::&lt;버킷&gt;/images/*" }
     * </pre>
     *
     * <p>이유 셋:
     *
     * <ul>
     *   <li><b>필요 권한이 줄어든다.</b> {@code s3:PutObjectAcl} 이 더는 필요 없다.
     *       업로드에 필요한 것은 {@code s3:PutObject} 하나다.</li>
     *   <li><b>ACL 이 꺼진 버킷에서도 돈다.</b> AWS 는 신규 버킷의 기본값을 Object
     *       Ownership = Bucket owner enforced 로 바꿨고, 그 설정에서는 ACL 자체가
     *       동작하지 않는다({@code AccessControlListNotSupported}).</li>
     *   <li><b>계정의 Block Public Access 와 싸우지 않는다.</b> 그 설정은 공개 ACL 을
     *       막지만, 버킷 정책으로 여는 경로는 따로 제어된다.</li>
     * </ul>
     *
     * <p>⚠️ 그래서 <b>버킷 정책이 없으면 이미지가 브라우저에서 403 이 된다.</b> 업로드는
     * 성공하는데 화면에서만 깨지므로, 새 버킷을 쓸 때는 위 정책을 먼저 넣을 것.
     */
    private PutObjectRequest putRequest(String bucket, String key, InputStream stream,
            ObjectMetadata metadata) {
        return new PutObjectRequest(bucket, key, stream, metadata);
    }

    public String upload(String path) throws IOException {

        // ClassPathResource를 사용하여 resources 폴더 내의 파일을 가져옵니다.
        ClassPathResource resource = new ClassPathResource(path);

        // 파일이 존재하지 않는 경우 예외 처리
        if (!resource.exists()) {
            throw new IllegalArgumentException("File not found in classpath: " + path);
        }

        String bucket = properties.getBucket();

        // S3에 업로드할 파일의 InputStream을 얻습니다.
        // 길이는 리소스가 안다 — 힙에 복사하지 않고 스트림을 그대로 넘긴다(putObjectToS3Storage 와 같은 이유).
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(resource.contentLength());

        // S3에 업로드할 때 전체 경로(path)를 키(key)로 사용합니다.
        try (InputStream stream = resource.getInputStream()) {
            client.putObject(putRequest(bucket, path, stream, metadata));
        }

        return client.getUrl(bucket, path).toString();

    }

    public String upload(MultipartFile file, String filename) throws IOException {

        return putObjectToS3Storage(client, filename, file);
    }

    /**
     * 업로드 파일을 S3 로 <b>흘려보낸다</b> — 힙에 통째로 올리지 않는다.
     *
     * <p>예전에는 {@code IOUtils.toByteArray(file.getInputStream())} 로 파일 전체를 byte[] 에
     * 담았다. 이유는 하나, S3 SDK 가 요구하는 Content-Length 를 알기 위해서였다. 그런데 그 값은
     * {@link MultipartFile#getSize()} 가 이미 알고 있다 — 톰캣이 요청을 받으면서 임시 파일에
     * 써 두었기 때문이다. 그래서 스트림을 그대로 넘기면 파일이 힙을 거치지 않는다.
     * 상품 등록은 이미지를 여러 장 한 번에 올리므로(색상별), 그만큼의 byte[] 가 요청 하나에
     * 동시에 살아 있던 것이 사라진다.
     */
    private String putObjectToS3Storage(AmazonS3 client, String filepath, MultipartFile file) throws IOException {
        String bucket = properties.getBucket();

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        // 🔒 Content-Type 은 파일 내용으로 서버가 정한다 — 클라이언트 값(text/html 등)을 믿지 않는다. 이미지가 아니면 거절(ImageUploads).
        // [JA] Content-Type はファイルの内容でサーバーが決める — クライアントの値を信じない。画像でなければ拒否。
        metadata.setContentType(com.project.common.util.ImageUploads.contentTypeOf(file));

        try (InputStream stream = file.getInputStream()) {
            client.putObject(putRequest(bucket, filepath, stream, metadata));
        }

        return client.getUrl(bucket, filepath).toString();
    }

    /**
     * 업로드 보상용 삭제. 상품 등록이 이미지 업로드 **뒤에** 실패하면 S3 에 아무도
     * 참조하지 않는 파일이 남는다 — 눈에 안 보이고, 지워지지 않고, 요금만 나간다.
     * 등록 실패 경로에서 방금 올린 것들을 되돌리는 데 쓴다.
     *
     * <p>URL 을 받는 이유: upload() 가 URL 을 돌려주므로 호출자는 URL 만 들고 있다.
     * 키는 URL 경로에서 되찾는다. 삭제 실패는 삼키고 로그만 남긴다 — 보상 중의 실패로
     * 원래 예외(사용자에게 알려야 할 진짜 원인)를 덮으면 안 된다.
     */
    public void deleteByUrl(String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            // https://bucket.s3.region.amazonaws.com/key 형태면 경로가 /key,
            // https://s3.region.amazonaws.com/bucket/key 형태면 /bucket/key 다.
            String key = path.startsWith("/") ? path.substring(1) : path;
            String bucket = properties.getBucket();
            if (key.startsWith(bucket + "/")) {
                key = key.substring(bucket.length() + 1);
            }
            client.deleteObject(bucket, key);
        } catch (Exception e) {
            log.warn("보상 삭제 실패 — S3 에 고아 이미지가 남습니다: {}", url, e);
        }
    }

    /**
     * CSV 가 가리키는 클래스패스 이미지를 올린다. <b>실패하면 던진다.</b>
     *
     * <p>예전에는 임포트 프로세서마다 이 호출을 {@code try { ... } catch (Exception e) {
     * e.printStackTrace(); return null; }} 로 감싸고 null 을 걸러 냈다. 그래서 자격 증명이
     * 죽어 있어도 잡은 <b>성공으로 끝났고</b>, 이미지가 하나도 없는 상품이 DB 에 남았다.
     * 증상은 등록 화면이 아니라 첫 화면에서 나타난다 — 이미지 배열이 비면 카드가 깨지고,
     * 예전 프론트는 거기서 TypeError 를 내 홈 전체를 백지로 만들었다.
     *
     * <p>지금은 던진다. 임포트는 스텝에서 실패하고, 원인이 잡 실행 이력에 남는다.
     * "절반만 들어간 데이터"보다 "실패했다고 말하는 잡"이 낫다.
     *
     * @param csvPath CSV 에 적힌 경로. 윈도우에서 만든 파일이라 {@code images\food\a.jpg}
     *                처럼 역슬래시가 섞여 있어 여기서 정규화한다.
     */
    public String uploadClasspathImage(String csvPath) {
        String cleaned = csvPath == null ? "" : csvPath.trim().replace("\\", "/");
        if (cleaned.isEmpty()) {
            throw new ImageUploadException("이미지 경로가 비어 있습니다.", null);
        }
        try {
            return upload(cleaned);
        } catch (Exception e) {
            throw new ImageUploadException(
                    "이미지 업로드 실패: " + cleaned + " — " + e.getMessage(), e);
        }
    }

    /**
     * 저장소가 <b>쓰기 가능한지</b> 확인한다.
     *
     * <p>읽기만 확인하면 의미가 없다. 상품 등록이 필요로 하는 것은 put 권한이고,
     * 키를 폐기했거나 버킷 정책이 바뀌면 정확히 그 권한만 사라진다. 그래서 작은 객체를
     * 실제로 쓰고 지운다.
     *
     * <p>등록 화면과 일괄 임포트가 시작 <b>전에</b> 이걸 부른다. 그러지 않으면 관리자는
     * 긴 폼을 다 채우고 이미지를 올린 뒤에야 500 을 보고, 로그를 볼 수 없으면 원인을
     * 알 방법이 없다.
     */
    public StorageStatus probe() {
        String bucket = properties.getBucket();
        String key = "health/probe-" + System.currentTimeMillis() + ".txt";
        byte[] payload = "ok".getBytes(StandardCharsets.UTF_8);

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(payload.length);
        metadata.setContentType("text/plain");

        try {
            // ⚠️ 반드시 putRequest 를 거친다 — 실제 업로드와 **같은 요청 모양**이어야
            //    점검이 의미를 가진다(위 putRequest 주석의 사고 참조).
            client.putObject(putRequest(bucket, key,
                    new ByteArrayInputStream(payload), metadata));
        } catch (Exception e) {
            // 자격 증명·버킷 이름·리전·정책 중 무엇이 틀렸는지는 SDK 메시지가 가장 잘 안다.
            // 그대로 올려보낸다 — 관리자 화면에만 보이고, 비밀값은 들어 있지 않다.
            log.error("이미지 저장소 쓰기 점검 실패: bucket={}", bucket, e);
            return new StorageStatus(false, bucket, e.getMessage());
        }

        try {
            client.deleteObject(bucket, key);
        } catch (Exception e) {
            // 쓰기는 됐다. 점검 파일 하나가 남는 것은 등록을 막을 이유가 아니다.
            log.warn("점검 파일 삭제 실패 — s3://{}/{} 가 남습니다.", bucket, key, e);
        }
        return new StorageStatus(true, bucket, null);
    }

    /** 저장소 점검 결과. {@code detail} 은 실패 원인이며 성공이면 null 이다. */
    public record StorageStatus(boolean writable, String bucket, String detail) {
    }

    /** 이미지 업로드 실패. 임포트 스텝을 실패시켜 원인을 이력에 남기기 위한 것. */
    public static class ImageUploadException extends RuntimeException {
        public ImageUploadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
