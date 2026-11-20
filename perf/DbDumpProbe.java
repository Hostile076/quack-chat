import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** 直接查 quack.db，确认数据真的在 SQLite 表里 */
public class DbDumpProbe {

    public static void main(String[] args) throws Exception {
        String path = args.length == 0 ? "E:\\dsh_workspace\\sqlite-test\\quack.db" : args[0];
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement()) {
            String[] tables = {"accounts", "profiles", "friendships", "groups", "group_members",
                    "messages", "read_state", "friend_requests"};
            boolean ok = true;
            for (String table : tables) {
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    int count = rows.next() ? rows.getInt(1) : 0;
                    System.out.println("TABLE " + table + " = " + count);
                    if ("accounts".equals(table) && count < 3) {
                        ok = false;
                    }
                    if ("messages".equals(table) && count < 1) {
                        ok = false;
                    }
                }
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT username, nickname FROM accounts ORDER BY qc_id LIMIT 3")) {
                while (rows.next()) {
                    System.out.println("ACCOUNT " + rows.getString(1) + " / " + rows.getString(2));
                }
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT content FROM messages ORDER BY ts DESC LIMIT 1")) {
                if (rows.next()) {
                    System.out.println("LAST MESSAGE " + rows.getString(1));
                }
            }
            System.out.println(ok ? "DB OK" : "DB FAILED");
            System.exit(ok ? 0 : 1);
        }
    }
}
