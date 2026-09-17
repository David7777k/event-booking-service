package io.github.david7777k.seatflow;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Statement;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class SeatflowApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoadsAndMigrationsApply() throws Exception {
        try (var connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     select count(*) from information_schema.tables
                     where table_schema = 'public'
                       and table_name in ('app_user','venue','seat','event','booking','event_seat')
                     """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(6);
        }
    }
}
