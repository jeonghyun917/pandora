import java.nio.file.*;
import java.sql.*;
import tools.jackson.databind.ObjectMapper;

/** Uses the existing normal Atlas connection configuration; emits metadata only. */
public class AtlasReadOnlyScopeDiagnostic {
    public static void main(String[] args) throws Exception {
        var config = new ObjectMapper().readTree(Files.readString(Path.of(args[0])));
        String url = config.path("ATLAS_DB_URL").asText();
        if (!url.equals("jdbc:mariadb://127.0.0.1:3306/atlas_cms"))
            throw new IllegalStateException("Observed local Atlas scope changed; refusing connection");
        try (var db = DriverManager.getConnection(url, config.path("ATLAS_DB_USER").asText(),
                config.path("ATLAS_DB_PASSWORD").asText())) {
            db.setReadOnly(true);
            try (var statement = db.createStatement()) {
                statement.setQueryTimeout(5);
                String[] queries = args.length>1 ? new String[] {
                    "SELECT CURRENT_ROLE() AS active_role",
                    "SELECT GRANTEE,ROLE_NAME,IS_GRANTABLE,IS_DEFAULT FROM information_schema.APPLICABLE_ROLES"
                } : new String[] {
                    "SELECT DATABASE() AS logical_db, CURRENT_USER() AS authenticated_account, @@port AS server_port",
                    "SELECT ID,USER,HOST,DB,COMMAND FROM information_schema.PROCESSLIST WHERE USER=SUBSTRING_INDEX(CURRENT_USER(),'@',1)",
                    "SELECT GRANTEE,PRIVILEGE_TYPE,IS_GRANTABLE FROM information_schema.USER_PRIVILEGES",
                    "SELECT GRANTEE,TABLE_SCHEMA,PRIVILEGE_TYPE,IS_GRANTABLE FROM information_schema.SCHEMA_PRIVILEGES",
                    "SELECT GRANTEE,TABLE_SCHEMA,TABLE_NAME,PRIVILEGE_TYPE,IS_GRANTABLE FROM information_schema.TABLE_PRIVILEGES"
                };
                for (String query : queries) {
                    try (var rows = statement.executeQuery(query)) {
                        var metadata = rows.getMetaData();
                        while (rows.next()) {
                            var values = new java.util.LinkedHashMap<String,Object>();
                            for (int i=1;i<=metadata.getColumnCount();i++) values.put(metadata.getColumnLabel(i),rows.getObject(i));
                            System.out.println(new ObjectMapper().writeValueAsString(values));
                        }
                    }
                }
            }
        }
    }
}
