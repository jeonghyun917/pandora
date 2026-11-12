import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

class PriorConsultationActiveControl {
    public static void main(String[] args) throws Exception {
        String article = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        int start = article.indexOf("⑤"), end = article.indexOf("⑥", start);
        if (!article.startsWith("제13조(사전협의 신청)") || start < 0 || end < 0) {
            throw new IllegalArgumentException("Expected official Article 13 boundaries");
        }
        String key = compact(article.substring(start + 1, end));
        try (var db = DriverManager.getConnection(System.getenv("PANDORA_PROBE_URL"),
                 System.getenv("PANDORA_PROBE_USER"), System.getenv("PANDORA_PROBE_PASSWORD"));
             var stmt = db.createStatement()) {
            stmt.setQueryTimeout(5);
            stmt.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
            try (var rows = stmt.executeQuery("""
                SELECT d.external_id,c.chunk_id,c.chunk_text,c.index_status
                  FROM law_api_documents d JOIN law_api_document_chunks c ON c.document_id=d.document_id
                 WHERE d.target='admrul' AND d.external_id IN ('2100000270130','2100000287088')
                   AND d.effective_status='CURRENT' AND d.use_yn='Y'
                   AND c.activation_status='ACTIVE' AND c.use_yn='Y'
                   AND c.chunk_text LIKE '%신청 누락%'
                 ORDER BY d.external_id,c.chunk_id
                """)) {
                int n = 0;
                while (rows.next()) {
                    n++;
                    System.out.printf("edition=%s chunk=%d index=%s literal=%s%n", rows.getString(1),
                        rows.getLong(2), rows.getString(4), compact(rows.getString(3)).contains(key));
                }
                System.out.println("activeMatches=" + n);
            } finally { stmt.execute("ROLLBACK"); }
        }
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }
}
