package com.linagora.tmail.james;

import static org.apache.james.blob.objectstorage.aws.DockerAwsS3Container.ACCESS_KEY_ID;
import static org.apache.james.blob.objectstorage.aws.DockerAwsS3Container.SECRET_ACCESS_KEY;

import java.net.URI;
import java.util.UUID;

import org.apache.james.GuiceModuleTestExtension;
import org.apache.james.blob.api.BucketName;
import org.apache.james.blob.objectstorage.aws.AwsS3AuthConfiguration;
import org.apache.james.blob.objectstorage.aws.DockerAwsS3Container;
import org.apache.james.blob.objectstorage.aws.Region;
import org.apache.james.blob.objectstorage.aws.S3BlobStoreConfiguration;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;

import com.google.inject.Module;

public class S3MinioExtension implements GuiceModuleTestExtension {
    private static final String MINIO_IMAGE = "quay.io/minio/minio";
    private static final String MINIO_TAG = "RELEASE.2024-01-29T03-56-32Z";
    private static final String MINIO_IMAGE_FULL = MINIO_IMAGE + ":" + MINIO_TAG;
    private static final int MINIO_PORT = 9000;

    static final GenericContainer<?> minioContainer = new GenericContainer<>(MINIO_IMAGE_FULL)
        .withExposedPorts(MINIO_PORT, 9090)
        .withEnv("MINIO_ROOT_USER", ACCESS_KEY_ID)
        .withEnv("MINIO_ROOT_PASSWORD", SECRET_ACCESS_KEY)
        .withCommand("server", "/data", "--console-address", ":9090")
        .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("james-minio-s3-test"));

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        if (minioContainer.isRunning()) {
            return;
        }
        minioContainer.start();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        minioContainer.stop();
    }

    @Override
    public Module getModule() {
        BucketName defaultBucketName = BucketName.of(UUID.randomUUID().toString());
        AwsS3AuthConfiguration authConfiguration = AwsS3AuthConfiguration.builder()
            .endpoint(URI.create("http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(MINIO_PORT) + "/"))
            .accessKeyId(ACCESS_KEY_ID)
            .secretKey(SECRET_ACCESS_KEY)
            .build();

        Region region = DockerAwsS3Container.REGION;
        S3BlobStoreConfiguration configuration = S3BlobStoreConfiguration.builder()
            .authConfiguration(authConfiguration)
            .region(region)
            .defaultBucketName(defaultBucketName)
            .bucketPrefix(UUID.randomUUID().toString())
            .build();

        return binder -> {
            binder.bind(BucketName.class).toInstance(defaultBucketName);
            binder.bind(Region.class).toInstance(region);
            binder.bind(AwsS3AuthConfiguration.class).toInstance(authConfiguration);
            binder.bind(S3BlobStoreConfiguration.class).toInstance(configuration);
        };

    }
}
