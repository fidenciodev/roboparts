package br.com.roboparts.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class CloudDatabaseMaintenanceTest {
    @Test void neonReceivesAnUnhashedGeneratedPasswordAndPostgresStoresScram() throws Exception {
        var connection=mock(Connection.class);
        var statement=mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        String synthetic="fixture-only-"+"x".repeat(48);
        CloudDatabaseMaintenance.setRuntimePassword(connection,synthetic);
        var order=inOrder(statement);
        order.verify(statement).execute("SET LOCAL password_encryption='scram-sha-256'");
        order.verify(statement).execute("ALTER ROLE roboparts_web PASSWORD '"+synthetic+"'");
        order.verify(statement).close();
        verifyNoMoreInteractions(statement);
    }

    @Test void untrustedPasswordCannotBecomeRoleManagementSql() {
        var connection=mock(Connection.class);
        for(String input:new String[]{"x".repeat(48)+"'; SELECT 1; --","x".repeat(42),"x".repeat(129)}) {
            assertThatIllegalArgumentException().isThrownBy(()->CloudDatabaseMaintenance.setRuntimePassword(connection,input));
        }
        verifyNoInteractions(connection);
    }

    @Test void diagnosticsNeverContainExceptionMessagesUrlsOrCredentials() {
        var failure=new IllegalStateException("jdbc:postgresql://private-host/secret-password",
                new SQLException("ALTER ROLE PASSWORD 'private-password'","42501"));
        assertThat(CloudDatabaseMaintenance.diagnostic(CloudDatabaseMaintenance.Stage.ROLE_PASSWORD,failure))
                .isEqualTo("stage=ROLE_PASSWORD\ncategory=SQL\nsqlState=42501\n");
        var tls=new SQLException("secret-password","08006",new SSLHandshakeException("private-host"));
        assertThat(CloudDatabaseMaintenance.diagnostic(CloudDatabaseMaintenance.Stage.OWNER_CONNECTION,tls))
                .isEqualTo("stage=OWNER_CONNECTION\ncategory=TLS\nsqlState=08006\n");
        var unexpected=new SQLException("secret-password","sensitive value");
        assertThat(CloudDatabaseMaintenance.diagnostic(CloudDatabaseMaintenance.Stage.ROLE_CREATE,unexpected))
                .isEqualTo("stage=ROLE_CREATE\ncategory=SQL\nsqlState=NONE\n");
    }
}
