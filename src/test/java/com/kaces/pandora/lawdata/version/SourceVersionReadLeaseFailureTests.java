package com.kaces.pandora.lawdata.version;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SourceVersionReadLeaseFailureTests {
    @Test void statementCleanupFailureCannotLeakAdmissionOrConnectionOwnership() throws Exception {
        // JDBC cleanup failure cannot be reliably induced in a healthy real DB; inject only that boundary.
        var pool=mock(DataSource.class);var connection=mock(Connection.class);
        var statement=mock(PreparedStatement.class);var result=mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true,false,true,false);
        when(result.getLong(1)).thenReturn(0L);
        doThrow(new SQLException("injected statement close failure")).doNothing().when(statement).close();
        var leases=new SourceVersionReadLeases(pool,1,Duration.ofMillis(20));
        assertThatThrownBy(leases::acquire).isInstanceOf(IllegalStateException.class);
        try(var next=leases.acquire()) {assertThat(next.revision()).isZero();}
    }
}
