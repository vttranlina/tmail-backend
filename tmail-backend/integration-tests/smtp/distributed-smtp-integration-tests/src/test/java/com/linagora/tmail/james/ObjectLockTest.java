package com.linagora.tmail.james;

import static org.apache.james.utils.TestIMAPClient.INBOX;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.james.GuiceJamesServer;
import org.apache.james.JamesServerBuilder;
import org.apache.james.JamesServerExtension;
import org.apache.james.backends.redis.RedisExtension;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.core.builder.MimeMessageBuilder;
import org.apache.james.modules.protocols.ImapGuiceProbe;
import org.apache.james.modules.protocols.SmtpGuiceProbe;
import org.apache.james.server.core.MailImpl;
import org.apache.james.util.Port;
import org.apache.james.utils.DataProbeImpl;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.SMTPMessageSender;
import org.apache.james.utils.TestIMAPClient;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.multibindings.Multibinder;
import com.linagora.tmail.blob.blobid.list.BlobStoreConfiguration;
import com.linagora.tmail.james.app.CassandraExtension;
import com.linagora.tmail.james.app.DistributedJamesConfiguration;
import com.linagora.tmail.james.app.DistributedServer;
import com.linagora.tmail.james.app.DockerOpenSearchExtension;
import com.linagora.tmail.james.app.EventBusKeysChoice;
import com.linagora.tmail.james.app.RabbitMQExtension;
import com.linagora.tmail.module.LinagoraTestJMAPServerModule;
import com.linagora.tmail.team.TeamMailboxProbe;

public class ObjectLockTest {

    @RegisterExtension
    static S3MinioExtension s3BlobStoreExtension = new S3MinioExtension();

    @RegisterExtension
    static JamesServerExtension testExtension = new JamesServerBuilder<DistributedJamesConfiguration>(tmpDir ->
        DistributedJamesConfiguration.builder()
            .workingDirectory(tmpDir)
            .configurationFromClasspath()
            .blobStore(BlobStoreConfiguration.builder()
                .disableCache()
                .deduplication()
                .noCryptoConfig()
                .enableSingleSave())
            .eventBusKeysChoice(EventBusKeysChoice.REDIS)
            .build())
        .extension(new DockerOpenSearchExtension())
        .extension(new CassandraExtension())
        .extension(new RabbitMQExtension())
        .extension(new RedisExtension())
        .extension(s3BlobStoreExtension)
        .server(configuration -> DistributedServer.createServer(configuration)
            .overrideWith(new LinagoraTestJMAPServerModule())
            .overrideWith(binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding().to(TeamMailboxProbe.class)))
        .build();

    public static final Domain DOMAIN = Domain.of("domain.tld");
    public static final Username BOB = Username.fromLocalPartWithDomain("bob", DOMAIN);
    public static final String BOB_PASSWORD = "123456";
    public static final ConditionFactory AWAIT_TEN_SECONDS = Awaitility.await().atMost(Duration.ofSeconds(300));

    private SMTPMessageSender messageSender;
    private int imapPort;

    @BeforeEach
    void setUp(GuiceJamesServer server) throws Exception {
        Port smtpPort = server.getProbe(SmtpGuiceProbe.class).getSmtpPort();
        imapPort = server.getProbe(ImapGuiceProbe.class).getImapPort();
        messageSender = new SMTPMessageSender("domain.tld")
            .connect("127.0.0.1", smtpPort);

        server.getProbe(DataProbeImpl.class)
            .fluent()
            .addDomain(DOMAIN.asString())
            .addUser(BOB.asString(), BOB_PASSWORD)
            .addUser("user1@domain.tld", "pass")
            .addUser("user2@domain.tld", "pass")
            .addUser("user3@domain.tld", "pass")
            .addUser("user4@domain.tld", "pass")
            .addUser("user5@domain.tld", "pass")
            .addUser("user6@domain.tld", "pass")
            .addUser("user7@domain.tld", "pass")
            .addUser("user8@domain.tld", "pass")
            .addUser("user9@domain.tld", "pass")
            .addUser("user10@domain.tld", "pass");
    }

    @Test
    void sendMailShouldSucceed() throws Exception {
        MailImpl mail = MailImpl.builder()
            .name("mymail")
            .sender(BOB.asString())
            .addRecipients("user1@domain.tld",
                "user2@domain.tld",
                "user3@domain.tld",
                "user4@domain.tld",
                "user5@domain.tld",
                "user6@domain.tld",
                "user7@domain.tld",
                "user8@domain.tld",
                "user9@domain.tld",
                "user10@domain.tld")
            .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
                .setMultipartWithBodyParts(
                    MimeMessageBuilder.bodyPartBuilder()
                        .data("simple text").build(),
                    MimeMessageBuilder.bodyPartFromBytes(ICS_BASE64.getBytes(StandardCharsets.UTF_8)))
                .setSubject("test")
                .build())
            .build();

