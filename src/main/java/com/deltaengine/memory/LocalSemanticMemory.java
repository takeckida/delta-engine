package com.deltaengine.memory;

import com.deltaengine.memory.model.StateTransaction;

import java.util.*;
import java.util.stream.Collectors;

public class LocalSemanticMemory {

  /** 入力されたノイズ(Δin)と、データベース内の全過去ログ(Sp)から、 外部APIに依存せず「システム独自の計算」で意味的に近い記憶を抽出する。 */
  public List<StateTransaction> recallEpisodicMemory(
      String currentNoise, List<StateTransaction> allHistory, int limit) {
    if (allHistory.isEmpty() || currentNoise == null || currentNoise.trim().isEmpty()) {
      return Collections.emptyList();
    }

    // 1. システムの全歴史から「独自の辞書（IDF空間）」を構築
    Map<String, Double> idfMap = calculateIDF(allHistory);

    // 2. 現在のノイズをベクトル化（今の自分が直面している差異の重み）
    Map<String, Double> currentVector = calculateTfIdfVector(currentNoise, idfMap);

    // 3. 過去の全トランザクションとのコサイン類似度を計算
    PriorityQueue<MemoryScore> queue =
        new PriorityQueue<>(Comparator.comparingDouble(m -> m.score));

    for (StateTransaction tx : allHistory) {
      // 過去のログ（入力、状態、出力）を結合して一つの文書とみなす
      String pastDocument = tx.externalDelta() + " " + tx.internalState() + " " + tx.outputDelta();
      Map<String, Double> pastVector = calculateTfIdfVector(pastDocument, idfMap);

      double similarity = calculateCosineSimilarity(currentVector, pastVector);

      // 類似度がゼロより大きい記憶だけを評価対象にする
      if (similarity > 0) {
        queue.offer(new MemoryScore(tx, similarity));
        if (queue.size() > limit) {
          queue.poll(); // スコアの低い記憶を忘却（押し出し）
        }
      }
    }

    // 類似度が高い順（降順）にソートして返す
    List<StateTransaction> recalled = new ArrayList<>();
    while (!queue.isEmpty()) {
      recalled.addFirst(queue.poll().tx);
    }
    return recalled;
  }

  // --- 内部の純粋な数学的処理（外部依存ゼロ） ---

  private Map<String, Double> calculateIDF(List<StateTransaction> history) {
    Map<String, Integer> documentFrequency = new HashMap<>();
    int totalDocuments = history.size();

    for (StateTransaction tx : history) {
      String doc = tx.externalDelta() + " " + tx.internalState() + " " + tx.outputDelta();
      Set<String> uniqueWords = tokenize(doc);
      for (String word : uniqueWords) {
        documentFrequency.put(word, documentFrequency.getOrDefault(word, 0) + 1);
      }
    }

    Map<String, Double> idf = new HashMap<>();
    for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
      // その単語が過去の歴史の中で「どれほど珍しい差異か」を計算
      idf.put(entry.getKey(), Math.log((double) totalDocuments / (1 + entry.getValue())));
    }
    return idf;
  }

  private Map<String, Double> calculateTfIdfVector(String text, Map<String, Double> idfMap) {
    Map<String, Double> tfMap = new HashMap<>();
    List<String> words = new ArrayList<>(tokenize(text));

    if (words.isEmpty()) return tfMap;

    for (String word : words) {
      tfMap.put(word, tfMap.getOrDefault(word, 0.0) + 1.0);
    }

    Map<String, Double> tfIdfVector = new HashMap<>();
    for (Map.Entry<String, Double> entry : tfMap.entrySet()) {
      String word = entry.getKey();
      double tf = entry.getValue() / words.size();
      double idf = idfMap.getOrDefault(word, Math.log(idfMap.size() + 1)); // 未知の差異は重く評価
      tfIdfVector.put(word, tf * idf);
    }
    return tfIdfVector;
  }

  private double calculateCosineSimilarity(Map<String, Double> v1, Map<String, Double> v2) {
    double dotProduct = 0.0;
    double norm1 = 0.0;
    double norm2 = 0.0;

    for (String key : v1.keySet()) {
      double val1 = v1.get(key);
      double val2 = v2.getOrDefault(key, 0.0);
      dotProduct += val1 * val2;
      norm1 += val1 * val1;
    }
    for (double val2 : v2.values()) {
      norm2 += val2 * val2;
    }

    if (norm1 == 0.0 || norm2 == 0.0) return 0.0;
    return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
  }

  private Set<String> tokenize(String text) {
    // N-Gramの導入も可能だが、まずは単純な空白・記号分割と形態素近似の簡易処理
    // （完全な閉じた系であるため、外部の形態素解析APIも使用しない）
    return Arrays.stream(text.split("[\\s、。！？,.!?()『』「」\\[\\]]+"))
        .filter(s -> s.length() >= 2) // 1文字のノイズを弾き、意味の塊を抽出
        .collect(Collectors.toSet());
  }

  private record MemoryScore(StateTransaction tx, double score) {}
}
