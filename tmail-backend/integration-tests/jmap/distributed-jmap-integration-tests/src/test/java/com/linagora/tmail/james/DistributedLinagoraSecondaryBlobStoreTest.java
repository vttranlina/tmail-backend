package com.linagora.tmail.james;

import static com.linagora.tmail.blob.guice.BlobStoreModulesChooser.MAYBE_SECONDARY_BLOBSTORE;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.requestSpecification;
import static io.restassured.http.ContentType.JSON;
import static org.apache.james.blob.objectstorage.aws.S3BlobStoreConfiguration.UPLOAD_RETRY_EXCEPTION_PREDICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Durations.ONE_HUNDRED_MILLISECONDS;
import static org.awaitility.Durations.ONE_MINUTE;
import static org.awaitility.Durations.TEN_SECONDS;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.apache.http.HttpStatus;
import org.apache.james.GuiceJamesServer;
import org.apache.james.JamesServerBuilder;
import org.apache.james.JamesServerExtension;
import org.apache.james.SearchConfiguration;
import org.apache.james.blob.api.BlobId;
import org.apache.james.blob.api.BlobStoreDAO;
import org.apache.james.blob.api.BucketName;
import org.apache.james.blob.objectstorage.aws.AwsS3AuthConfiguration;
import org.apache.james.blob.objectstorage.aws.DockerAwsS3Container;
import org.apache.james.blob.objectstorage.aws.S3BlobStoreConfiguration;
import org.apache.james.blob.objectstorage.aws.S3BlobStoreDAO;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.jmap.JMAPUrls;
import org.apache.james.jmap.JmapGuiceProbe;
import org.apache.james.jmap.http.UserCredential;
import org.apache.james.junit.categories.BasicFeature;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.modules.AwsS3BlobStoreExtension;
import org.apache.james.modules.MailboxProbeImpl;
import org.apache.james.utils.DataProbeImpl;
import org.apache.james.utils.GuiceProbe;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.Inject;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Named;
import com.linagora.tmail.blob.guice.BlobStoreConfiguration;
import com.linagora.tmail.blob.secondaryblobstore.SecondaryBlobStoreDAO;
import com.linagora.tmail.encrypted.MailboxConfiguration;
import com.linagora.tmail.james.app.CassandraExtension;
import com.linagora.tmail.james.app.DistributedJamesConfiguration;
import com.linagora.tmail.james.app.DistributedServer;
import com.linagora.tmail.james.app.EventBusKeysChoice;
import com.linagora.tmail.james.app.RabbitMQExtension;
import com.linagora.tmail.james.common.EncryptHelper;
import com.linagora.tmail.james.common.LinagoraEmailSendMethodContract$;
import com.linagora.tmail.james.common.module.JmapGuiceKeystoreManagerModule;
import com.linagora.tmail.james.jmap.firebase.FirebaseModuleChooserConfiguration;
import com.linagora.tmail.module.LinagoraTestJMAPServerModule;

import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

@Tag(BasicFeature.TAG)
class DistributedLinagoraSecondaryBlobStoreTest {
    public static final int FIVE_SECONDS = 5000;

    public static final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(ONE_HUNDRED_MILLISECONDS)
        .and()
        .with()
        .pollDelay(ONE_HUNDRED_MILLISECONDS)
        .await();

    static class BlobStoreProbe implements GuiceProbe {
        private final S3BlobStoreDAO primaryBlobStoreDAO;
        private final S3BlobStoreDAO secondaryBlobStoreDAO;

        @Inject
        public BlobStoreProbe(@Named(MAYBE_SECONDARY_BLOBSTORE) BlobStoreDAO blobStoreDAO) {
            SecondaryBlobStoreDAO secondaryBlobStoreDAO = (SecondaryBlobStoreDAO) blobStoreDAO;
            this.primaryBlobStoreDAO = (S3BlobStoreDAO) secondaryBlobStoreDAO.getFirstBlobStoreDAO();
            this.secondaryBlobStoreDAO = (S3BlobStoreDAO) secondaryBlobStoreDAO.getSecondBlobStoreDAO();
        }

        public S3BlobStoreDAO getPrimaryBlobStoreDAO() {
            return primaryBlobStoreDAO;
        }

        public S3BlobStoreDAO getSecondaryBlobStoreDAO() {
            return secondaryBlobStoreDAO;
        }
    }

    static final Domain DOMAIN = Domain.of("domain.tld");
    static final Username BOB = Username.fromLocalPartWithDomain("bob", DOMAIN);
    static final Username ANDRE = Username.fromLocalPartWithDomain("andre", DOMAIN);
    static final String BOB_PASSWORD = "bobpassword";
    static final String ANDRE_PASSWORD = "andrepassword";
    static final String ACCEPT_RFC8621_VERSION_HEADER = "application/json; jmapVersion=rfc-8621";
    static final String ACCOUNT_ID = "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6";

    static DockerAwsS3Container secondaryS3 = new DockerAwsS3Container();
    static S3BlobStoreConfiguration secondaryS3Configuration;

    static {
        secondaryS3.start();
        AwsS3AuthConfiguration authConfiguration = AwsS3AuthConfiguration.builder()
            .endpoint(secondaryS3.getEndpoint())
            .accessKeyId(DockerAwsS3Container.ACCESS_KEY_ID)
            .secretKey(DockerAwsS3Container.SECRET_ACCESS_KEY)
            .build();

        secondaryS3Configuration = S3BlobStoreConfiguration.builder()
            .authConfiguration(authConfiguration)
            .region(secondaryS3.dockerAwsS3().region())
            .uploadRetrySpec(Optional.of(Retry.backoff(3, java.time.Duration.ofSeconds(1))
                .filter(UPLOAD_RETRY_EXCEPTION_PREDICATE)))
            .readTimeout(Optional.of(Duration.ofMillis(500)))
            .build();
    }

