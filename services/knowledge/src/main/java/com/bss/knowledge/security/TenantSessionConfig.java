package com.bss.knowledge.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Second lock on the tenant door: every pooled connection carries the acting
 * tenant in the app.tenant_id session variable, which the row-level-security
 * policies compare against each row. Set on checkout, RESET on checkin, so a
 * connection can never leak one request's tenant into the next. Postgres
 * only — H2 test runs get the plain datasource (no RLS there). Flyway is
 * deliberately NOT wrapped: migrations run as the owning role.
 */
@Configuration
public class TenantSessionConfig {

    @Bean
    static BeanPostProcessor tenantSessionDataSourceWrapper(ObjectProvider<TenantScope> tenantScope) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(dataSource, tenantScope);
                }
                return bean;
            }
        };
    }

    static class TenantAwareDataSource extends DelegatingDataSource {

        private final ObjectProvider<TenantScope> tenantScope;
        private volatile Boolean postgres;

        TenantAwareDataSource(DataSource target, ObjectProvider<TenantScope> tenantScope) {
            super(target);
            this.tenantScope = tenantScope;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return prepared(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return prepared(super.getConnection(username, password));
        }

        private Connection prepared(Connection connection) throws SQLException {
            if (!isPostgres(connection)) {
                return connection;
            }
            String tenant = TenantContext.current();
            if (tenant == null) {
                TenantScope scope = tenantScope.getIfAvailable();
                tenant = scope != null ? scope.currentTenantId() : null;
            }
            if (tenant == null) {
                return connection;
            }
            // BOUND, NOT BUILT. This used to concatenate the tenant id into a SET
            // statement and escape quotes by doubling them. That was almost certainly
            // safe — ids come from our own registry — but "almost certainly safe,
            // argued in a comment" is the wrong standard for the one statement the
            // whole row-level-security wall stands on, and CodeQL flagged it as
            // java/sql-injection in all 36 services that carry this file.
            //
            // set_config() is the parameterised form of SET, so the value is bound by
            // the driver and can no longer be read as SQL whatever it contains. The
            // third argument is false, meaning session scope — identical to SET.
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                statement.setString(1, tenant);
                statement.execute();
            }
            return resettingProxy(connection);
        }

        private boolean isPostgres(Connection connection) throws SQLException {
            Boolean known = postgres;
            if (known == null) {
                known = "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
                postgres = known;
            }
            return known;
        }

        /** RESET the session variable when the pool takes the connection back. */
        private Connection resettingProxy(Connection connection) {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("close".equals(method.getName())) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("RESET app.tenant_id");
                    } catch (SQLException ignored) {
                        // connection is going back broken; the pool will discard it
                    }
                }
                try {
                    return method.invoke(connection, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, handler);
        }
    }
}
