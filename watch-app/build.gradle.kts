// 手錶端（com.linewatch.watch）：v1.0.3 起引入 Pulsar（com.swmansion:pulsar 1.3.0）
// → 被迫引入 androidx.core/appcompat/material 傳遞依賴並升 AGP 8.9.1＋compileSdk 36（見 decisions D21，D6 部分鬆綁）
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
