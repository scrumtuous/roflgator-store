package com.mcnz.store;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

import com.mcnz.store.config.ContainerConfigFileService;
import com.mcnz.store.config.RuntimeConfigState;
import com.mcnz.store.data.StorePurchasesData;
import com.mcnz.store.messaging.FakeKafka;
import com.mcnz.store.messaging.StoreProducers;
import com.mcnz.store.service.StoreService;

@SpringBootApplication
public class App implements CommandLineRunner {

    private static final String ADDITIONAL_LOCATION_KEY = "spring.config.additional-location";
    private static final Logger log = LoggerFactory.getLogger(App.class);

    @Autowired
    private StoreProducers producers;

    @Autowired
    private StoreService storeService;

    @Autowired
    private Environment environment;

    @Autowired
    private RuntimeConfigState runtimeConfigState;

    public static void main(String[] args) {
        Path overridesPath = prepareOverridesFile();
        registerOverridesLocation(overridesPath);

        if (shouldStartEmbeddedKafka(overridesPath, args)) {
            FakeKafka.start();
        }

        SpringApplication.run(App.class, args);
    }

    @Override
    public void run(String... args) {
        logStartupDiagnostics();
        for (String[] row : StorePurchasesData.ROWS) {
            // Refunds are left for a separate lesson.
            if ("PURCHASE".equals(row[0])) {
                Purchase purchase = new Purchase(
                    LocalDate.parse(row[1]),
                    new Customer(row[2], new Address(row[3], row[4]))
                );
                purchase.addProduct(new Product(row[5], Integer.parseInt(row[6]), Double.parseDouble(row[7])));
                Purchase pendingPurchase = storeService.createPendingPurchase(purchase);
                producers.submit(pendingPurchase.id);
            }
        }
        System.out.println("Purchases submitted. Kafka listeners will process them.");
    }

    private void logStartupDiagnostics() {
        RuntimeConfigState.Snapshot snapshot = runtimeConfigState.snapshot(environment);

        log.info("Startup diagnostics: activeProfiles='{}', defaultProfiles='{}', desiredProfile='{}'",
                blankAs(snapshot.activeProfiles(), "(none)"),
                blankAs(snapshot.defaultProfiles(), "(none)"),
                blankAs(snapshot.desiredProfile(), "(none)"));

        log.info("Startup diagnostics: jdbcDriver='{}', dbUrl='{}', dbHost='{}', dbPort='{}', dbName='{}', dbUser='{}'",
                blankAs(snapshot.databaseDriverClassName(), "(unset)"),
                blankAs(snapshot.databaseUrl(), "(unset)"),
                blankAs(snapshot.databaseLocation().host(), "(unknown)"),
                snapshot.databaseLocation().port() > 0 ? snapshot.databaseLocation().port() : "(unknown)",
                blankAs(snapshot.databaseLocation().database(), "(unknown)"),
                blankAs(snapshot.databaseUsername(), "(unset)"));

        if (snapshot.databasePasswordBlank()) {
            log.warn("Startup warning: database password is blank. Configure spring.datasource.password for secured environments.");
        } else {
            log.info("Startup diagnostics: database password is configured (value masked).");
        }

        log.info("Startup diagnostics: kafkaBootstrapServers='{}', kafkaMode='{}', topics='{}'",
                blankAs(snapshot.kafkaBootstrapServers(), "(unset)"),
                snapshot.kafkaLocal() ? "LOCAL" : "REMOTE",
                String.join(", ", snapshot.topicsInUse()));

        if (!snapshot.warnings().isEmpty()) {
            for (String warning : snapshot.warnings()) {
                log.warn("Startup warning: {}", warning);
            }
        }

        log.info("Startup diagnostics: adminOverrides='{}', appLog='runtime/app.log'",
                ContainerConfigFileService.OVERRIDES_RELATIVE_PATH);
    }

    private static Path prepareOverridesFile() {
        Path overridesPath = Paths.get(ContainerConfigFileService.OVERRIDES_RELATIVE_PATH).toAbsolutePath().normalize();
        try {
            Path parent = overridesPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(overridesPath)) {
                Files.createFile(overridesPath);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to prepare config overrides file: " + overridesPath, exception);
        }
        return overridesPath;
    }

    private static void registerOverridesLocation(Path overridesPath) {
        String location = "optional:file:" + overridesPath.toString().replace('\\', '/');
        String existing = System.getProperty(ADDITIONAL_LOCATION_KEY);

        if (existing == null || existing.isBlank()) {
            System.setProperty(ADDITIONAL_LOCATION_KEY, location);
            return;
        }

        if (!existing.contains(location)) {
            System.setProperty(ADDITIONAL_LOCATION_KEY, existing + "," + location);
        }
    }

    private static boolean shouldStartEmbeddedKafka(Path overridesPath, String[] args) {
        String active = firstNonBlank(
                extractActiveProfileFromArgs(args),
                System.getProperty("spring.profiles.active"),
                System.getenv("SPRING_PROFILES_ACTIVE"),
                readOverridesProfile(overridesPath),
                "aws");

        String explicitBootstrapServers = firstNonBlank(
                extractKafkaBootstrapFromArgs(args),
                System.getProperty("spring.kafka.bootstrap-servers"),
                System.getenv("SPRING_KAFKA_BOOTSTRAP_SERVERS"),
                readOverridesProperty(overridesPath, "spring.kafka.bootstrap-servers"));

        String[] profiles = active.split(",");
        for (String profile : profiles) {
            if ("dev".equalsIgnoreCase(profile.trim())) {
                return explicitBootstrapServers.isBlank();
            }
        }
        return false;
    }

    private static String readOverridesProfile(Path overridesPath) {
        return readOverridesProperty(overridesPath, "spring.profiles.active");
    }

    private static String readOverridesProperty(Path overridesPath, String key) {
        if (!Files.exists(overridesPath)) {
            return "";
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(overridesPath)) {
            properties.load(input);
            return properties.getProperty(key, "");
        } catch (Exception exception) {
            return "";
        }
    }

    private static String extractActiveProfileFromArgs(String[] args) {
        return extractSpringArg(args, "spring.profiles.active");
    }

    private static String extractKafkaBootstrapFromArgs(String[] args) {
        return extractSpringArg(args, "spring.kafka.bootstrap-servers");
    }

    private static String extractSpringArg(String[] args, String key) {
        if (args == null) {
            return "";
        }
        String prefix = "--" + key + "=";
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String blankAs(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}