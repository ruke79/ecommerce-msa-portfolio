package com.project.userservice.service;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.project.userservice.constants.AwsS3Properties;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ImageService {

    private final AwsS3Properties properties;
    private final AmazonS3 client;

    public String upload(MultipartFile file, String filename) throws IOException {
        return putObjectToS3Storage(client, filename, file);
    }

    /**
     * 업로드 파일을 S3 로 <b>흘려보낸다</b> — 힙에 통째로 올리지 않는다.
     *
     * <p>예전에는 {@code IOUtils.toByteArray(file.getInputStream())} 로 파일 전체를 byte[] 에
     * 담았다. S3 SDK 가 요구하는 Content-Length 를 알기 위해서였는데, 그 값은
     * {@link MultipartFile#getSize()} 가 이미 알고 있다(톰캣이 받으면서 임시 파일에 써 둔다).
     * 스트림을 그대로 넘기면 프로필 이미지가 힙을 거치지 않는다. catalog-service 의
     * ImageService 와 같은 수정이다.
     */
    private String putObjectToS3Storage(AmazonS3 client, String filepath, MultipartFile file) throws IOException {
        String bucket = properties.getBucket();

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        if (file.getContentType() != null) {
            metadata.setContentType(file.getContentType());
        }

        try (InputStream stream = file.getInputStream()) {
            // ⚠️ 공개 ACL 을 달지 않는다. 공개 읽기는 **버킷 정책**이 맡는다
            //    (arn:aws:s3:::<버킷>/images/* 에 s3:GetObject 허용).
            //
            //    예전에는 여기서 .withCannedAcl(PublicRead) 를 달았고, 그래서 업로드에
            //    s3:PutObject 말고 **s3:PutObjectAcl** 까지 필요했다. catalog-service 의
            //    일괄 임포트가 정확히 그 권한이 없어 403 으로 죽었다:
            //
            //        User: arn:aws:iam::…:user/webdev is not authorized to perform:
            //        s3:PutObjectAcl … (403 AccessDenied)
            //
            //    게다가 AWS 신규 버킷 기본값(Object Ownership = Bucket owner enforced)에서는
            //    ACL 자체가 동작하지 않는다. catalog-service 의 ImageService.putRequest 주석 참조 —
            //    두 서비스가 같은 버킷을 쓰므로 규칙이 갈리면 한쪽만 조용히 깨진다.
            client.putObject(new PutObjectRequest(bucket, filepath, stream, metadata));
        }

        return client.getUrl(bucket, filepath).toString();
    }

}
