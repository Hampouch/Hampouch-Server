package Hampouch.server.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 presigned URL 발급 및 업로드 확인(HeadObject)을 위한 클라이언트 설정.
 * 실제 업로드/다운로드는 프론트에서 presigned URL로 S3에 직접 수행하고, 서버는 URL 발급과
 * (필요 시) 업로드 완료 확인만 담당한다.
 */
@Configuration
public class S3Config {

    @Value("${aws.s3.region}")
    private String region;

    @Value("${aws.credentials.access-key}")
    private String accessKey;

    @Value("${aws.credentials.secret-key}")
    private String secretKey;

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider())
                .build();
    }

    /**
     * HeadObject로 presigned URL 발급 이후 실제로 업로드가 됐는지 확인하기 위한 클라이언트, ExpenseImageService에서 활용.
     * S3Presigner와 별개 빈으로 둔 이유: Presigner는 URL 서명만 하고 실제 네트워크 요청을 보내지 않는 반면,
     * S3Client는 실제 HTTP 호출(HeadObject 등)을 수행하는 별도 클라이언트라 SDK 레벨에서 타입이 다르다.
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider())
                .build();
    }

    private StaticCredentialsProvider credentialsProvider() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
}
