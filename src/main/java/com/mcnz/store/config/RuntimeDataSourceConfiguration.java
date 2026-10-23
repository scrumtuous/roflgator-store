package com.mcnz.store.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class RuntimeDataSourceConfiguration {

    @Bean
    @Primary
    public SwappableDataSource dataSource(RuntimeConfigState runtimeConfigState) {
        RuntimeConfigState.DatabaseSettings databaseSettings = runtimeConfigState.databaseSettings();
        HikariDataSource initial = buildDataSource(
                databaseSettings.url(),
                databaseSettings.username(),
                databaseSettings.password(),
                databaseSettings.driverClassName(),
                "store-primary");
        return new SwappableDataSource(initial);
    }

    public static HikariDataSource buildDataSource(
            String url,
            String username,
            String password,
            String driverClassName,
            String poolName) {

        HikariConfig config = new HikariConfig();
        config.setDriverClassName(driverClassName);
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password == null ? "" : password);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(3000);
        config.setInitializationFailTimeout(5000);
        config.setMaximumPoolSize(6);
        config.setMinimumIdle(0);
        config.setPoolName(poolName);
        return new HikariDataSource(config);
    }
}