package tech.migueldev.coffeewarehouse;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CoffeeWarehouseApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("context starts and Flyway applies the migrations")
    void contextLoadsAndMigrationsApplied() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM flyway_schema_history WHERE success = true")) {

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isPositive();
        }
    }

    @Test
    @DisplayName("baseline tables exist in the schema")
    void baselineTablesExist() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema = 'public'
                     """)) {

            var tables = new java.util.ArrayList<String>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            assertThat(tables).contains("producer", "warehouse", "storage_position", "lot");
        }
    }
}
