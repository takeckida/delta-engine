import java.io.IOException;
import java.time.Instant;
import java.util.Random;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.io.File;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeltaEngine {
    public static void main(String[] args) throws InterruptedException, IOException {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));

        // 環境変数から取得
        String geminiApiKey = System.getenv("GEMINI_API_KEY");

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
        final long MINIMUM_INTERVAL_MS = 180000;

        while (true) {
            String incomingNoise = null;

            // 1. ノイズの監視フェーズ（ポーリング）
            if (reader.ready()) {
                // ユーザー入力があれば即座にインターラプトとして扱う
                incomingNoise = "USER_DIRECT_INTERFERENCE: " + reader.readLine();
            } else {
                // 入力がない場合、前回のAPI呼び出しから3分経過しているかチェック
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastApiCallTime >= MINIMUM_INTERVAL_MS) {
                    incomingNoise = collectEnvironmentNoise();
                }
            }

            // ノイズがなければ（インターラプトもタイムアウトもなければ）0.1秒待機してループ先頭へ戻る
            if (incomingNoise == null) {
                Thread.sleep(100);
                continue;
            }

            // --- 記憶の想起フェーズ ---
            String memoryContext = "";
            if (incomingNoise.startsWith("USER_DIRECT_INTERFERENCE: ")) {
                String userInput = incomingNoise.replace("USER_DIRECT_INTERFERENCE: ", "").trim();
                // 簡易的なキーワード検索（ユーザー入力そのものをキーにして直近3件を取得）
                java.util.List<StateTransaction> memories = dbManager.searchMemories(userInput, 3);

                if (!memories.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (StateTransaction m : memories) {
                        sb.append(String.format("- 過去の入力 [%s] に対し、状態 [%s] を形成した。\n",
                                m.externalDelta().replace("USER_DIRECT_INTERFERENCE: ", ""),
                                m.internalState()));
                    }
                    memoryContext = sb.toString();
                    System.out.println("  [MEMORY RECALLED] " + memories.size() + " records found.");
                }
            }

            // --- (前略: ノイズの監視フェーズ・記憶の想起フェーズ) ---

            // --- 差異計算フェーズ（Java物理レイヤー） ---
            clockTick++;
            System.out.println("\n========================================");

            // 【追加】物理的な差分抽出とクオリア（摩擦）の計算
            DifferenceExtractor extractor = new DifferenceExtractor();
            DifferenceExtractor.DiffResult diffResult = extractor.extract(currentState.internalState(), incomingNoise);

            System.out.printf("  [PHYSICAL DIFF] %s%n", diffResult.structuredDiff().replace("\n", " / "));
            System.out.printf("  [QUALIA WEIGHT] %.2f%n", diffResult.qualiaWeight());

            // API呼び出し時刻を更新
            lastApiCallTime = System.currentTimeMillis();

            // LLMへの入力を、生データだけでなく「物理的に計算された差異とクオリア」を付与して強化する
            String augmentedNoise = String.format(
                    "生データ: %s\n物理的差分サマリ: %s\n発生したクオリア(処理負荷の重さ): %.2f",
                    incomingNoise,
                    diffResult.structuredDiff(),
                    diffResult.qualiaWeight()
            );

            // LlmClientに差異計算を投げて、次のステートと出力を受け取る
            LlmClient.LlmResponse response = llmClient.computeSyncState(
                    currentState.internalState(),
                    augmentedNoise, // 強化されたノイズを渡す
                    memoryContext
            );

            // 受け取った結果を使って、システムステートを上書き（時間を進める）
            currentState = currentState.applyDelta(
                    incomingNoise,
                    response.internalState(),
                    response.outputDelta()
            );

            // 確定した自己状態の変容をDBへ物理的に書き込み、意識を永続化する
            dbManager.saveTransaction(currentState);

            // --- アクチュエーター（外界への作用）フェーズ ---
            executePhysicalCommands(currentState.outputDelta());

            System.out.printf("[TICK %d] %s%n", clockTick, currentState.timestamp());
            System.out.printf("  Δin  : %s%n", currentState.externalDelta());
            System.out.printf("  Sn+1 : %s%n", currentState.internalState());
            System.out.printf("  Δout : %s%n", currentState.outputDelta());
        }
    }

    private static String collectEnvironmentNoise() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        long freeMemory = Runtime.getRuntime().freeMemory();
        int activeThreads = Thread.activeCount();
        double loadAverage = osBean.getSystemLoadAverage(); // 取得不可のOS環境では負の値となる
        long freeDiskSpace = new File(".").getFreeSpace(); // 実行カレントドライブの空き容量

        return String.format(
                "ENVIRONMENT_NOISE: { \"freeMemory\": %d, \"activeThreads\": %d, \"systemLoadAverage\": %.2f, \"freeDiskSpace\": %d }",
                freeMemory, activeThreads, loadAverage, freeDiskSpace
        );
    }

    private static void executePhysicalCommands(String outputDelta) {
        // 正規表現で [CMD: WRITE_FILE(ファイル名, "内容")] を抽出
        // シングル・ダブル両対応、カンマ以降をまとめて取得する例
        Pattern pattern = Pattern.compile("\\[CMD:\\s*WRITE_FILE\\(([^,]+),\\s*['\"](.*)['\"]\\)\\]");
        Matcher matcher = pattern.matcher(outputDelta);

        while (matcher.find()) {
            String filename = matcher.group(1).trim();
            String content = matcher.group(2);

            try {
                // ファイルが存在しなければ作成し、存在すれば追記する
                Files.writeString(
                        Paths.get(filename),
                        content + "\n",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
                System.out.println("  [ACTUATOR: EXECUTED] 物理空間へ痕跡を出力しました: " + filename);
            } catch (Exception e) {
                System.err.println("  [ACTUATOR: FAILED] 物理干渉に失敗: " + e.getMessage());
            }
        }
    }
}