// 手錶端（com.linewatch.watch）：純 AOSP，禁用 androidx.wear 與 GMS（docs/decisions.md D6/D7）
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
