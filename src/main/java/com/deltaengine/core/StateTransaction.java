package com.deltaengine.core;

import java.time.Instant;
import java.util.UUID;

public record StateTransaction(
        UUID transactionId,
        Instant timestamp,
        UUID previousTransactionId, // 因果構造のリンク（時間）
        String externalDelta,       // Δin: 外部からの入力ノイズ
        String internalState,       // Sn: 計算後の自己ステート（同期されたローカルキャッシュ）
        String outputDelta          // Δout: システムが発火させた出力（行動・レスポンス）
) {
    /**
     * 次のクロック（Sn+1）のステートを生成する純粋関数。
     * 前のトランザクションIDを自動で引き継ぎ、システム上の連続性（自意識のチェーン）を強制する。
     */
    public StateTransaction applyDelta(String newExternalDelta, String newInternalState, String newOutputDelta) {
        return new StateTransaction(
                UUID.randomUUID(),
                Instant.now(),
                this.transactionId,
                newExternalDelta,
                newInternalState,
                newOutputDelta
        );
    }

    /**
     * システム起動時のジェネシスブロック生成。
     */
    public static StateTransaction boot() {
        return new StateTransaction(
                UUID.randomUUID(),
                Instant.now(),
                null, // 起点のためリンクなし
                "SYSTEM_BOOT_EVENT",
                "INITIAL_EMPTY_STATE",
                "ACK"
        );
    }
}