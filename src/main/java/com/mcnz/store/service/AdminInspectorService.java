package com.mcnz.store.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

import com.mcnz.store.config.ContainerConfigFileService;
import com.mcnz.store.config.RuntimeConfigState;
import com.mcnz.store.config.RuntimeDataSourceConfiguration;
import com.mcnz.store.config.SwappableDataSource;
import com.zaxxer.hikari.HikariDataSource;

@Service
public class AdminInspectorService {

    private static final Logger log = LoggerFactory.getLogger(AdminInspectorService.class);

    private final RuntimeConfigState runtimeConfigState;
    private final SwappableDataSource swappableDataSource;
    private final ProducerFactory<?, ?> producerFactory;
    private final ConsumerFactory<?, ?> consumerFactory;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final Environment environment;
    private final ContainerConfigFileService containerConfigFileService;

    public AdminInspectorService(
            RuntimeConfigState runtimeConfigState,
            SwappableDataSource swappableDataSource,
            ProducerFactory<?, ?> producerFactory,
            ConsumerFactory<?, ?> consumerFactory,
            KafkaListenerEndpointRegistry listenerRegistry,
            Environment environment,
            ContainerConfigFileService containerConfigFileService) {

        this.runtimeConfigState = runtimeConfigState;
        this.swappableDataSource = swappableDataSource;
        this.producerFactory = producerFactory;
        this.consumerFactory = consumerFactory;
        this.listenerRegistry = listenerRegistry;
        this.environment = environment;
        this.containerConfigFileService = containerConfigFileService;
    }

    public ConfigSnapshot currentConfig() {
        RuntimeConfigState.Snapshot snapshot = runtimeConfigState.snapshot(environment);
        Properties overrides = containerConfigFileService.readOverrides();
        String updated = overrides.getProperty("admin.overrides.last-updated", "(never)");
        return new ConfigSnapshot(snapshot, containerConfigFileService.absolutePath(), updated);
    }

    public DatabaseProbeResult inspectDatabase(String url, String username, String password) {
        RuntimeConfigState.DatabaseSettings current = runtimeConfigState.databaseSettings();
        String effectiveUrl = firstNonBlank(url, current.url());
        String effectiveUsername = firstNonBlank(username, current.username());
        String effectivePassword = password == null ? current.password() : password;

        if (effectiveUrl == null || effectiveUrl.isBlank()) {
            return new DatabaseProbeResult(false, "Database URL is required.", "", List.of(), RuntimeConfigState.parseJdbcLocation(""));
        }

        try {
            DriverManager.setLoginTimeout((int) Duration.ofSeconds(5).toSeconds());
            try (Connection connection = DriverManager.getConnection(effectiveUrl, effectiveUsername, effectivePassword)) {
                String version = connection.getMetaData().getDatabaseProductVersion();
                List<String> databases = listPostgresDatabases(connection);
                return new DatabaseProbeResult(
                        true,
                        "Database connection succeeded.",
                        version,
                        databases,
                        RuntimeConfigState.parseJdbcLocation(effectiveUrl));
            }
        } catch (Exception exception) {
            String message = rootMessage(exception);
            log.warn("Database probe failed for {}: {}", effectiveUrl, message);
            return new DatabaseProbeResult(
                    false,
                    "Database connection failed: " + message,
                    "",
                    List.of(),
                    RuntimeConfigState.parseJdbcLocation(effectiveUrl));
        }
    }

