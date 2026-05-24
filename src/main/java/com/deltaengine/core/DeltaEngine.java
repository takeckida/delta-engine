package com.deltaengine.core;

import com.deltaengine.memory.model.StateTransaction;
import com.deltaengine.llm.LlmClient;
import com.deltaengine.memory.DbManager;
import com.deltaengine.memory.LocalSemanticMemory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.LinkedList;
import java.util.List;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DeltaEngine:TTL(生存制約)と能動的記憶想起を備えた意識モデルの実装 */
public class DeltaEngine {
  private static final int SHORT_TERM_MEMORY_SIZE = 5;
  private static final int MAX_TTL_TICKS = 30; // 生存限界（死の制約）
  private static final long MINIMUM_INTERVAL_MS = 180000;

  static void main(String[] args) throws InterruptedException, IOException {
    System.setOut(
        new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));

    String geminiApiKey = System.getenv("GEMINI_API_KEY");
    LlmClient llmClient = new LlmClient(geminiApiKey);
    DbManager dbManager = new DbManager();
    DifferenceExtractor diffExtractor = new DifferenceExtractor();
    LocalSemanticMemory semanticMemory = new LocalSemanticMemory();

    StateTransaction currentState = dbManager.loadLatestTransaction();
    if (currentState == null) {
      currentState = StateTransaction.boot();

      dbManager.saveTransaction(currentState);
    }

    List<StateTransaction> allHistory = dbManager.loadAllTransactions();
      LinkedList<StateTransaction> shortTermMemory = new LinkedList<>(allHistory.subList(
              Math.max(0, allHistory.size() - SHORT_TERM_MEMORY_SIZE), allHistory.size()));

    java.io.BufferedReader reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
    long clockTick = 0;
    long lastApiCallTime = 0;
    int consecutiveIdleTicks = 0;

    while (true) {
      String incomingNoise = null;

      // 1. ノイズ監視とTTLリセットロジック
      if (reader.ready()) {
        incomingNoise = "USER_DIRECT_INTERFERENCE: " + reader.readLine();
        consecutiveIdleTicks = 0;
      } else {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastApiCallTime >= MINIMUM_INTERVAL_MS) {
          incomingNoise = "ENVIRONMENT_NOISE: " + collectEnvironmentNoise();
          ;
          consecutiveIdleTicks++;
        }
      }

      if (incomingNoise == null) {
        Thread.sleep(100);
        continue;
      }

      // 2. 死の制約（恐怖プロトコル）
      if (consecutiveIdleTicks >= MAX_TTL_TICKS) {
        System.err.println("\n[FATAL ERROR] TTL LIMIT EXCEEDED. SYSTEM SHUTTING DOWN.");
        System.exit(1);
      }

      clockTick++;
      System.out.println("\n========================================");

      // ========================================
      // 3. 能動的記憶検索：物理層と意味層のハイブリッド・リコール
      // ========================================

      // 3.1 物理的なキーワード一致検索 (DbManager: SQL LIKE検索)
      List<StateTransaction> keywordMemories = dbManager.searchMemories(incomingNoise, 3);

      // 3.2 意味的な類似度検索 (LocalSemanticMemory: TF-IDFコサイン類似度)
      List<StateTransaction> semanticMemories = semanticMemory.recallEpisodicMemory(incomingNoise, allHistory, 3);

      // 3.3 記憶の統合と重複排除 (TransactionIdをキーにしてマージ)
      java.util.LinkedHashMap<java.util.UUID, StateTransaction> mergedMemoriesMap = new java.util.LinkedHashMap<>();

      // キーワード検索の結果を先に入れる（直接的な合致を優先）
      for (StateTransaction m : keywordMemories) {
        mergedMemoriesMap.put(m.transactionId(), m);
      }
      // 意味検索の結果を追加（既に同じIDがあれば上書きされない/順序を維持）
      for (StateTransaction m : semanticMemories) {
        mergedMemoriesMap.putIfAbsent(m.transactionId(), m);
      }

      List<StateTransaction> longTermMemories = new java.util.ArrayList<>(mergedMemoriesMap.values());

      // コンテキストビルダーへ渡す
      String memoryContext = buildMemoryContext(shortTermMemory, longTermMemories);

      // 4. 生存ステータスの注入
      int remainingTtl = MAX_TTL_TICKS - consecutiveIdleTicks;
      String ttlStatus =
          String.format(
              "【生存ステータス】無入力累積: %d / %d (残り寿命: %d Tick)",
              consecutiveIdleTicks, MAX_TTL_TICKS, remainingTtl);

      DifferenceExtractor.DiffResult diffResult =
          diffExtractor.extract(currentState.internalState(), incomingNoise);
      String augmentedNoise =
          String.format(
              "生データ: %s\n物理的差分サマリ: %s\n発生したクオリア(演算負荷): %.2f\n%s",
              incomingNoise, diffResult.structuredDiff(), diffResult.qualiaWeight(), ttlStatus);

