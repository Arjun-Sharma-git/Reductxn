package io.reductxn.platform.persistence;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * The DataSource, configured for the pooler that is in front of it.
 *
 * <p><strong>PgBouncer changes what a connection can be trusted to hold.</strong> In transaction pooling
 * mode a connection is returned to the pool between transactions, so anything server-side and
 * session-scoped is not there when the next transaction picks the connection up. The sharp edge is
 * server-side prepared statements: the driver prepares a statement, hands the name to the server, and the
 * next statement on that pooled connection can arrive at a server that has never heard of it. The failure
 * is intermittent, load-dependent, and looks like a database problem rather than a configuration one.
 *
 * <p>PgBouncer is in the local compose stack on purpose - "developers should feel the constraint early" -
 * so this is not a production-only concern. It is set here rather than in a JDBC URL because a URL is
 * assembled from environment-specific pieces, and a setting that has to survive three of them is a
 * setting that eventually gets dropped from one.
 *
 * <p>Schema migrations deliberately do <em>not</em> run through this DataSource. DDL on a pooled
 * connection is the same problem with worse consequences, so {@code MigrationsConfiguration} points
 * Flyway at the direct URL.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfiguration {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();

        // Server-side prepared statements are not safe behind a transaction-pooling pooler. `0` disables
        // the threshold, so the driver always uses a client-side prepare.
        dataSource.addDataSourceProperty("prepareThreshold", "0");

        // Batched inserts are rewritten client-side for the same reason: a rewritten batch is a single
        // server-side statement, which is exactly what the pooler cannot be relied on to remember.
        dataSource.addDataSourceProperty("reWriteBatchedInserts", "true");

        return dataSource;
    }

    /** Exposed separately so Flyway and the application can use different URLs. See above. */
    @Bean
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /** Marks the DataSource as the one the application uses, so autoconfiguration does not build another. */
    @Bean
    DataSource reductxnDataSource(HikariDataSource dataSource) {
        return dataSource;
    }
}
