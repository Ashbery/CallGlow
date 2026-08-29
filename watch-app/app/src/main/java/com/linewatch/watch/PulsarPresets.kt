package com.linewatch.watch

import com.swmansion.pulsar.Pulsar
import com.swmansion.pulsar.types.Preset

/**
 * v1.0.3：從 Pulsar（com.swmansion:pulsar 1.3.0，MIT）挑選的 15 種震動預設。
 * - key：對應 PresetsWrapper 的 accessor（alarm() 等，呼叫即播放一次）
 * - durationMs：取自原始檔 rawContinuousPattern/rawDiscretePattern 的最大時間點（循環重發間隔用）
 * - 標籤：中文名＋英文名（Settings 顯示）
 */
object PulsarPresets {

    data class Item(val key: String, val labelZh: String, val labelEn: String, val durationMs: Long)

    val ITEMS: List<Item> = listOf(
        Item("alarm", "警報", "Alarm", 1130),
        Item("buzz", "蜂鳴", "Buzz", 350),
        Item("warDrum", "戰鼓", "War Drum", 398),
        Item("charge", "充能", "Charge", 2046),
        Item("crescendo", "漸強", "Crescendo", 601),
        Item("bassDrop", "重低音", "Bass Drop", 71),
        Item("canter", "三步節奏", "Canter", 173),
        Item("cadence", "兩拍節奏", "Cadence", 199),
        Item("tickTock", "滴答", "Tick-Tock", 1200),
        Item("bellToll", "鐘鳴", "Bell Toll", 399),
        Item("barrage", "彈幕", "Barrage", 309),
        Item("woodpecker", "啄木鳥", "Woodpecker", 460),
        Item("hammer", "重錘", "Hammer", 1050),
        Item("cascade", "瀑布", "Cascade", 1863),
        Item("explosion", "爆炸", "Explosion", 1000),
    )

    /** 播放一次（accessor 即播放）；未知 key 回 false。 */
    fun playOnce(pulsar: Pulsar, key: String): Boolean = when (key) {
        "alarm" -> play(pulsar.getPresets()::alarm)
        "buzz" -> play(pulsar.getPresets()::buzz)
        "warDrum" -> play(pulsar.getPresets()::warDrum)
        "charge" -> play(pulsar.getPresets()::charge)
        "crescendo" -> play(pulsar.getPresets()::crescendo)
        "bassDrop" -> play(pulsar.getPresets()::bassDrop)
        "canter" -> play(pulsar.getPresets()::canter)
        "cadence" -> play(pulsar.getPresets()::cadence)
        "tickTock" -> play(pulsar.getPresets()::tickTock)
        "bellToll" -> play(pulsar.getPresets()::bellToll)
        "barrage" -> play(pulsar.getPresets()::barrage)
        "woodpecker" -> play(pulsar.getPresets()::woodpecker)
        "hammer" -> play(pulsar.getPresets()::hammer)
        "cascade" -> play(pulsar.getPresets()::cascade)
        "explosion" -> play(pulsar.getPresets()::explosion)
        else -> false
    }

    fun durationMs(key: String): Long =
        ITEMS.firstOrNull { it.key == key }?.durationMs ?: 700L

    private fun play(play: () -> Unit): Boolean {
        play()
        return true
    }
}
