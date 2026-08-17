package com.linewatch.phone;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** protocol.md 訊息建構工具：UTF-8 JSON、一次寫入、≤200 bytes、name ≤60 bytes。 */
public final class Command {

    private Command() {}

    public static String start(String name, String kind) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "start");
            o.put("name", truncateUtf8(name == null ? "" : name, Constants.MAX_NAME_BYTES));
            o.put("kind", "video".equals(kind) ? "video" : "voice");
            return o.toString();
        } catch (JSONException e) {
            return "{\"t\":\"start\",\"name\":\"\",\"kind\":\"voice\"}";
        }
    }

    public static String end(boolean missed) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "end");
            o.put("missed", missed);
            return o.toString();
        } catch (JSONException e) {
            return "{\"t\":\"end\",\"missed\":false}";
        }
    }

    /** protocol.md v1.1：未接判定窗內補送的顯示指令（手錶 IDLE 收到 → 未接畫面 8s 不震動）。 */
    public static String missed(String name, String kind) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "missed");
            o.put("name", truncateUtf8(name == null ? "" : name, Constants.MAX_NAME_BYTES));
            o.put("kind", "video".equals(kind) ? "video" : "voice");
            return o.toString();
        } catch (JSONException e) {
            return "{\"t\":\"missed\",\"name\":\"\",\"kind\":\"voice\"}";
        }
    }

    public static String ping(int seq) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "ping");
            o.put("seq", seq);
            return o.toString();
        } catch (JSONException e) {
            return "{\"t\":\"ping\",\"seq\":0}";
        }
    }

    /** 訊息類型：start/end/ping/pong/ack/unknown（未知欄位忽略、向前相容）。 */
    public static String typeOf(String json) {
        if (json == null) return "unknown";
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("t", "unknown");
            return t == null || t.isEmpty() ? "unknown" : t;
        } catch (JSONException e) {
            return "unknown";
        }
    }

    /** 依 UTF-8 bytes 截斷（name ≤60 bytes；不切斷代理對）。 */
    public static String truncateUtf8(String s, int maxBytes) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int b = ch.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + b > maxBytes) break;
            sb.append(ch);
            bytes += b;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}