        messageSender.authenticate(BOB.asString(), BOB_PASSWORD)
            .sendMessage(mail);

        for (int i = 1; i <= 10; i++) {
            new TestIMAPClient().connect("127.0.0.1", imapPort)
                .login(String.format("user%s@domain.tld", i), "pass")
                .select(INBOX)
                .awaitMessage(AWAIT_TEN_SECONDS);
        }
    }

    private static final String ICS_BASE64 = "Content-Type: application/ics;\n" +
        " name=\"invite.ics\"\n" +
        "Content-Transfer-Encoding: base64\n" +
        "Content-Disposition: attachment;\n" +
        " filename=\"invite.ics\"\n" +
        "\n" +
        "QkVHSU46VkNBTEVOREFSDQpQUk9ESUQ6LS8vR29vZ2xlIEluYy8vR29vZ2xlIENhbGVuZGFy\n" +
        "IDcwLjkwNTQvL0VODQpWRVJTSU9OOjIuMA0KQ0FMU0NBTEU6R1JFR09SSUFODQpNRVRIT0Q6\n" +
        "UkVRVUVTVA0KQkVHSU46VkVWRU5UDQpEVFNUQVJUOjIwMTcwMTIwVDEzMDAwMFoNCkRURU5E\n" +
        "OjIwMTcwMTIwVDE0MDAwMFoNCkRUU1RBTVA6MjAxNzAxMTlUMTkxODIzWg0KT1JHQU5JWkVS\n" +
        "O0NOPUFudG9pbmUgRHVwcmF0Om1haWx0bzphbnRkdXByYXRAZ21haWwuY29tDQpVSUQ6YWg4\n" +
        "Nms1bTM0MmJtY3JiZTlraGtraGxuMDBAZ29vZ2xlLmNvbQ0KQVRURU5ERUU7Q1VUWVBFPUlO\n" +
        "RElWSURVQUw7Uk9MRT1SRVEtUEFSVElDSVBBTlQ7UEFSVFNUQVQ9TkVFRFMtQUNUSU9OO1JT\n" +
        "VlA9DQogVFJVRTtDTj1hZHVwcmF0QGxpbmFnb3JhLmNvbTtYLU5VTS1HVUVTVFM9MDptYWls\n" +
        "dG86YWR1cHJhdEBsaW5hZ29yYS5jb20NCkFUVEVOREVFO0NVVFlQRT1JTkRJVklEVUFMO1JP\n" +
        "TEU9UkVRLVBBUlRJQ0lQQU5UO1BBUlRTVEFUPUFDQ0VQVEVEO1JTVlA9VFJVRQ0KIDtDTj1B\n" +
        "bnRvaW5lIER1cHJhdDtYLU5VTS1HVUVTVFM9MDptYWlsdG86YW50ZHVwcmF0QGdtYWlsLmNv\n" +
        "bQ0KQ1JFQVRFRDoyMDE3MDExOVQxOTE4MjNaDQpERVNDUklQVElPTjpBZmZpY2hleiB2b3Ry\n" +
        "ZSDDqXbDqW5lbWVudCBzdXIgbGEgcGFnZSBodHRwczovL3d3dy5nb29nbGUuY29tL2NhbA0K\n" +
        "IGVuZGFyL2V2ZW50P2FjdGlvbj1WSUVXJmVpZD1ZV2c0Tm1zMWJUTTBNbUp0WTNKaVpUbHJh\n" +
        "R3RyYUd4dU1EQWdZV1IxY0hKaGRFQg0KIHNhVzVoWjI5eVlTNWpiMjAmdG9rPU1Ua2pZVzUw\n" +
        "WkhWd2NtRjBRR2R0WVdsc0xtTnZiVGcxT1RNNU5XTTRNR1JsWW1FMVlUSTROeg0KIFJqTjJV\n" +
        "eU5qVTBNMll5Wm1RNE56UmtOVGhoWVRRJmN0ej1FdXJvcGUvUGFyaXMmaGw9ZnIuDQpMQVNU\n" +
        "LU1PRElGSUVEOjIwMTcwMTE5VDE5MTgyM1oNCkxPQ0FUSU9OOg0KU0VRVUVOQ0U6MA0KU1RB\n" +
        "VFVTOkNPTkZJUk1FRA0KU1VNTUFSWToNClRSQU5TUDpPUEFRVUUNCkVORDpWRVZFTlQNCkVO\n" +
        "RDpWQ0FMRU5EQVINCg==";
}
