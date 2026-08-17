package com.linewatch.phone;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 來電通知純邏輯解析器（無 Android 相依，JVM 單元測試）。
 * 規格：docs/protocol.md「名稱解析」、docs/architecture.md CallParser、D8。
 * 注意：來電／missed／kind 判定納入 subText（T4 實測 LINE 通知欄位分佈不固定，
 * 關鍵字可能落在 title、subText 或 text 任一欄位）。
 */
public final class CallParser {

    private static final String[] CALL_KEYWORDS = {
            "來電", "語音通話", "視訊通話", "voice", "video", "call", "incoming"};
    private static final String[] MISSED_KEYWORDS = {"未接", "錯過", "missed"};
    private static final String[] VIDEO_KEYWORDS = {"視訊", "視像", "video"};
    private static final String[] NAME_KEYWORDS = {
            "line", "來電", "語音通話", "視訊通話", "通話中",
            "你有一通", "有一通", "一通", "你有",
            "通話", "未接", "錯過",
            "voice", "video", "call", "incoming", "missed"};
    /** 英文模板填充詞（整詞邊界比對，避免誤刪名字內字元）。 */
    private static final String EN_FILLERS = "you|your|have|has|had|a|an|the|of|is|are|was|to|from";
    /** 名稱中殘留的孤立助詞（前後皆非文字時才移除）。 */
    private static final String PARTICLES = "的了是與和及";
    /** 通話中頻道關鍵字（LINE 接聽後：VoIP.02.Ongoing；常數見 Constants.LINE_CHANNEL_ONGOING）。 */
    private static final String[] ONGOING_CHANNEL_KEYWORDS = {"ongoing", Constants.LINE_CHANNEL_ONGOING};
    /** 通話中文字關鍵字。 */
    private static final String[] ONGOING_TEXT_KEYWORDS = {"通話中", "正在通話", "進行中", "ongoing"};

    private CallParser() {}

    /** 來電判定：category==CATEGORY_CALL 或 title/subText/text 命中來電關鍵字。 */
    public static boolean isCall(String category, String title, String subText, String text) {
        if (Constants.CATEGORY_CALL.equals(category)) return true;
        String hay = lower(title) + " " + lower(subText) + " " + lower(text);
        for (String k : CALL_KEYWORDS) {
            if (hay.contains(k)) return true;
        }
        return false;
    }

    /** 未接判定：title/subText/text 含 未接／錯過／Missed。 */
    public static boolean isMissed(String title, String subText, String text) {
        String hay = lower(title) + " " + lower(subText) + " " + lower(text);
        for (String k : MISSED_KEYWORDS) {
            if (hay.contains(k)) return true;
        }
        return false;
    }

    /** 通話種類：video｜voice（protocol.md kind 欄位）。 */
    public static String kind(String title, String subText, String text) {
        String hay = lower(title) + " " + lower(subText) + " " + lower(text);
        for (String k : VIDEO_KEYWORDS) {
            if (hay.contains(k)) return "video";
        }
        return "voice";
    }

    /**
     * 通話中偵測（T4 修復 1）：LINE 接聽後，來電通知更新為「通話中」頻道
     * （VoIP.02.Ongoing）而非被移除 → 需立即 end(false)。
     * 頻道比對優先；文字比對排除響鈴樣式文案（語音通話中／視訊通話中）避免誤判。
     */
    public static boolean isOngoing(String channelId, String title, String subText, String text) {
        String ch = lower(channelId);
        for (String k : ONGOING_CHANNEL_KEYWORDS) {
            if (ch.contains(k)) return true;
        }
        String hay = lower(title) + " " + lower(subText) + " " + lower(text);
        if (hay.contains("語音通話中") || hay.contains("視訊通話中")) {
            return false; // 響鈴文案，非通話中狀態
        }
        for (String k : ONGOING_TEXT_KEYWORDS) {
            if (hay.contains(k)) return true;
        }
        return false;
    }

    /** 名稱解析：title → subText → text 首行；去關鍵字；fallback 未知聯絡人。 */
    public static String name(String title, String subText, String text) {
        String[] candidates = {title, subText, firstLine(text)};
        for (String c : candidates) {
            if (c == null) continue;
            String cleaned = strip(c);
            if (!cleaned.isEmpty()) return cleaned;
        }
        return Constants.UNKNOWN_NAME;
    }

    static String firstLine(String text) {
        if (text == null) return null;
        int i = text.indexOf('\n');
        return i < 0 ? text : text.substring(0, i);
    }

    /**
     * 去關鍵字（含 LINE 模板語句如「你有一通未接的語音通話」）→ 英文填充詞（整詞）→
     * 「N通」計數樣式 → 孤立助詞 → 首尾標點空白。T5 §5.6 樣本驅動強化。
     */
    static String strip(String raw) {
        if (raw == null) return "";
        String s = raw;
        for (String k : NAME_KEYWORDS) {
            s = s.replaceAll("(?i)" + Pattern.quote(k), " ");
        }
        s = s.replaceAll("(?i)\\b(?:" + EN_FILLERS + ")\\b", " ");
        s = s.replaceAll("\\d+\\s*通", " ");
        s = s.replaceAll("(?<!\\S)[" + PARTICLES + "](?!\\S)", " ");
        s = s.replaceAll("^[\\p{Punct}\\p{Space}]+|[\\p{Punct}\\p{Space}]+$", "");
        return s.trim();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
