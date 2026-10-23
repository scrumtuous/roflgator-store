package com.mcnz.store.config;

import java.io.PrintStream;

import org.springframework.boot.Banner;
import org.springframework.core.env.Environment;

public class TroubleshootingBanner implements Banner {

    @Override
    public void printBanner(Environment environment, Class<?> sourceClass, PrintStream out) {
        String activeProfiles = profiles(environment.getActiveProfiles());
        String defaultProfiles = profiles(environment.getDefaultProfiles());
        String desiredProfile = firstNonBlank(environment.getProperty("spring.profiles.active"), environment.getProperty("spring.profiles.default"), "default");
        String dbDriver = environment.getProperty("spring.datasource.driver-class-name", "(unset)");
        String dbUrl = environment.getProperty("spring.datasource.url", "(unset)");
        String dbUser = environment.getProperty("spring.datasource.username", "(unset)");
        RuntimeConfigState.JdbcLocation dbLocation = RuntimeConfigState.parseJdbcLocation(dbUrl);
        String kafkaServers = environment.getProperty("spring.kafka.bootstrap-servers", "(unset)");
        boolean localKafka = RuntimeConfigState.isLocalKafka(kafkaServers);

        out.println("============================================================");
        out.println("  Simple Kafka Store - Troubleshooting Banner");
        out.println("============================================================");
        out.println("  Active profiles : " + blankAs(activeProfiles, "(none)"));
        out.println("  Default profiles: " + blankAs(defaultProfiles, "(none)"));
        out.println("  Desired profile : " + desiredProfile);
        out.println("  JDBC driver     : " + dbDriver);
        out.println("  JDBC url        : " + dbUrl);
        out.println("  DB host/port    : " + blankAs(dbLocation.host(), "(unknown)") + ":" + (dbLocation.port() > 0 ? dbLocation.port() : "(unknown)"));
        out.println("  DB name         : " + blankAs(dbLocation.database(), "(unknown)"));
        out.println("  DB user         : " + dbUser);
        out.println("  Kafka servers   : " + kafkaServers);
        out.println("  Kafka mode      : " + (localKafka ? "LOCAL" : "REMOTE"));
        out.println("  Topics in use   : " + String.join(", ", StoreKafkaTopics.all()));
        out.println("  Persisted config: " + ContainerConfigFileService.OVERRIDES_RELATIVE_PATH);
        out.println("  File logs       : runtime/app.log");
        out.println("============================================================");
    }

    private String profiles(String[] profiles) {
        if (profiles == null || profiles.length == 0) {
            return "";
        }
        return String.join(",", profiles);
    }

    private String firstNonBlank(String primary, String secondary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return fallback;
    }

    private String blankAs(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
