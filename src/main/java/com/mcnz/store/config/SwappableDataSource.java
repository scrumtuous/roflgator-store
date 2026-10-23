package com.mcnz.store.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.AbstractDataSource;

public class SwappableDataSource extends AbstractDataSource {

    private final AtomicReference<DataSource> delegate;

    public SwappableDataSource(DataSource initialDataSource) {
        this.delegate = new AtomicReference<>(Objects.requireNonNull(initialDataSource, "initialDataSource must not be null"));
    }

    @Override
    public Connection getConnection() throws SQLException {
        return delegate.get().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return delegate.get().getConnection(username, password);
    }

    public synchronized void swap(DataSource nextDataSource) {
        DataSource previous = delegate.getAndSet(Objects.requireNonNull(nextDataSource, "nextDataSource must not be null"));
        closeIfPossible(previous);
    }

    public DataSource current() {
        return delegate.get();
    }

    private void closeIfPossible(DataSource dataSource) {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Cleanup failure should not block switching to a known-good data source.
            }
        }
    }
}
