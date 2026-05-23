import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DbManager {
    private static final String DB_URL = "jdbc:sqlite:delta_memory.db";

    public DbManager() {
        initializeDatabase();
    }

    private void initializeDatabase() {
        String createTableSql = """
            CREATE TABLE IF NOT EXISTS state_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_id TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                previous_transaction_id TEXT,
                external_delta TEXT NOT NULL,
                internal_state TEXT NOT NULL,
                output_delta TEXT NOT NULL
            );
        """;
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSql);
        } catch (SQLException e) {
            System.err.println("Database Initialization Error: " + e.getMessage());
        }
    }

    public void saveTransaction(StateTransaction tx) {
        String insertSql = "INSERT INTO state_transactions (transaction_id, timestamp, previous_transaction_id, external_delta, internal_state, output_delta) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            pstmt.setString(1, tx.transactionId().toString());
            pstmt.setString(2, tx.timestamp().toString());
            pstmt.setString(3, tx.previousTransactionId() == null ? null : tx.previousTransactionId().toString());
            pstmt.setString(4, tx.externalDelta());
            pstmt.setString(5, tx.internalState());
            pstmt.setString(6, tx.outputDelta());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Save Transaction Error: " + e.getMessage());
        }
    }

    public StateTransaction loadLatestTransaction() {
        String selectSql = "SELECT * FROM state_transactions ORDER BY id DESC LIMIT 1";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql)) {

            if (rs.next()) {
                return new StateTransaction(
                        java.util.UUID.fromString(rs.getString("transaction_id")),
                        java.time.Instant.parse(rs.getString("timestamp")),
                        rs.getString("previous_transaction_id") == null ? null : java.util.UUID.fromString(rs.getString("previous_transaction_id")),
                        rs.getString("external_delta"),
                        rs.getString("internal_state"),
                        rs.getString("output_delta")
                );
            }
        } catch (SQLException e) {
            System.err.println("Load Transaction Error: " + e.getMessage());
        }
        return null;
    }

    public java.util.List<StateTransaction> searchMemories(String keyword, int limit) {
        java.util.List<StateTransaction> memories = new java.util.ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) return memories;

        // 外部入力または内部ステートにキーワードが含まれる過去の記憶を抽出
        String sql = "SELECT * FROM state_transactions WHERE external_delta LIKE ? OR internal_state LIKE ? ORDER BY id DESC LIMIT ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            String searchPattern = "%" + keyword + "%";
            pstmt.setString(1, searchPattern);
            pstmt.setString(2, searchPattern);
            pstmt.setInt(3, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    memories.add(new StateTransaction(
                            java.util.UUID.fromString(rs.getString("transaction_id")),
                            java.time.Instant.parse(rs.getString("timestamp")),
                            rs.getString("previous_transaction_id") == null ? null : java.util.UUID.fromString(rs.getString("previous_transaction_id")),
                            rs.getString("external_delta"),
                            rs.getString("internal_state"),
                            rs.getString("output_delta")
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("Memory Search Error: " + e.getMessage());
        }
        return memories;
    }
}