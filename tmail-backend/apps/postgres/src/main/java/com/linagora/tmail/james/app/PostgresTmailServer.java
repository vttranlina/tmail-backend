package com.linagora.tmail.james.app;

import java.util.List;

import org.apache.james.ExtraProperties;
import org.apache.james.GuiceJamesServer;
import org.apache.james.JamesServerMain;
import org.apache.james.SearchConfiguration;
import org.apache.james.SearchModuleChooser;
import org.apache.james.blob.api.BlobReferenceSource;
import org.apache.james.data.LdapUsersRepositoryModule;
import org.apache.james.modules.BlobExportMechanismModule;
import org.apache.james.modules.MailboxModule;
import org.apache.james.modules.MailetProcessingModule;
import org.apache.james.modules.RunArgumentsModule;
import org.apache.james.modules.blobstore.BlobStoreCacheModulesChooser;
import org.apache.james.modules.blobstore.BlobStoreModulesChooser;
import org.apache.james.modules.data.PostgresDataModule;
import org.apache.james.modules.data.PostgresDelegationStoreModule;
import org.apache.james.modules.data.PostgresUsersRepositoryModule;
import org.apache.james.modules.data.SievePostgresRepositoryModules;
import org.apache.james.modules.event.RabbitMQEventBusModule;
import org.apache.james.modules.events.PostgresDeadLetterModule;
import org.apache.james.modules.eventstore.MemoryEventStoreModule;
import org.apache.james.modules.mailbox.DefaultEventModule;
import org.apache.james.modules.mailbox.PostgresDeletedMessageVaultModule;
import org.apache.james.modules.mailbox.PostgresMailboxModule;
import org.apache.james.modules.mailbox.TikaMailboxModule;
import org.apache.james.modules.protocols.IMAPServerModule;
import org.apache.james.modules.protocols.LMTPServerModule;
import org.apache.james.modules.protocols.ManageSieveServerModule;
import org.apache.james.modules.protocols.POP3ServerModule;
import org.apache.james.modules.protocols.ProtocolHandlerModule;
import org.apache.james.modules.protocols.SMTPServerModule;
import org.apache.james.modules.queue.activemq.ActiveMQQueueModule;
import org.apache.james.modules.queue.rabbitmq.RabbitMQModule;
import org.apache.james.modules.server.DataRoutesModules;
import org.apache.james.modules.server.DefaultProcessorsConfigurationProviderModule;
import org.apache.james.modules.server.InconsistencyQuotasSolvingRoutesModule;
import org.apache.james.modules.server.JMXServerModule;
import org.apache.james.modules.server.MailQueueRoutesModule;
import org.apache.james.modules.server.MailRepositoriesRoutesModule;
import org.apache.james.modules.server.MailboxRoutesModule;
import org.apache.james.modules.server.NoJwtModule;
import org.apache.james.modules.server.RawPostDequeueDecoratorModule;
import org.apache.james.modules.server.ReIndexingModule;
import org.apache.james.modules.server.SieveRoutesModule;
import org.apache.james.modules.server.TaskManagerModule;
import org.apache.james.modules.server.WebAdminReIndexingTaskSerializationModule;
import org.apache.james.modules.server.WebAdminServerModule;
import org.apache.james.modules.vault.DeletedMessageVaultRoutesModule;
import org.apache.james.server.blob.deduplication.StorageStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.inject.Module;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.util.Modules;

public class PostgresTmailServer {
    static Logger LOGGER = LoggerFactory.getLogger("org.apache.james.CONFIGURATION");

    public static void main(String[] args) throws Exception {
        ExtraProperties.initialize();

        PostgresTmailConfiguration configuration = PostgresTmailConfiguration.builder()
            .useWorkingDirectoryEnvProperty()
            .build();

        LOGGER.info("Loading configuration {}", configuration.toString());
        GuiceJamesServer server = createServer(configuration)
            .combineWith(new JMXServerModule())
            .overrideWith(new RunArgumentsModule(args));

        JamesServerMain.main(server);
    }

