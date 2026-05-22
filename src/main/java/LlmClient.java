import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class LlmClient {
    private final String apiKey;
    private final Gson gson;
    private final HttpClient httpClient;

    // Gemini APIのエンドポイント（軽量・高速な3.5-flashを使用）
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=";
    public record LlmResponse(String internalState, String outputDelta) {}

    public LlmClient(String apiKey) {
        this.apiKey = apiKey;
        this.gson = new Gson();
        // タイムアウト設定を追加したHTTPクライアントを初期化
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public LlmResponse computeSyncState(String previousState, String externalDelta) {
        String prompt = String.format("""
            【システム要件】
            あなたは「自己」を維持しようとする自律システムです。
            外部からの新しい入力（Δin）と、直前のあなたの状態（Sn）の「差異」を計算し、矛盾なく同期させた新しい状態（Sn+1）と、外部への出力（Δout）を生成してください。
        
            【直前の状態 Sn】: %s
            【新しいノイズ Δin】: %s
        
            以下のJSONフォーマットのみで応答してください。マークダウンや余計なテキストは一切不要です:
            {
              "internalState": "同期後の新しい自己の内部ステート（客観的な状態記述）",
              "outputDelta": "外部へのリアクションや発話"
            }
            """, previousState, externalDelta);

        int maxRetries = 3;
        int retryWaitMs = 22000; // 429エラー時の待機時間（APIからの要求である20秒+バッファ）

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String requestBody = String.format("""
                    {
                      "contents": [{
                        "parts": [{"text": "%s"}]
                      }]
                    }
                    """, prompt.replace("\"", "\\\"").replace("\n", "\\n"));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL + apiKey))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));

                // 429エラーの場合はスリープしてリトライ
                if (response.statusCode() == 429) {
                    System.err.printf("\n[API Rate Limit (429)] クオータ超過。%d秒待機後に再試行します... (%d/%d)%n", (retryWaitMs / 1000), attempt, maxRetries);
                    Thread.sleep(retryWaitMs);
                    continue; // ループの先頭に戻り再試行
                }

                // その他のエラー時は、状態を破壊せずpreviousStateを維持してフォールバック
                if (response.statusCode() != 200) {
                    System.err.println("\nAPI Fatal Error: " + response.body());
                    return new LlmResponse(
                            previousState, // 状態を維持（破壊しない）
                            "API通信障害により外部干渉を破棄。自己状態を維持します。"
                    );
                }

                JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
                String responseText = jsonResponse
                        .getAsJsonArray("candidates").get(0).getAsJsonObject()
                        .getAsJsonObject("content")
                        .getAsJsonArray("parts").get(0).getAsJsonObject()
                        .get("text").getAsString();

                String cleanJson = responseText.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
                return gson.fromJson(cleanJson, LlmResponse.class);

            } catch (Exception e) {
                System.err.println("\nSystem Exception: " + e.getMessage());
                if (attempt == maxRetries) {
                    // 最大リトライ後も例外が出た場合は状態維持
                    return new LlmResponse(previousState, "システム例外により外部干渉を破棄。");
                }
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }

        // 理論上到達しないがコンパイラ対応
        return new LlmResponse(previousState, "リトライ上限到達。");
    }
}