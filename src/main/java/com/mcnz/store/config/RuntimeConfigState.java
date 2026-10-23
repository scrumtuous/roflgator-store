package com.mcnz.store.config;

import java.util.ArrayList;
import java.util.Objects;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class RuntimeConfigState {

    private String databaseUrl;
    private String databaseUsername;
    private String databasePassword;
    private String databaseDriverClassName;
    private String kafkaBootstrapServers;
    private String desiredProfile;

    public RuntimeConfigState(Environment environment) {
        this.databaseUrl = property(environment, "spring.datasource.url", "");
        this.databaseUsername = property(environment, "spring.datasource.username", "");
        this.databasePassword = property(environment, "spring.datasource.password", "");
        this.databaseDriverClassName = property(environment, "spring.datasource.driver-class-name", "org.postgresql.Driver");
        this.kafkaBootstrapServers = property(environment, "spring.kafka.bootstrap-servers", "");
        this.desiredProfile = resolveProfile(environment);
    }

    public synchronized Snapshot snapshot(Environment environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        String[] activeProfiles = environment.getActiveProfiles();
        String[] defaultProfiles = environment.getDefaultProfiles();
        boolean databasePasswordBlank = databasePassword == null || databasePassword.isBlank();
        java.util.List<String> warnings = new ArrayList<>();
        if (databasePasswordBlank) {
            warnings.add("Database password is blank. Connections can fail in secured environments.");
        }
        return new Snapshot(
                csv(activeProfiles),
                csv(defaultProfiles),
                desiredProfile,
                databaseDriverClassName,
                databaseUrl,
                databaseUsername,
                mask(databasePassword),
                databasePasswordBlank,
                parseJdbcLocation(databaseUrl),
                kafkaBootstrapServers,
                isLocalKafka(kafkaBootstrapServers),
                StoreKafkaTopics.all(),
                warnings);
    }

    public synchronized DatabaseSettings databaseSettings() {
        return new DatabaseSettings(databaseDriverClassName, databaseUrl, databaseUsername, databasePassword);
    }

    public synchronized String kafkaBootstrapServers() {
        return kafkaBootstrapServers;
    }

    public synchronized String desiredProfile() {
        return desiredProfile;
    }

    public synchronized void updateDatabase(String url, String username, String password) {
        this.databaseUrl = safe(url);
        this.databaseUsername = safe(username);
        this.databasePassword = safe(password);
        this.databaseDriverClassName = "org.postgresql.Driver";
    }

    public synchronized void updateKafkaBootstrapServers(String bootstrapServers) {
        this.kafkaBootstrapServers = safe(bootstrapServers);
    }

    public synchronized void updateDesiredProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return;
        }
        this.desiredProfile = profile.trim();
    }

    public static JdbcLocation parseJdbcLocation(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return new JdbcLocation("", -1, "", jdbcUrl == null ? "" : jdbcUrl);
        }

        String trimmed = jdbcUrl.trim();
        String withoutPrefix = trimmed;
        int prefix = withoutPrefix.indexOf("://");
        if (prefix >= 0) {
            withoutPrefix = withoutPrefix.substring(prefix + 3);
        }

        String hostPortAndPath = withoutPrefix;
        int querySeparator = hostPortAndPath.indexOf('?');
        if (querySeparator >= 0) {
            hostPortAndPath = hostPortAndPath.substring(0, querySeparator);
        }

        String hostPort;
        String database = "";
        int slash = hostPortAndPath.indexOf('/');
        if (slash >= 0) {
            hostPort = hostPortAndPath.substring(0, slash);
            database = hostPortAndPath.substring(slash + 1);
        } else {
            hostPort = hostPortAndPath;
        }

        String host = hostPort;
        int port = -1;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && colon < hostPort.length() - 1) {
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                port = -1;
            }
        }

        return new JdbcLocation(host, port, database, trimmed);
    }

    public static boolean isLocalKafka(String bootstrapServers) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            return false;
        }
        String[] servers = bootstrapServers.split(",");
        for (String server : servers) {
            String trimmed = server.trim().toLowerCase();
            if (trimmed.startsWith("localhost") || trimmed.startsWith("127.0.0.1")
                    || trimmed.startsWith("0.0.0.0") || trimmed.startsWith("host.docker.internal")) {
                return true;
            }
        }
        return false;
    }

    private static String resolveProfile(Environment environment) {
        String explicit = environment.getProperty("spring.profiles.active");
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        String[] active = environment.getActiveProfiles();
        if (active.length > 0) {
            return active[0];
        }
        String configuredDefault = environment.getProperty("spring.profiles.default");
        if (configuredDefault != null && !configuredDefault.isBlank()) {
            return configuredDefault.trim();
        }
        String[] defaults = environment.getDefaultProfiles();
        if (defaults.length > 0) {
            return defaults[0];
        }
        return "default";
    }

    private static String property(Environment environment, String key, String fallback) {
        String value = environment.getProperty(key);
        if (value == null) {
            return fallback;
        }
        return value;
    }

    private static String safe(String input) {
        return input == null ? "" : input.trim();
    }

    private static String csv(String[] values) {
        return values == null || values.length == 0 ? "" : String.join(",", values);
    }

    private static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "(empty)";
        }
        return "*".repeat(Math.min(secret.length(), 12));
    }

    public record DatabaseSettings(String driverClassName, String url, String username, String password) {
    }

    public record JdbcLocation(String host, int port, String database, String jdbcUrl) {
    }

    public record Snapshot(
            String activeProfiles,
            String defaultProfiles,
            String desiredProfile,
            String databaseDriverClassName,
            String databaseUrl,
            String databaseUsername,
            String databasePasswordMasked,
            boolean databasePasswordBlank,
            JdbcLocation databaseLocation,
            String kafkaBootstrapServers,
            boolean kafkaLocal,
            java.util.List<String> topicsInUse,
            java.util.List<String> warnings) {
    }
}
