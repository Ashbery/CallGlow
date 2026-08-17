package com.linewatch.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** CallParser 純邏輯單元測試（test-plan §2.3、§5.6 通知格式變化 fallback 樣本庫）。 */
public class CallParserTest {

    // ---- 來電判定 ----

    @Test
    public void categoryCallTriggersCall() {
        assertTrue(CallParser.isCall("call", "", "", ""));
    }

    @Test
    public void chineseCallKeywordsTriggerCall() {
        assertTrue(CallParser.isCall("", "LINE 語音通話", "", ""));
        assertTrue(CallParser.isCall("", "LINE 視訊通話", "", "王小明"));
        assertTrue(CallParser.isCall("", "來電", "", "王小明"));
        assertTrue(CallParser.isCall("", "LINE 未接來電", "", ""));
    }

    @Test
    public void englishCallKeywordsTriggerCall() {
        assertTrue(CallParser.isCall("", "Voice call", "", ""));
        assertTrue(CallParser.isCall("", "Incoming call", "", "Alice"));
        assertTrue(CallParser.isCall("", "Video call", "", "Bob"));
    }

    @Test
    public void callKeywordsInSubTextTriggerCall() {
        assertTrue(CallParser.isCall("", "王小明", "語音通話", ""));
        assertTrue(CallParser.isCall("", "LINE", "視訊通話", "王小明"));
    }

    @Test
    public void plainMessageIsNotCall() {
        assertFalse(CallParser.isCall("", "王小明", "", "早安！"));
        assertFalse(CallParser.isCall("", "LINE", "", "今天開會嗎？"));
        assertFalse(CallParser.isCall("", "王小明", "LINE", "明天見"));
    }

    @Test
    public void callEndedTextIsNotCall() {
        // 「通話結束」為掛斷後的提示，不得觸發來電
        assertFalse(CallParser.isCall("", "LINE", "", "通話結束"));
        assertFalse(CallParser.isMissed("LINE", "", "通話結束"));
    }

    @Test
    public void stickerTextIsNotCall() {
        assertFalse(CallParser.isCall("", "LINE", "", "貼圖傳送中"));
    }

    // ---- 未接判定（T5 §5.6 樣本庫） ----

    @Test
    public void missedKeywords() {
        assertTrue(CallParser.isMissed("", "", "未接來電"));
        assertTrue(CallParser.isMissed("LINE 未接來電", "", ""));
        assertTrue(CallParser.isMissed("", "", "錯過的語音通話"));
        assertTrue(CallParser.isMissed("", "", "Missed call"));
        assertFalse(CallParser.isMissed("", "", "王小明來電"));
    }

    @Test
    public void missedKeywordsInSubText() {
        assertTrue(CallParser.isMissed("", "未接來電", ""));
        assertTrue(CallParser.isMissed("LINE", "未接的語音通話", ""));
    }

    @Test
    public void missedTemplatePhraseFallsBackToUnknown() {
        // LINE 模板句「你有一通未接的語音通話」：無名字 → fallback 未知聯絡人
        assertTrue(CallParser.isCall("", "LINE", "", "你有一通未接的語音通話"));
        assertTrue(CallParser.isMissed("LINE", "", "你有一通未接的語音通話"));
        assertEquals("voice", CallParser.kind("LINE", "", "你有一通未接的語音通話"));
        assertEquals("未知聯絡人", CallParser.name("LINE", "", "你有一通未接的語音通話"));
    }

    @Test
    public void missedTemplatePhraseWithTitleName() {
        // 名字在 title 的同一模板句 → 正確取出名字
        assertTrue(CallParser.isMissed("王小明", "", "你有一通未接的語音通話"));
        assertEquals("王小明", CallParser.name("王小明", "", "你有一通未接的語音通話"));
    }

    @Test
    public void missedVideoTemplateKindIsVideo() {
        assertTrue(CallParser.isMissed("LINE", "", "你有一通未接的視訊通話"));
        assertEquals("video", CallParser.kind("LINE", "", "你有一通未接的視訊通話"));
        assertEquals("未知聯絡人", CallParser.name("LINE", "", "你有一通未接的視訊通話"));
    }

    @Test
    public void missedCounterPhraseFallsBack() {
        // 「你有3通未接來電」計數樣式（含空格變體）→ fallback
        assertTrue(CallParser.isMissed("LINE", "", "你有3通未接來電"));
        assertEquals("未知聯絡人", CallParser.name("LINE", "", "你有3通未接來電"));
        assertEquals("未知聯絡人", CallParser.name("LINE", "", "你有 3 通未接來電"));
    }

