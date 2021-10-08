package com.zebrunner.mcloud.grid.s3;

import java.io.File;
import java.net.URI;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3Uploader {
    private final static String TENANT = System.getenv("S3_TENANT");
    private final static String REGION = System.getenv("S3_REGION");
    private final static String BUCKET = System.getenv("S3_BUCKET");
    private final static String ENDPOINT = System.getenv("S3_ENDPOINT");
    private final static String ACCESS_KEY = System.getenv("S3_ACCESS_KEY_ID");
    private final static String ACCESS_SECRET = System.getenv("S3_SECRET");
    
    
    private static final String S3_KEY_FORMAT = "artifacts/test-sessions/%s/%s";
    public static final String VIDEO_S3_FILENAME = "video.mp4";
    
    private final S3AsyncClient s3Client; 
    private final static S3Uploader INSTANCE = new S3Uploader();

    private S3Uploader() {
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, ACCESS_SECRET));
        SdkAsyncHttpClient httpClient = NettyNioAsyncHttpClient.builder()
                                                               .writeTimeout(Duration.ZERO)
                                                               .connectionAcquisitionTimeout(Duration.ofSeconds(60))
                                                               .maxConcurrency(8)
                                                               .build();

        S3Configuration s3Configuration = S3Configuration.builder()
                                                         .checksumValidationEnabled(false)
                                                         .chunkedEncodingEnabled(true)
                                                         .build();

        S3AsyncClientBuilder clientBuilder = S3AsyncClient.builder()
                                                          .httpClient(httpClient)
                                                          .region(Region.of(REGION))
                                                          .credentialsProvider(credentialsProvider)
                                                          .serviceConfiguration(s3Configuration);

        if (ENDPOINT != null && !ENDPOINT.isBlank()) {
            clientBuilder.endpointOverride(URI.create(ENDPOINT));
        }
        s3Client = clientBuilder.build();
    }

    public static S3Uploader getInstance() {
        return INSTANCE;
    }
    
    public void uploadArtifact(String sessionId, File file, String s3FileName) {
        
        String key = String.format(S3_KEY_FORMAT, sessionId, s3FileName);
        if (!StringUtils.isEmpty(TENANT)) {
            key = TENANT + '/' + String.format(S3_KEY_FORMAT, sessionId, s3FileName);
        }

        System.out.println("key: " + key);
        PutObjectRequest request = PutObjectRequest.builder()
                                                   .contentLength(file.length())
                                                   .key(key)
                                                   .bucket(BUCKET)
                                                   .build();

        s3Client.putObject(request, AsyncRequestBody.fromFile(file)).whenComplete((msg, ex) -> {
            if (ex != null) {
                System.out.println(String.format("Unable to put object to S3! File: %s", file));
                System.out.println(msg);
                ex.printStackTrace();
            } else {
                System.out.println(msg);
                System.out.println(String.format("File uploaded to S3. File: %s; eTag: %s", file, msg.eTag()));
                file.delete();
            }
        });
        
 
    }


}