    @RegisterExtension
    static JamesServerExtension
        testExtension = new JamesServerBuilder<DistributedJamesConfiguration>(tmpDir ->
        DistributedJamesConfiguration.builder()
            .workingDirectory(tmpDir)
            .configurationFromClasspath()
            .searchConfiguration(SearchConfiguration.scanning())
            .blobStore(BlobStoreConfiguration.builder()
                .disableCache()
                .deduplication()
                .noCryptoConfig()
                .disableSingleSave()
                .secondaryS3BlobStore(secondaryS3Configuration))
            .eventBusKeysChoice(EventBusKeysChoice.RABBITMQ)
            .firebaseModuleChooserConfiguration(FirebaseModuleChooserConfiguration.DISABLED)
            .mailbox(new MailboxConfiguration(true))
            .build())
        .extension(new CassandraExtension())
        .extension(new RabbitMQExtension())
        .extension(new AwsS3BlobStoreExtension())
        .server(configuration -> DistributedServer.createServer(configuration)
            .overrideWith(new LinagoraTestJMAPServerModule())
            .overrideWith(new JmapGuiceKeystoreManagerModule())
            .overrideWith(binder -> Multibinder.newSetBinder(binder, GuiceProbe.class).addBinding().to(BlobStoreProbe.class)))
        .build();

    @AfterAll
    static void afterAll() {
        secondaryS3.stop();
    }

    @BeforeEach
    void setUp(GuiceJamesServer server) throws Exception {
        prepareBlobStore(server);

        server.getProbe(DataProbeImpl.class)
            .fluent()
            .addDomain(DOMAIN.asString())
            .addUser(BOB.asString(), BOB_PASSWORD)
            .addUser(ANDRE.asString(), ANDRE_PASSWORD);

        UserCredential userCredential = new UserCredential(BOB, BOB_PASSWORD);
        PreemptiveBasicAuthScheme authScheme = new PreemptiveBasicAuthScheme();
        authScheme.setUserName(userCredential.username().asString());
        authScheme.setPassword(userCredential.password());
        requestSpecification = new RequestSpecBuilder()
            .setContentType(JSON)
            .setAccept(JSON)
            .setConfig(RestAssuredConfig.newConfig().encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(JmapGuiceProbe.class)
                .getJmapPort()
                .getValue())
            .setBasePath(JMAPUrls.JMAP)
            .setAuth(authScheme)
            .addHeader(ACCEPT.toString(), ACCEPT_RFC8621_VERSION_HEADER)
            .build();

        MailboxProbeImpl mailboxProbe = server.getProbe(MailboxProbeImpl.class);
        mailboxProbe.createMailbox(MailboxPath.inbox(BOB));
        mailboxProbe.createMailbox(MailboxPath.inbox(ANDRE));

        EncryptHelper.uploadPublicKey(ACCOUNT_ID, requestSpecification);
    }

    void prepareBlobStore(GuiceJamesServer server) {
        if (secondaryS3.isPaused()) {
            secondaryS3.unpause();
        }
        BlobStoreProbe blobStoreProbe = server.getProbe(BlobStoreProbe.class);
        blobStoreProbe.getPrimaryBlobStoreDAO().deleteAllBuckets().block();
        blobStoreProbe.getSecondaryBlobStoreDAO().deleteAllBuckets().block();
    }

    @Test
    void sendEmailShouldResultingInSavingDataToBothObjectStorages(GuiceJamesServer server) {
        given()
            .body(LinagoraEmailSendMethodContract$.MODULE$.bobSendsAMailToAndre(server))
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        BlobStoreProbe blobStoreProbe = server.getProbe(BlobStoreProbe.class);

        calmlyAwait.atMost(TEN_SECONDS)
            .untilAsserted(() -> {
                BucketName bucketName = Flux.from(blobStoreProbe.getPrimaryBlobStoreDAO().listBuckets()).collectList().block().getFirst();
                List<BlobId> blobIds = Flux.from(blobStoreProbe.getPrimaryBlobStoreDAO().listBlobs(bucketName)).collectList().block();
                List<BlobId> blobIds2 = Flux.from(blobStoreProbe.getSecondaryBlobStoreDAO().listBlobs(bucketName)).collectList().block();
                assertThat(blobIds).hasSameSizeAs(blobIds2);
                assertThat(blobIds).hasSameElementsAs(blobIds2);
            });
    }

    @Test
    void sendEmailShouldResultingInEventuallySavingDataToBothObjectStoragesWhenSecondStorageIsDown(GuiceJamesServer server) throws Exception {
        secondaryS3.pause();

        given()
            .body(LinagoraEmailSendMethodContract$.MODULE$.bobSendsAMailToAndre(server))
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        Thread.sleep(FIVE_SECONDS);
        secondaryS3.unpause();

        BlobStoreProbe blobStoreProbe = server.getProbe(BlobStoreProbe.class);
        BucketName bucketName = Flux.from(blobStoreProbe.getPrimaryBlobStoreDAO().listBuckets()).collectList().block().getFirst();
        calmlyAwait.atMost(ONE_MINUTE)
            .untilAsserted(() -> {
                List<BlobId> blobIds = Flux.from(blobStoreProbe.getPrimaryBlobStoreDAO().listBlobs(bucketName)).collectList().block();
                List<BlobId> blobIds2 = Flux.from(blobStoreProbe.getSecondaryBlobStoreDAO().listBlobs(bucketName)).collectList().block();
                assertThat(blobIds2).hasSameSizeAs(blobIds);
                assertThat(blobIds2).hasSameElementsAs(blobIds);
            });
    }
}