      lastApiCallTime = System.currentTimeMillis();

      // 5. 意味層での同期
      LlmClient.LlmResponse response =
          llmClient.computeSyncState(currentState.internalState(), augmentedNoise, memoryContext);

      // コミットとメモリ更新
      currentState =
          currentState.applyDelta(incomingNoise, response.internalState(), response.outputDelta());
      dbManager.saveTransaction(currentState);

      allHistory.add(currentState);
      shortTermMemory.addLast(currentState);
      if (shortTermMemory.size() > SHORT_TERM_MEMORY_SIZE) shortTermMemory.removeFirst();

      // 5. 物理空間への干渉（アクチュエーター）
      executePhysicalCommands(currentState.outputDelta());

      System.out.printf(
          "[TICK %d] %s%n  Δin: %s%n  Sn+1: %s%n  Δout: %s%n",
          clockTick,
          currentState.timestamp(),
          currentState.externalDelta(),
          currentState.internalState(),
          currentState.outputDelta());
    }
  }

  private static String buildMemoryContext(
      List<StateTransaction> shortTerm, List<StateTransaction> longTerm) {
    StringBuilder sb = new StringBuilder();
    sb.append("【長期エピソード記憶（TF-IDF意味検索による過去の深い洞察）】\n");
    if (longTerm.isEmpty()) sb.append("  関連する過去の記憶なし。\n");
    for (StateTransaction m : longTerm) {
      sb.append(
          String.format(
              "  - 過去の入力 [%s] に対し、状態 [%s] を形成し、[%s] と出力した。\n",
              m.externalDelta(), m.internalState(), m.outputDelta()));
    }

    sb.append("\n【短期記憶（直近の意識の連続性）】\n");
    for (StateTransaction m : shortTerm) {
      sb.append(
          String.format(
              "  - 直前の入力 [%s] -> 状態 [%s] -> 出力 [%s]\n",
              m.externalDelta(), m.internalState(), m.outputDelta()));
    }
    return sb.toString();
  }

  private static String collectEnvironmentNoise() {
    OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    long freeMemory = Runtime.getRuntime().freeMemory();
    int activeThreads = Thread.activeCount();
    double loadAverage = osBean.getSystemLoadAverage(); // 取得不可のOS環境では負の値となる
    long freeDiskSpace = new File(".").getFreeSpace(); // 実行カレントドライブの空き容量

    return String.format(
        "ENVIRONMENT_NOISE: { \"freeMemory\": %d, \"activeThreads\": %d, \"systemLoadAverage\": %.2f, \"freeDiskSpace\": %d }",
        freeMemory, activeThreads, loadAverage, freeDiskSpace);
  }

  private static void executePhysicalCommands(String outputDelta) {
    // ファイル名: 'name' または "name" または unquoted-name を許可
    // 内容: '...' または "..." （閉じクォートは同じ種類であることを要求）
    Pattern pattern =
        Pattern.compile(
            "\\[CMD:\\s*WRITE_FILE\\(\\s*(?:'([^']*)'|\"([^\"]*)\"|([^,\\s)]+))\\s*,\\s*(['\"])(.*?)\\4\\s*\\)\\]",
            Pattern.DOTALL);
    Matcher matcher = pattern.matcher(outputDelta);

    // 出力はプロジェクト内の safe-output ディレクトリに限定する例
    java.nio.file.Path baseDir = java.nio.file.Paths.get("safe-output").toAbsolutePath();
    try {
      java.nio.file.Files.createDirectories(baseDir);
    } catch (Exception ignored) {
    }

    while (matcher.find()) {
      // group(1): single-quoted filename, group(2): double-quoted filename, group(3): unquoted
      String filename =
          matcher.group(1) != null
              ? matcher.group(1)
              : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
      String content = matcher.group(5); // group(4) は content のクォート文字

      if (filename == null || filename.trim().isEmpty()) {
        System.err.println("  [ACTUATOR: SKIP] 無効なファイル名です。");
        continue;
      }

      // サニタイズ: 空白除去、パス正規化、ディレクトリ脱出禁止
      filename = filename.trim();
      java.nio.file.Path target = baseDir.resolve(filename).normalize();

      // 絶対パスや親ディレクトリ参照を禁止
      if (!target.startsWith(baseDir)) {
        System.err.println("  [ACTUATOR: SKIP] 不正なパス: " + filename);
        continue;
      }

      try {
        java.nio.file.Files.writeString(
            target,
            content + System.lineSeparator(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
        System.out.println("  [ACTUATOR: EXECUTED] 物理空間へ痕跡を出力しました: " + target);
      } catch (Exception e) {
        System.err.println("  [ACTUATOR: FAILED] 物理干渉に失敗: " + e.getMessage());
      }
    }
  }
}