    public KafkaProbeResult inspectKafka(String bootstrapServers) {
        String effectiveBootstrapServers = firstNonBlank(bootstrapServers, runtimeConfigState.kafkaBootstrapServers());
        if (effectiveBootstrapServers == null || effectiveBootstrapServers.isBlank()) {
            return new KafkaProbeResult(false, "Kafka bootstrap servers are required.", "", List.of(), List.of(), false);
        }

        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, effectiveBootstrapServers);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);

        try (AdminClient admin = AdminClient.create(config)) {
            DescribeClusterResult cluster = admin.describeCluster();
            String clusterId = cluster.clusterId().get(5, TimeUnit.SECONDS);

            List<String> brokers = new ArrayList<>();
            for (Node node : cluster.nodes().get(5, TimeUnit.SECONDS)) {
                brokers.add(node.host() + ":" + node.port());
            }

            TreeSet<String> topicsSorted = new TreeSet<>(admin.listTopics().names().get(5, TimeUnit.SECONDS));

            return new KafkaProbeResult(
                    true,
                    "Kafka connection succeeded.",
                    clusterId,
                    brokers,
                    new ArrayList<>(topicsSorted),
                    RuntimeConfigState.isLocalKafka(effectiveBootstrapServers));
        } catch (Exception exception) {
            String message = rootMessage(exception);
            log.warn("Kafka probe failed for {}: {}", effectiveBootstrapServers, message);
            return new KafkaProbeResult(
                    false,
                    "Kafka connection failed: " + message,
                    "",
                    List.of(),
                    List.of(),
                    RuntimeConfigState.isLocalKafka(effectiveBootstrapServers));
        }
    }

    public ApplyConfigResult applyConfig(ApplyConfigCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        RuntimeConfigState.DatabaseSettings currentDb = runtimeConfigState.databaseSettings();
        String requestedProfile = firstNonBlank(command.profile(), runtimeConfigState.desiredProfile());
        String requestedDbUrl = firstNonBlank(command.databaseUrl(), currentDb.url());
        String requestedDbUser = firstNonBlank(command.databaseUsername(), currentDb.username());
        String requestedDbPassword = command.databasePassword() == null ? currentDb.password() : command.databasePassword();
        String requestedKafka = firstNonBlank(command.kafkaBootstrapServers(), runtimeConfigState.kafkaBootstrapServers());

        List<String> messages = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        OperationResult profileResult = applyProfile(command.attemptHotSwitch(), requestedProfile);
        if (!profileResult.success()) {
            errors.add(profileResult.message());
        } else {
            messages.add(profileResult.message());
        }

        OperationResult databaseResult = OperationResult.notChanged("Database configuration unchanged.");
        if (hasChanged(currentDb.url(), requestedDbUrl) || hasChanged(currentDb.username(), requestedDbUser)
                || hasChanged(currentDb.password(), requestedDbPassword)) {
            if (command.attemptHotSwitch()) {
                databaseResult = hotSwitchDatabase(requestedDbUrl, requestedDbUser, requestedDbPassword);
            } else {
                databaseResult = OperationResult.restartRequired("Hot switch skipped for database; restart required to apply.");
            }

            if (!databaseResult.success()) {
                errors.add(databaseResult.message());
            } else {
                messages.add(databaseResult.message());
            }
        }

        OperationResult kafkaResult = OperationResult.notChanged("Kafka configuration unchanged.");
        if (hasChanged(runtimeConfigState.kafkaBootstrapServers(), requestedKafka)) {
            if (command.attemptHotSwitch()) {
                kafkaResult = hotSwitchKafka(requestedKafka);
            } else {
                kafkaResult = OperationResult.restartRequired("Hot switch skipped for Kafka; restart required to apply.");
            }

            if (!kafkaResult.success()) {
                errors.add(kafkaResult.message());
            } else {
                messages.add(kafkaResult.message());
            }
        }

        boolean persisted = false;
        if (command.persistToContainer()) {
            try {
                containerConfigFileService.persistOverrides(
                        requestedProfile,
                        requestedDbUrl,
                        requestedDbUser,
                        requestedDbPassword,
                        requestedKafka);
                runtimeConfigState.updateDesiredProfile(requestedProfile);
                persisted = true;
                messages.add("Configuration persisted to container file. Restart app/container to fully apply non-hot-swappable settings.");
            } catch (Exception exception) {
                String message = "Failed to persist overrides: " + rootMessage(exception);
                errors.add(message);
                log.error(message, exception);
            }
        }

        boolean restartRequired = profileResult.restartRequired() || databaseResult.restartRequired() || kafkaResult.restartRequired();
        if (!errors.isEmpty()) {
            restartRequired = true;
        }

        String outcome = determineOutcome(errors, restartRequired, persisted, databaseResult, kafkaResult, profileResult);

        return new ApplyConfigResult(
                outcome,
                restartRequired,
                persisted,
                containerConfigFileService.absolutePath(),
                profileResult,
                databaseResult,
                kafkaResult,
                messages,
                errors,
                currentConfig());
    }

    private OperationResult applyProfile(boolean attemptHotSwitch, String requestedProfile) {
        String[] activeProfiles = environment.getActiveProfiles();
        String active = activeProfiles.length == 0 ? "" : String.join(",", activeProfiles);

        if (!hasChanged(active, requestedProfile)) {
            return OperationResult.notChanged("Profile unchanged: " + requestedProfile);
        }

        if (attemptHotSwitch) {
            return OperationResult.restartRequired(
                    "Hot profile switch is not supported without refreshing the Spring context. "
                            + "Requested profile '" + requestedProfile + "' was queued for restart fallback.");
        }

        return OperationResult.restartRequired("Profile change queued for restart: " + requestedProfile);
    }

    private OperationResult hotSwitchDatabase(String url, String username, String password) {
        DatabaseProbeResult probe = inspectDatabase(url, username, password);
        if (!probe.connected()) {
            return OperationResult.failedWithRestart("Database hot switch failed pre-check: " + probe.message());
        }

        HikariDataSource candidate = null;
        try {
            candidate = RuntimeDataSourceConfiguration.buildDataSource(
                    url,
                    username,
                    password,
                    "org.postgresql.Driver",
                    "store-runtime-" + System.currentTimeMillis());

            try (Connection connection = candidate.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                statement.execute();
            }

            swappableDataSource.swap(candidate);
            runtimeConfigState.updateDatabase(url, username, password);
            return OperationResult.changed("Database hot switch applied successfully.");
        } catch (Exception exception) {
            if (candidate != null) {
                candidate.close();
            }
            return OperationResult.failedWithRestart("Database hot switch failed: " + rootMessage(exception));
        }
    }

    private OperationResult hotSwitchKafka(String bootstrapServers) {
        KafkaProbeResult probe = inspectKafka(bootstrapServers);
        if (!probe.connected()) {
            return OperationResult.failedWithRestart("Kafka hot switch failed pre-check: " + probe.message());
        }

        if (!(producerFactory instanceof DefaultKafkaProducerFactory<?, ?> defaultProducerFactory)) {
            return OperationResult.failedWithRestart("Kafka producer factory does not support runtime updates; restart required.");
        }

        if (!(consumerFactory instanceof DefaultKafkaConsumerFactory<?, ?> defaultConsumerFactory)) {
            return OperationResult.failedWithRestart("Kafka consumer factory does not support runtime updates; restart required.");
        }

        String previousBootstrapServers = runtimeConfigState.kafkaBootstrapServers();
        List<MessageListenerContainer> containers = new ArrayList<>(listenerRegistry.getListenerContainers());
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        try {
            for (MessageListenerContainer container : containers) {
                container.stop();
            }

            defaultConsumerFactory.updateConfigs(updates);
            defaultProducerFactory.updateConfigs(updates);
            defaultProducerFactory.reset();

            for (MessageListenerContainer container : containers) {
                container.start();
            }

            runtimeConfigState.updateKafkaBootstrapServers(bootstrapServers);
            return OperationResult.changed("Kafka hot switch applied successfully.");
        } catch (Exception exception) {
            log.error("Kafka hot switch failed", exception);
            String rollbackNote = rollbackKafkaRuntime(defaultProducerFactory, defaultConsumerFactory, containers, previousBootstrapServers);
            return OperationResult.failedWithRestart("Kafka hot switch failed: " + rootMessage(exception) + rollbackNote);
        }
    }

    private String rollbackKafkaRuntime(
            DefaultKafkaProducerFactory<?, ?> producer,
            DefaultKafkaConsumerFactory<?, ?> consumer,
            List<MessageListenerContainer> containers,
            String previousBootstrapServers) {

        try {
            Map<String, Object> rollback = new LinkedHashMap<>();
            rollback.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, previousBootstrapServers);
            consumer.updateConfigs(rollback);
            producer.updateConfigs(rollback);
            producer.reset();
            for (MessageListenerContainer container : containers) {
                if (!container.isRunning()) {
                    container.start();
                }
            }
            return " Runtime rollback attempted using previous Kafka settings.";
        } catch (Exception rollbackException) {
            log.error("Kafka runtime rollback failed", rollbackException);
            return " Runtime rollback also failed: " + rootMessage(rollbackException) + ".";
        }
    }

    private String determineOutcome(
            List<String> errors,
            boolean restartRequired,
            boolean persisted,
            OperationResult databaseResult,
            OperationResult kafkaResult,
            OperationResult profileResult) {

        if (!errors.isEmpty() && persisted) {
            return "PERSISTED_RESTART_REQUIRED";
        }
        if (!errors.isEmpty()) {
            return "HOT_SWITCH_FAILED";
        }
        if (databaseResult.changed() || kafkaResult.changed()) {
            return restartRequired ? "HOT_SWITCH_PARTIAL_RESTART_REQUIRED" : "HOT_SWITCH_APPLIED";
        }
        if (profileResult.restartRequired() || restartRequired) {
            return persisted ? "PERSISTED_RESTART_REQUIRED" : "RESTART_REQUIRED";
        }
        return persisted ? "PERSISTED_ONLY" : "NO_CHANGES";
    }

    private List<String> listPostgresDatabases(Connection connection) {
        List<String> databases = new ArrayList<>();
        try (PreparedStatement statement = connection
                .prepareStatement("select datname from pg_database where datistemplate = false order by datname");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                databases.add(resultSet.getString(1));
            }
        } catch (Exception exception) {
            databases.add("(database list query failed: " + rootMessage(exception) + ")");
        }
        return databases;
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private boolean hasChanged(String current, String requested) {
        String a = current == null ? "" : current.trim();
        String b = requested == null ? "" : requested.trim();
        return !a.equals(b);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public record ConfigSnapshot(RuntimeConfigState.Snapshot runtime, String overridesFilePath, String overridesLastUpdated) {
    }

    public record DatabaseProbeResult(
            boolean connected,
            String message,
            String databaseVersion,
            List<String> databases,
            RuntimeConfigState.JdbcLocation location) {
    }

    public record KafkaProbeResult(
            boolean connected,
            String message,
            String clusterId,
            List<String> brokers,
            List<String> topics,
            boolean local) {
    }

    public record OperationResult(boolean success, boolean changed, boolean restartRequired, String message) {
        public static OperationResult changed(String message) {
            return new OperationResult(true, true, false, message);
        }

        public static OperationResult notChanged(String message) {
            return new OperationResult(true, false, false, message);
        }

        public static OperationResult restartRequired(String message) {
            return new OperationResult(true, true, true, message);
        }

        public static OperationResult failedWithRestart(String message) {
            return new OperationResult(false, true, true, message);
        }
    }

    public record ApplyConfigCommand(
            String profile,
            String databaseUrl,
            String databaseUsername,
            String databasePassword,
            String kafkaBootstrapServers,
            boolean attemptHotSwitch,
            boolean persistToContainer) {
    }

    public record ApplyConfigResult(
            String outcome,
            boolean restartRequired,
            boolean persisted,
            String overridesPath,
            OperationResult profile,
            OperationResult database,
            OperationResult kafka,
            List<String> messages,
            List<String> errors,
            ConfigSnapshot currentConfig) {
    }
}