    public static GuiceJamesServer createServer(PostgresTmailConfiguration configuration) {
        SearchConfiguration searchConfiguration = configuration.searchConfiguration();

        return GuiceJamesServer.forConfiguration(configuration)
            .combineWith(SearchModuleChooser.chooseModules(searchConfiguration))
            .combineWith(chooseUserRepositoryModule(configuration))
            .combineWith(chooseBlobStoreModules(configuration))
            .combineWith(chooseEventBusModules(configuration))
            .combineWith(chooseDeletedMessageVaultModules(configuration))
            .combineWith(POSTGRES_MODULE_AGGREGATE);
    }


    private static final Module WEBADMIN = Modules.combine(
        new WebAdminServerModule(),
        new DataRoutesModules(),
        new InconsistencyQuotasSolvingRoutesModule(),
        new MailboxRoutesModule(),
        new MailQueueRoutesModule(),
        new MailRepositoriesRoutesModule(),
        new ReIndexingModule(),
        new SieveRoutesModule(),
        new WebAdminReIndexingTaskSerializationModule());

    private static final Module PROTOCOLS = Modules.combine(
        new IMAPServerModule(),
        new LMTPServerModule(),
        new ManageSieveServerModule(),
        new POP3ServerModule(),
        new ProtocolHandlerModule(),
        new SMTPServerModule(),
        WEBADMIN);

    private static final Module POSTGRES_SERVER_MODULE = Modules.combine(
        new ActiveMQQueueModule(),
        new BlobExportMechanismModule(),
        new PostgresDelegationStoreModule(),
        new DefaultProcessorsConfigurationProviderModule(),
        new PostgresMailboxModule(),
        new PostgresDeadLetterModule(),
        new PostgresDataModule(),
        new MailboxModule(),
        new NoJwtModule(),
        new RawPostDequeueDecoratorModule(),
        new SievePostgresRepositoryModules(),
        new TaskManagerModule(),
        new MemoryEventStoreModule(),
        new TikaMailboxModule());
    private static final Module POSTGRES_MODULE_AGGREGATE = Modules.combine(
        new MailetProcessingModule(), POSTGRES_SERVER_MODULE, PROTOCOLS);

    private static List<Module> chooseBlobStoreModules(PostgresTmailConfiguration configuration) {
        ImmutableList.Builder<Module> builder = ImmutableList.<Module>builder()
            .addAll(BlobStoreModulesChooser.chooseModules(configuration.blobStoreConfiguration()))
            .add(new BlobStoreCacheModulesChooser.CacheDisabledModule());

        // should remove this after https://github.com/linagora/james-project/issues/4998
        if (configuration.blobStoreConfiguration().storageStrategy().equals(StorageStrategy.DEDUPLICATION)) {
            builder.add(binder -> Multibinder.newSetBinder(binder, BlobReferenceSource.class));
        }

        return builder.build();
    }

    public static List<Module> chooseEventBusModules(PostgresTmailConfiguration configuration) {
        return switch (configuration.eventBusImpl()) {
            case IN_MEMORY -> List.of(new DefaultEventModule());
            case RABBITMQ -> List.of(new RabbitMQModule(),
                Modules.override(new DefaultEventModule()).with(new RabbitMQEventBusModule()));
        };
    }

    public static List<Module> chooseUserRepositoryModule(PostgresTmailConfiguration configuration) {
        return switch (configuration.usersRepositoryImplementation()) {
            case LDAP -> ImmutableList.of(new LdapUsersRepositoryModule());
            case DEFAULT -> ImmutableList.of(new PostgresUsersRepositoryModule());
        };
    }

    private static Module chooseDeletedMessageVaultModules(PostgresTmailConfiguration configuration) {
        if (configuration.deletedMessageVaultConfiguration().isEnabled()) {
            return Modules.combine(new PostgresDeletedMessageVaultModule(), new DeletedMessageVaultRoutesModule());
        }
        return Modules.EMPTY_MODULE;
    }

}
