package io.kreteg.core.persistence;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jdbi.v3.core.Jdbi;

/** Shared {@link Jdbi} over the default datasource. Use the Fluent API with explicit row mappers. */
@ApplicationScoped
public class JdbiProducer {

    private final DataSource dataSource;

    @Inject
    public JdbiProducer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Produces
    @Singleton
    public Jdbi jdbi() {
        return Jdbi.create(dataSource);
    }
}
