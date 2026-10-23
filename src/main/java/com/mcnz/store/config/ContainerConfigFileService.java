package com.mcnz.store.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Properties;

import org.springframework.stereotype.Service;

@Service
public class ContainerConfigFileService {

    public static final String OVERRIDES_RELATIVE_PATH = "runtime/admin-overrides.properties";

    private final Path overridesPath;

    public ContainerConfigFileService() {
        this.overridesPath = Paths.get(OVERRIDES_RELATIVE_PATH).toAbsolutePath().normalize();
    }

    public synchronized void ensureFileExists() {
        try {
            Path parent = overridesPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(overridesPath)) {
                Files.createFile(overridesPath);
            }
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to prepare overrides file at " + overridesPath, ioException);
        }
    }

    public synchronized void persistOverrides(
            String profile,
            String databaseUrl,
            String databaseUsername,
            String databasePassword,
            String kafkaBootstrapServers) {

        ensureFileExists();
        Properties properties = loadProperties();

        setOrRemove(properties, "spring.profiles.active", profile);
        setOrRemove(properties, "spring.datasource.url", databaseUrl);
        setOrRemove(properties, "spring.datasource.username", databaseUsername);
        setOrRemove(properties, "spring.datasource.password", databasePassword);
        properties.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
        setOrRemove(properties, "spring.kafka.bootstrap-servers", kafkaBootstrapServers);
        properties.setProperty("admin.overrides.last-updated", Instant.now().toString());

        storeProperties(properties);
    }

    public synchronized Properties readOverrides() {
        ensureFileExists();
        return loadProperties();
    }

    public synchronized String absolutePath() {
        return overridesPath.toString();
    }

    private Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(overridesPath)) {
            properties.load(input);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to read overrides file at " + overridesPath, ioException);
        }
        return properties;
    }

    private void storeProperties(Properties properties) {
        try (OutputStream output = Files.newOutputStream(overridesPath)) {
            properties.store(output, "Container-local admin overrides");
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to write overrides file at " + overridesPath, ioException);
        }
    }

    private void setOrRemove(Properties properties, String key, String value) {
        if (value == null || value.isBlank()) {
            properties.remove(key);
            return;
        }
        properties.setProperty(key, value.trim());
    }
}
