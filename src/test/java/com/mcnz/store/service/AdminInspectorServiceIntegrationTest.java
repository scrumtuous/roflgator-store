package com.mcnz.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.mock.env.MockEnvironment;

import com.mcnz.store.config.ContainerConfigFileService;
import com.mcnz.store.config.RuntimeConfigState;
import com.mcnz.store.config.RuntimeDataSourceConfiguration;
import com.mcnz.store.config.SwappableDataSource;
import com.mcnz.store.messaging.StoreKafkaTopics;

class AdminInspectorServiceIntegrationTest {

    private static EmbeddedKafkaKraftBroker kafkaBroker;

    @BeforeAll
    static void startKafka() {
        kafkaBroker = new EmbeddedKafkaKraftBroker(1, 1, StoreKafkaTopics.VALIDATE, StoreKafkaTopics.PROCESS);
        kafkaBroker.afterPropertiesSet();
    }

    @AfterAll
    static void stopKafka() {
        if (kafkaBroker != null) {
            kafkaBroker.destroy();
        }
    }

    @Test
    void applyConfigHotSwitchKafkaSucceedsWhenNewTargetIsReachable() {
        String currentBootstrap = firstBroker();

        try (TestHarness harness = createHarness(currentBootstrap)) {
            String targetBootstrap = findReachableChangedBootstrap(harness.service, currentBootstrap);
            assertNotNull(targetBootstrap, "Expected a reachable alternate bootstrap server value for hot-switch test");

            AdminInspectorService.ApplyConfigResult result = harness.service.applyConfig(new AdminInspectorService.ApplyConfigCommand(
                    "dev",
                    null,
                    null,
                    null,
                    targetBootstrap,
                    true,
                    false));

            assertEquals("HOT_SWITCH_APPLIED", result.outcome());
            assertFalse(result.restartRequired());
            assertTrue(result.errors().isEmpty());
            assertTrue(result.kafka().success());
            assertTrue(result.kafka().changed());
            assertEquals(targetBootstrap, result.currentConfig().runtime().kafkaBootstrapServers());
        }
    }

    @Test
    void applyConfigHotSwitchKafkaFailsWhenTargetIsUnreachable() {
        String currentBootstrap = firstBroker();

        try (TestHarness harness = createHarness(currentBootstrap)) {
            AdminInspectorService.ApplyConfigResult result = harness.service.applyConfig(new AdminInspectorService.ApplyConfigCommand(
                    "dev",
                    null,
                    null,
                    null,
                    "127.0.0.1:1",
                    true,
                    false));

            assertEquals("HOT_SWITCH_FAILED", result.outcome());
            assertTrue(result.restartRequired());
            assertFalse(result.kafka().success());
            assertTrue(result.kafka().restartRequired());
            assertTrue(result.errors().stream().anyMatch(message -> message.contains("Kafka hot switch failed")));
            assertEquals(currentBootstrap, result.currentConfig().runtime().kafkaBootstrapServers());
        }
    }

    @Test
    void applyConfigHotSwitchDatabaseFailsWhenTargetIsUnreachable() {
        String currentBootstrap = firstBroker();

        try (TestHarness harness = createHarness(currentBootstrap)) {
            AdminInspectorService.ApplyConfigResult result = harness.service.applyConfig(new AdminInspectorService.ApplyConfigCommand(
                    "dev",
                    "jdbc:postgresql://127.0.0.1:1/missing",
                    "baduser",
                    "badpassword",
                    null,
                    true,
                    false));

            assertEquals("HOT_SWITCH_FAILED", result.outcome());
            assertTrue(result.restartRequired());
            assertFalse(result.database().success());
            assertTrue(result.database().restartRequired());
            assertTrue(result.errors().stream().anyMatch(message -> message.contains("Database hot switch failed")));
        }
    }

    private TestHarness createHarness(String bootstrapServers) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("spring.profiles.active", "dev");
        environment.setProperty("spring.datasource.url", "jdbc:h2:mem:admin_inspector_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        environment.setProperty("spring.datasource.username", "sa");
        environment.setProperty("spring.datasource.password", "");
        environment.setProperty("spring.datasource.driver-class-name", "org.h2.Driver");
        environment.setProperty("spring.kafka.bootstrap-servers", bootstrapServers);

        RuntimeConfigState runtimeConfigState = new RuntimeConfigState(environment);
        SwappableDataSource swappableDataSource = new RuntimeDataSourceConfiguration().dataSource(runtimeConfigState);

        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, LongSerializer.class);

        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class);
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "admin-inspector-tests-" + UUID.randomUUID());
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        DefaultKafkaProducerFactory<String, Long> producerFactory = new DefaultKafkaProducerFactory<>(producerConfig);
        DefaultKafkaConsumerFactory<String, Long> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerConfig);

        KafkaListenerEndpointRegistry listenerRegistry = new KafkaListenerEndpointRegistry();
        ContainerConfigFileService containerConfigFileService = new ContainerConfigFileService();

        AdminInspectorService service = new AdminInspectorService(
                runtimeConfigState,
                swappableDataSource,
                producerFactory,
                consumerFactory,
                listenerRegistry,
                environment,
                containerConfigFileService);

        return new TestHarness(service, producerFactory, consumerFactory, swappableDataSource);
    }

    private String firstBroker() {
        String brokers = kafkaBroker.getBrokersAsString();
        return brokers.split(",")[0].trim();
    }

    private String findReachableChangedBootstrap(AdminInspectorService service, String currentBootstrap) {
        List<String> candidates = new ArrayList<>();
        int port = extractPort(currentBootstrap);
        if (port > 0) {
            candidates.add("localhost:" + port);
            candidates.add("127.0.0.1:" + port);
        }
        candidates.add(currentBootstrap + "," + currentBootstrap);

        for (String candidate : candidates) {
            if (candidate.equals(currentBootstrap)) {
                continue;
            }
            if (service.inspectKafka(candidate).connected()) {
                return candidate;
            }
        }
        return null;
    }

    private int extractPort(String bootstrapServer) {
        int colon = bootstrapServer.lastIndexOf(':');
        if (colon <= 0 || colon + 1 >= bootstrapServer.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(bootstrapServer.substring(colon + 1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static final class TestHarness implements AutoCloseable {
        private final AdminInspectorService service;
        private final DefaultKafkaProducerFactory<String, Long> producerFactory;
        private final DefaultKafkaConsumerFactory<String, Long> consumerFactory;
        private final SwappableDataSource swappableDataSource;

        private TestHarness(
                AdminInspectorService service,
                DefaultKafkaProducerFactory<String, Long> producerFactory,
                DefaultKafkaConsumerFactory<String, Long> consumerFactory,
                SwappableDataSource swappableDataSource) {
            this.service = service;
            this.producerFactory = producerFactory;
            this.consumerFactory = consumerFactory;
            this.swappableDataSource = swappableDataSource;
        }

        @Override
        public void close() {
            producerFactory.destroy();
            if (swappableDataSource.current() instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
