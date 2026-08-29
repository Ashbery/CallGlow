package com.linewatch.watch

import android.content.Context
import com.swmansion.pulsar.ActivityProvider
import com.swmansion.pulsar.Pulsar
import com.swmansion.pulsar.presets.PresetsWrapper

/**
 * v1.0.3：Pulsar 服務端適配。
 * Pulsar.getPresets() 預設以「context as Activity」建立 ActivityProvider——服務端（無 Activity）
 * 會 ClassCastException；此子類別改用可空 ActivityProvider（view-based presets 不需要時為 null）。
 */
class WatchPulsar(context: Context) : Pulsar(context) {
    override fun createPresets(): PresetsWrapper =
        PresetsWrapper(this, ActivityProvider(), engine)
}
