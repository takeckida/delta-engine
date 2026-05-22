import java.io.IOException;
import java.time.Instant;
import java.util.Random;

public class DeltaEngine {
    public static void main(String[] args) throws InterruptedException, IOException {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));
        // APIキー取得後にここに入れる（今は空でOK）
        String geminiApiKey = "";
        LlmClient llmClient = new LlmClient(geminiApiKey);
        DbManager dbManager = new DbManager();

        // 過去の記憶（最新トランザクション）をDBからロード
        StateTransaction currentState = dbManager.loadLatestTransaction();
        if (currentState == null) {
            // 過去の記憶がない場合はゼロから初期化し、DBに保存
            currentState = new StateTransaction(
                    java.util.UUID.randomUUID(),
                    java.time.Instant.now(),
                    null,
                    "SYSTEM_BOOT_EVENT",
                    "INITIAL_EMPTY_STATE",
                    "ACK"
            );
            dbManager.saveTransaction(currentState);
            System.out.println("SYSTEM BOOT (NEW): " + currentState);
        } else {
            System.out.println("SYSTEM RESTORED (PREVIOUS STATE): " + currentState.internalState());
        }

        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
        long clockTick = 0;

        // APIレートリミット管理用
        long lastApiCallTime = 0;
        final long MINIMUM_INTERVAL_MS = 60000;

        while (true) {
            String incomingNoise = null;

            // 1. ノイズの監視フェーズ（ポーリング）
            if (reader.ready()) {
                // ユーザー入力があれば即座にインターラプトとして扱う
                incomingNoise = "USER_DIRECT_INTERFERENCE: " + reader.readLine();
            } else {
                // 入力がない場合、前回のAPI呼び出しから60秒経過しているかチェック
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastApiCallTime >= MINIMUM_INTERVAL_MS) {
                    incomingNoise = "ENVIRONMENT_NOISE: FREE_MEMORY_" + Runtime.getRuntime().freeMemory() + "_BYTES";
                }
            }

            // ノイズがなければ（インターラプトもタイムアウトもなければ）0.1秒待機してループ先頭へ戻る
            if (incomingNoise == null) {
                Thread.sleep(100);
                continue;
            }

            // --- 差異計算フェーズ ---
            clockTick++;
            System.out.println("\n========================================");

            // API呼び出し時刻を更新
            lastApiCallTime = System.currentTimeMillis();

            // LlmClientに差異計算を投げて、次のステートと出力を受け取る
            LlmClient.LlmResponse response = llmClient.computeSyncState(
                    currentState.internalState(),
                    incomingNoise
            );

            // 受け取った結果を使って、システムステートを上書き（時間を進める）
            currentState = currentState.applyDelta(
                    incomingNoise,
                    response.internalState(),
                    response.outputDelta()
            );

            // 確定した自己状態の変容をDBへ物理的に書き込み、意識を永続化する
            dbManager.saveTransaction(currentState);

            System.out.printf("[TICK %d] %s%n", clockTick, currentState.timestamp());
            System.out.printf("  Δin  : %s%n", currentState.externalDelta());
            System.out.printf("  Sn+1 : %s%n", currentState.internalState());
            System.out.printf("  Δout : %s%n", currentState.outputDelta());
        }
    }
}