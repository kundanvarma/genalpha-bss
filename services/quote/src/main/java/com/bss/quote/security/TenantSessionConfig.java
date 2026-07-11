package com.bss.quote.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
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
            try (Statement statement = connection.createStatement()) {
                // Tenant ids come from our own registry/config, never user input.
                statement.execute("SET app.tenant_id = '" + tenant.replace("'", "''") + "'");
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