    @Test
    public void missedEnglishTemplateFallsBack() {
        assertTrue(CallParser.isCall("", "LINE", "", "You have a missed voice call"));
        assertTrue(CallParser.isMissed("LINE", "", "You have a missed voice call"));
        assertEquals("voice", CallParser.kind("LINE", "", "You have a missed voice call"));
        assertEquals("未知聯絡人", CallParser.name("LINE", "", "You have a missed voice call"));
    }

    @Test
    public void missedEnglishVideoTemplateKindIsVideo() {
        assertTrue(CallParser.isMissed("LINE", "", "You have a missed video call"));
        assertEquals("video", CallParser.kind("LINE", "", "You have a missed video call"));
        assertEquals("未知聯絡人", CallParser.name("LINE", "", "You have a missed video call"));
    }

    // ---- 通話種類 ----

    @Test
    public void kindDetection() {
        assertEquals("video", CallParser.kind("LINE 視訊通話", "", ""));
        assertEquals("voice", CallParser.kind("LINE 語音通話", "", ""));
        assertEquals("video", CallParser.kind("Video call", "", ""));
        assertEquals("voice", CallParser.kind("", "", "王小明"));
    }

    @Test
    public void kindDetectionFromSubText() {
        assertEquals("video", CallParser.kind("LINE", "視訊通話", "王小明"));
        assertEquals("voice", CallParser.kind("LINE", "語音通話", "王小明"));
    }

    // ---- 名稱解析 ----

    @Test
    public void nameFromTitle() {
        assertEquals("王小明", CallParser.name("王小明", "", ""));
        assertEquals("王小明", CallParser.name("王小明 語音通話", "", ""));
    }

    @Test
    public void nameFromSubTextWhenTitleIsKeywords() {
        assertEquals("王小明", CallParser.name("LINE 語音通話", "王小明", ""));
        assertEquals("王小明", CallParser.name("LINE", "王小明", ""));
    }

    @Test
    public void nameFromTextFirstLine() {
        assertEquals("陳小美", CallParser.name("LINE 視訊通話", "", "陳小美\n語音通話"));
    }

    @Test
    public void nameFallsBackToUnknown() {
        assertEquals("未知聯絡人", CallParser.name("LINE 未接來電", "", "未接的語音通話"));
        assertEquals("未知聯絡人", CallParser.name("", "", ""));
    }

    @Test
    public void nameFromMissedTextWithName() {
        assertEquals("王小明", CallParser.name("LINE 未接來電", "", "王小明 未接的語音通話"));
    }

    @Test
    public void nameFromMissedTitleWithName() {
        assertEquals("王小明", CallParser.name("王小明 未接來電", "", ""));
    }

    @Test
    public void nameFromSubTextMissedTemplate() {
        assertEquals("王小明", CallParser.name("王小明", "未接的語音通話", ""));
        assertTrue(CallParser.isMissed("王小明", "未接的語音通話", ""));
    }

    @Test
    public void nameFromMissedCallTitleEnglish() {
        assertEquals("Alice", CallParser.name("Missed call", "", "Alice"));
        assertTrue(CallParser.isMissed("Missed call", "", "Alice"));
    }

    @Test
    public void nameFromCallTextWithPrefix() {
        assertEquals("王小明", CallParser.name("LINE", "", "王小明 來電"));
    }

    @Test
    public void englishName() {
        assertEquals("Alice", CallParser.name("Voice call", "", "Alice"));
        assertEquals("Bob", CallParser.name("Incoming call", "", "Bob"));
    }

    // ---- 通話中偵測（T4 修復 1） ----

    @Test
    public void ongoingChannelDetection() {
        assertTrue(CallParser.isOngoing("jp.naver.line.android.notification.VoIP.02.Ongoing", "LINE", "", "通話中"));
        assertTrue(CallParser.isOngoing("voip.02.ongoing", "", "", ""));
        assertTrue(CallParser.isOngoing("some.ongoing.channel", "", "", ""));
    }

    @Test
    public void ongoingTextDetection() {
        assertTrue(CallParser.isOngoing("", "LINE", "", "通話中"));
        assertTrue(CallParser.isOngoing("", "", "正在通話", ""));
        assertTrue(CallParser.isOngoing("", "", "", "Ongoing call"));
    }

    @Test
    public void ringingStyleTextIsNotOngoing() {
        assertFalse(CallParser.isOngoing("", "LINE", "", "語音通話中"));
        assertFalse(CallParser.isOngoing("", "", "", "視訊通話中"));
        assertFalse(CallParser.isOngoing("", "LINE 語音通話", "", "王小明"));
        assertFalse(CallParser.isOngoing("", "LINE 未接來電", "", ""));
    }

    @Test
    public void nonCallTextsAreNotOngoing() {
        assertFalse(CallParser.isOngoing("", "LINE", "", "通話結束"));
        assertFalse(CallParser.isOngoing("", "LINE", "", "貼圖傳送中"));
    }
}
