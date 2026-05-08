package com.codex.shopeetaskhook;

import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.UUID;

public class SHPTask {
    public String traceID;
    public String itemID;
    public String shopID;
    public String productURL;
    public String pdpURL;
    public JSONObject rawPayload;

    public JSONObject toJSON() {
        JSONObject obj = new JSONObject();
        try {
            if (traceID != null) obj.put("traceID", traceID);
            if (itemID != null) obj.put("itemID", itemID);
            if (shopID != null) obj.put("shopID", shopID);
            if (productURL != null) obj.put("productURL", productURL);
            if (pdpURL != null) obj.put("pdpURL", pdpURL);
        } catch (Exception ignored) {}
        return obj;
    }

    public static SHPTask fromJSON(JSONObject obj) {
        if (obj == null) return null;
        SHPTask task = new SHPTask();
        task.traceID = optStr(obj, "traceID");
        task.itemID = optStr(obj, "itemID");
        task.shopID = optStr(obj, "shopID");
        task.productURL = optStr(obj, "productURL");
        task.pdpURL = optStr(obj, "pdpURL");
        return task;
    }

    public static SHPTask fromServerResponse(JSONObject root) {
        if (root == null) return null;

        JSONObject payload = root;
        String code = optStr(root, "code");
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            if (code != null && !code.equals("200")) return null;
            payload = data;
        }

        SHPTask task = new SHPTask();
        task.rawPayload = payload;

        task.traceID = firstStr(payload, "taskId", "trace_id", "traceId", "request_id", "requestId", "task_id", "id");
        task.itemID = firstStr(payload, "itemId", "item_id", "itemid");
        task.shopID = firstStr(payload, "shopId", "shop_id", "shopid");
        task.productURL = firstStr(payload, "taskUrl", "task_url", "url", "link", "product_url", "target_url", "jump_url");
        task.pdpURL = firstStr(payload, "pdpUrl", "pdp_url", "detail_url", "detailUrl", "api_url", "apiUrl");

        if (task.productURL != null && !task.productURL.isEmpty()) {
            extractIDsFromURL(task.productURL, task);
            if (task.pdpURL == null && task.productURL.contains("/api/v4/pdp/get")) {
                task.pdpURL = task.productURL;
            }
        }

        if (task.pdpURL != null && !task.pdpURL.isEmpty()) {
            extractIDsFromURL(task.pdpURL, task);
        }

        if (task.productURL == null && task.shopID != null && task.itemID != null) {
            task.productURL = buildProductURL(task.shopID, task.itemID);
        }

        if (task.pdpURL == null && task.shopID != null && task.itemID != null) {
            task.pdpURL = buildPDPURL(task.shopID, task.itemID);
        }

        if (task.traceID == null || task.traceID.isEmpty()) {
            task.traceID = UUID.randomUUID().toString();
        }

        if (task.itemID == null || task.itemID.isEmpty() || task.shopID == null || task.shopID.isEmpty()) {
            return null;
        }

        return task;
    }

    public static String buildProductURL(String shopID, String itemID) {
        if (shopID == null || itemID == null) return null;
        return "https://shopee.tw/product/" + shopID + "/" + itemID;
    }

    public static String buildPDPURL(String shopID, String itemID) {
        if (shopID == null || itemID == null) return null;
        return "https://shopee.tw/api/v4/pdp/get_pc?display_model_id=0&item_id=" + itemID
                + "&model_selection_logic=3&shop_id=" + shopID
                + "&tz_offset_in_minutes=480&detail_level=0";
    }

    private static void extractIDsFromURL(String url, SHPTask task) {
        if (url == null) return;

        java.util.regex.Matcher productMatcher = java.util.regex.Pattern.compile("/product/(\\d+)/(\\d+)").matcher(url);
        if (productMatcher.find()) {
            if (task.shopID == null) task.shopID = productMatcher.group(1);
            if (task.itemID == null) task.itemID = productMatcher.group(2);
        }

        java.util.regex.Matcher itemMatcher = java.util.regex.Pattern.compile("(?:item_id|itemid)=(\\d+)").matcher(url);
        if (itemMatcher.find() && task.itemID == null) {
            task.itemID = itemMatcher.group(1);
        }

        java.util.regex.Matcher shopMatcher = java.util.regex.Pattern.compile("(?:shop_id|shopid)=(\\d+)").matcher(url);
        if (shopMatcher.find() && task.shopID == null) {
            task.shopID = shopMatcher.group(1);
        }
    }

    private static String firstStr(JSONObject obj, String... keys) {
        for (String key : keys) {
            String val = optStr(obj, key);
            if (val != null) return val;
        }
        return null;
    }

    private static String optStr(JSONObject obj, String key) {
        if (!obj.has(key) || obj.isNull(key)) return null;
        String val = obj.optString(key, "").trim();
        return val.isEmpty() ? null : val;
    }
}
