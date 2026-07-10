package com.bss.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the row-level-security policies on the real engine, as the real
 * restricted role. This is the defense-in-depth guarantee: even SQL with no
 * tenant predicate at all cannot cross tenants once app.tenant_id is set —
 * and sees NOTHING when it is not set. The identical migration runs in every
 * service, so this pilot covers the mechanics for all of them.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RlsPolicyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    private Connection appConnection() throws Exception {
        // The catalog_app role created by the RLS migration, not the owner.
        return DriverManager.getConnection(postgres.getJdbcUrl(), "catalog_app", "catalog_app");
    }

    private int countVisible(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM product_offering")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void policiesConfineTheAppRole_evenWithoutAnyPredicate() throws Exception {
        // Seed two tenants' rows as the owner (bypasses RLS, like Flyway would).
        jdbc.update("INSERT INTO product_offering (id, name, tenant_id) VALUES ('rls-a', 'A plan', 'tenant-a')");
        jdbc.update("INSERT INTO product_offering (id, name, tenant_id) VALUES ('rls-b', 'B plan', 'tenant-b')");

        try (Connection con = appConnection(); Statement st = con.createStatement()) {
            // No session tenant: the app role sees nothing at all.
            assertThat(countVisible(st)).isZero();

            // Acting as tenant-a: exactly tenant-a's row, from predicate-free SQL.
            st.execute("SET app.tenant_id = 'tenant-a'");
            assertThat(countVisible(st)).isEqualTo(1);

            // Writes across the fence are rejected by WITH CHECK.
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    st.execute("INSERT INTO product_offering (id, name, tenant_id) "
                            + "VALUES ('rls-x', 'smuggled', 'tenant-b')"))
                    .hasMessageContaining("row-level security");

            // Cross-tenant UPDATE silently matches nothing rather than leaking.
            assertThat(st.executeUpdate(
                    "UPDATE product_offering SET name = 'hijacked' WHERE id = 'rls-b'")).isZero();

            // The system escape hatch sees both tenants (sweeper-style jobs).
            st.execute("SET app.tenant_id = '__system__'");
            assertThat(countVisible(st)).isEqualTo(2);
        } finally {
            jdbc.update("DELETE FROM product_offering WHERE id IN ('rls-a', 'rls-b')");
        }
    }
}
