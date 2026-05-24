package com.deltaengine.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.util.Map;

public class DifferenceExtractor {
    private final Gson gson = new Gson();

    public record DiffResult(String structuredDiff, double qualiaWeight) {}

    /**
     * 現在の状態と外部ノイズを物理的に比較し、差分とクオリア（摩擦）を算出する
     */
    public DiffResult extract(String currentStateString, String incomingNoise) {
        long startTime = System.nanoTime();
        double qualiaWeight = 0.0;
        StringBuilder diffSummary = new StringBuilder();

        // 1. 環境ノイズ(JSON)の構造的な差異チェック
        if (incomingNoise.startsWith("ENVIRONMENT_NOISE: ")) {
            String jsonPart = incomingNoise.replace("ENVIRONMENT_NOISE: ", "").trim();
            try {
                JsonObject currentJson = extractJson(currentStateString); // 現在状態からJSONを抽出（もしあれば）
                JsonObject newJson = gson.fromJson(jsonPart, JsonObject.class);

                int structuralChanges = 0;
                for (Map.Entry<String, JsonElement> entry : newJson.entrySet()) {
                    String key = entry.getKey();
                    if (currentJson == null || !currentJson.has(key)) {
                        diffSummary.append(String.format("[NEW] %s = %s\n", key, entry.getValue()));
                        structuralChanges += 2; // 新規キーの発見は摩擦が大きい
                    } else if (!currentJson.get(key).equals(entry.getValue())) {
                        diffSummary.append(String.format("[MOD] %s : %s -> %s\n", key, currentJson.get(key), entry.getValue()));
                        structuralChanges += 1; // 値の変更
                    }
                }
                qualiaWeight += structuralChanges * 1.5;
            } catch (JsonSyntaxException e) {
                diffSummary.append("[ERROR] JSON解析失敗による強烈なノイズ\n");
                qualiaWeight += 10.0; // パースエラーは大きなシステム摩擦（クオリア）を生む
            }
        }
        // 2. ユーザー入力等の非定型テキストの物理的差異チェック
        else {
            // 単純な文字列長の差分と文字コードの演算負荷を摩擦とする
            int lengthDiff = Math.abs(incomingNoise.length() - currentStateString.length());
            diffSummary.append(String.format("[TEXT_DIFF] 文字列長の差異: %dバイト\n", lengthDiff));
            qualiaWeight += (lengthDiff * 0.1) + 5.0; // テキスト処理の基本負荷
        }

        long processingTimeNs = System.nanoTime() - startTime;
        qualiaWeight += (processingTimeNs / 1_000_000.0) * 0.01; // 実際のミリ秒処理時間をクオリアに加算

        return new DiffResult(diffSummary.toString().trim(), qualiaWeight);
    }

    private JsonObject extractJson(String text) {
        try {
            // 文字列内にJSON構造があれば抜き出してパース（簡易実装）
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start != -1 && end != -1 && start < end) {
                return gson.fromJson(text.substring(start, end + 1), JsonObject.class);
            }
        } catch (Exception ignored) {}
        return null;
    }
}