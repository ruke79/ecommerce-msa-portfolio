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
     *
     * <p>[JA] アップロードファイルを S3 へ<b>流す</b> — ヒープに丸ごと載せない。
     * <p>以前は IOUtils.toByteArray(file.getInputStream()) でファイル全体を byte[] に入れていた。S3 SDK が求める Content-Length を知るためだったが、その値は
     * MultipartFile#getSize() が既に知っている(Tomcat が受信しながら一時ファイルに書いておく)。ストリームをそのまま渡せばプロフィール画像がヒープを経由しない。catalog-service の ImageService と同じ修正。
     */
    private String putObjectToS3Storage(AmazonS3 client, String filepath, MultipartFile file) throws IOException {
        String bucket = properties.getBucket();

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        // 🔒 Content-Type 은 파일 내용으로 서버가 정한다 — 클라이언트 값(text/html 등)을 믿지 않는다. 이미지가 아니면 거절(ImageUploads).
        // [JA] Content-Type はファイルの内容でサーバーが決める — クライアントの値を信じない。画像でなければ拒否。
        metadata.setContentType(com.project.common.util.ImageUploads.contentTypeOf(file));

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
            // [JA] ⚠️ 公開 ACL を付けない。公開読み取りは**バケットポリシー**が担う(arn:aws:s3:::<バケット>/images/* に s3:GetObject を許可)。
            //      以前はここで .withCannedAcl(PublicRead) を付けており、アップロードに s3:PutObject だけでなく **s3:PutObjectAcl** まで必要だった。catalog-service の一括インポートがちょうどその権限がなく 403 で死んだ。
            //      さらに AWS の新規バケット既定値(Object Ownership = Bucket owner enforced)では ACL 自体が動作しない。catalog-service の ImageService.putRequest のコメント参照 —
            //      二つのサービスが同じバケットを使うので規則が分かれると片方だけ黙って壊れる。
            client.putObject(new PutObjectRequest(bucket, filepath, stream, metadata));
        }

        return client.getUrl(bucket, filepath).toString();
    }

}
