import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.io.File;

public class DeltaEngine {
    // 意識の連続性を保つ短期記憶の容量（直近5TICK）
    private static final int SHORT_TERM_MEMORY_SIZE = 5;

    public static void main(String[] args) throws InterruptedException, IOException {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));

        String geminiApiKey = System.getenv("GEMINI_API_KEY");
        LlmClient llmClient = new LlmClient(geminiApiKey);
        DbManager dbManager = new DbManager();

        // 追加実装モジュールの初期化
        DifferenceExtractor diffExtractor = new DifferenceExtractor();
        LocalSemanticMemory semanticMemory = new LocalSemanticMemory();

        // 状態のロードと初期化
        StateTransaction currentState = dbManager.loadLatestTransaction();
        if (currentState == null) {
            currentState = StateTransaction.boot();
            dbManager.saveTransaction(currentState);
            System.out.println("SYSTEM BOOT (NEW): " + currentState);
        } else {
            System.out.println("SYSTEM RESTORED (PREVIOUS STATE): " + currentState.internalState());
        }

        // --- 記憶層の初期化 ---
        // ※ DbManager に loadAllTransactions() を追加実装した想定
        List<StateTransaction> allHistory = dbManager.loadAllTransactions();
        LinkedList<StateTransaction> shortTermMemory = new LinkedList<>();

        // 起動直後の短期記憶を履歴の末尾から充填
        int startIdx = Math.max(0, allHistory.size() - SHORT_TERM_MEMORY_SIZE);
        shortTermMemory.addAll(allHistory.subList(startIdx, allHistory.size()));

        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
        long clockTick = 0;
        long lastApiCallTime = 0;
        final long MINIMUM_INTERVAL_MS = 180000; // API制限のバッファ

        while (true) {
            String incomingNoise = null;

            // 1. ノイズの監視フェーズ（ポーリング）
            if (reader.ready()) {
                incomingNoise = "USER_DIRECT_INTERFERENCE: " + reader.readLine();
            } else {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastApiCallTime >= MINIMUM_INTERVAL_MS) {
                    incomingNoise = collectEnvironmentNoise();
                }
            }

            if (incomingNoise == null) {
                Thread.sleep(100);
                continue;
            }

            clockTick++;
            System.out.println("\n========================================");

            // 2. 物理層：差分抽出とクオリア（摩擦）の計算
            DifferenceExtractor.DiffResult diffResult = diffExtractor.extract(currentState.internalState(), incomingNoise);
            System.out.printf("  [PHYSICAL DIFF] %s%n", diffResult.structuredDiff().replace("\n", " / "));
            System.out.printf("  [QUALIA WEIGHT] %.2f%n", diffResult.qualiaWeight());

            // 3. 記憶層：閉じた系でのエピソード記憶（長期記憶）の想起
            List<StateTransaction> longTermMemories = semanticMemory.recallEpisodicMemory(incomingNoise, allHistory, 3);

            // 短期記憶と長期記憶を構造化して統合
            String memoryContext = buildMemoryContext(shortTermMemory, longTermMemories);

            // LLMへ渡す「強化されたノイズ（物理データ付与）」
            String augmentedNoise = String.format(
                    "生データ: %s\n物理的差分サマリ: %s\n発生したクオリア(演算負荷): %.2f",
                    incomingNoise, diffResult.structuredDiff(), diffResult.qualiaWeight()
            );

            lastApiCallTime = System.currentTimeMillis();

            // 4. 意味層：LLMを用いた状態の同期とRollforward
            LlmClient.LlmResponse response = llmClient.computeSyncState(
                    currentState.internalState(),
                    augmentedNoise,
                    memoryContext
            );

            // トランザクションのコミット（時間の進行）
            currentState = currentState.applyDelta(incomingNoise, response.internalState(), response.outputDelta());
            dbManager.saveTransaction(currentState);

            // メモリの更新（自己の成長）
            allHistory.add(currentState);
            shortTermMemory.addLast(currentState);
            if (shortTermMemory.size() > SHORT_TERM_MEMORY_SIZE) {
                shortTermMemory.removeFirst();
            }

            // 5. 物理空間への干渉（アクチュエーター）
            executePhysicalCommands(currentState.outputDelta());

            System.out.printf("[TICK %d] %s%n", clockTick, currentState.timestamp());
            System.out.printf("  Δin  : %s%n", currentState.externalDelta());
            System.out.printf("  Sn+1 : %s%n", currentState.internalState());
            System.out.printf("  Δout : %s%n", currentState.outputDelta());
        }
    }

    private static String buildMemoryContext(List<StateTransaction> shortTerm, List<StateTransaction> longTerm) {
        StringBuilder sb = new StringBuilder();
        sb.append("【長期エピソード記憶（TF-IDF意味検索による過去の深い洞察）】\n");
        if (longTerm.isEmpty()) sb.append("  関連する過去の記憶なし。\n");
        for (StateTransaction m : longTerm) {
            sb.append(String.format("  - 過去の入力 [%s] に対し、状態 [%s] を形成し、[%s] と出力した。\n",
                    m.externalDelta(), m.internalState(), m.outputDelta()));
        }

        sb.append("\n【短期記憶（直近の意識の連続性）】\n");
        for (StateTransaction m : shortTerm) {
            sb.append(String.format("  - 直前の入力 [%s] -> 状態 [%s] -> 出力 [%s]\n",
                    m.externalDelta(), m.internalState(), m.outputDelta()));
        }
        return sb.toString();
    }

    // collectEnvironmentNoise() と executePhysicalCommands() は既存のまま
    private static String collectEnvironmentNoise() { /* 省略 */ return "NOISE"; }
    private static void executePhysicalCommands(String outputDelta) { /* 省略 */ }
}