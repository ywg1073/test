package cxyonly.fans;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import cxyonly.fans.db.AppDatabase;
import cxyonly.fans.db.FavoriteQuestion;

/**
 * 收藏题目管理器
 * 通过 JS fetch 获取收藏列表 API，需要从 localStorage 读取 JWT token 鉴权
 * API: GET /api/user/collections?page=1&per_page=50
 * 认证: Authorization: Bearer <daguan_token from localStorage>
 * 响应: {code:0, data:{items:[{id, stem, options, answer, answer_explanation, source, user_state:{mastery, favorited_at}}]}}
 */
public class FavoritesManager {

    private static final String PREFS_NAME = "favorites_prefs";
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final String KEY_DATA_JSON = "favorites_data";
    private static final String KEY_DATA_VERSION = "data_version";
    private static final int CURRENT_VERSION = 2; // v2: JSONTokener 标准解析，修复 LaTeX 反斜杠损坏

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable deferredSave = () -> ioExecutor.execute(() -> saveToDiskNow(null));
    private JSONArray favoritesData = new JSONArray();
    private SyncListener listener;
    private boolean isSyncing = false;

    public interface SyncListener {
        void onProgress(String message);
        void onComplete(int count);
        void onError(String error);
    }

    public FavoritesManager(Context context) {
        this.context = context.getApplicationContext();
        // 优化：将耗时的本地磁盘读取和 JSON 解析迁移到子线程，避免阻塞主线程导致启动卡顿
        ioExecutor.execute(this::loadFromDisk);
    }

    public void setListener(SyncListener listener) { this.listener = listener; }
    public JSONArray getData() { return favoritesData; }
    public int getCount() { return favoritesData.length(); }
    public boolean isSyncing() { return isSyncing; }

    public void shutdown() {
        mainHandler.removeCallbacks(deferredSave);
        ioExecutor.execute(() -> saveToDiskNow(null));
        ioExecutor.shutdown();
    }

    public void markQuestionSynced(String qId) {
        if (qId == null || qId.isEmpty()) return;
        ioExecutor.execute(() -> {
            boolean changed = false;
            synchronized (this) {
                for (int i = 0; i < favoritesData.length(); i++) {
                    JSONObject obj = favoritesData.optJSONObject(i);
                    if (obj != null && qId.equals(obj.optString("id", ""))) {
                        obj.remove("needSync");
                        changed = true;
                        break;
                    }
                }
            }
            if (changed) scheduleSave();
        });
    }

    /**
     * 乐观本地更新题目状态（收藏、熟练度、笔记），无需等网络同步即可立刻反映在收藏列表和首页
     */
    public void updateQuestionStateLocally(String qId, String action, String extraData) {
        if (qId == null || qId.isEmpty()) return;
        ioExecutor.execute(() -> {
            try {
                boolean changed = false;
                synchronized (this) {
                    if ("fav_false".equals(action)) {
                        JSONArray newArr = new JSONArray();
                        for (int i = 0; i < favoritesData.length(); i++) {
                            JSONObject obj = favoritesData.optJSONObject(i);
                            if (obj != null && qId.equals(obj.optString("id", ""))) {
                                changed = true;
                            } else if (obj != null) {
                                newArr.put(obj);
                            }
                        }
                        if (changed) {
                            favoritesData = newArr;
                        }
                    } else if ("fav_true".equals(action)) {
                        boolean exists = false;
                        for (int i = 0; i < favoritesData.length(); i++) {
                            JSONObject obj = favoritesData.optJSONObject(i);
                            if (obj != null && qId.equals(obj.optString("id", ""))) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            AppDatabase db = AppDatabase.getInstance(context);
                            cxyonly.fans.db.QuestionCacheEntity cache = db.questionCacheDao().getById(qId);
                            JSONObject entry = new JSONObject();
                            entry.put("id", qId);
                            entry.put("questionNumber", String.valueOf(favoritesData.length() + 1));
                            if (cache != null && cache.jsonContent != null) {
                                try {
                                    JSONObject cacheJson = new JSONObject(cache.jsonContent);
                                    entry.put("source", cacheJson.optString("source", ""));
                                    entry.put("stemHTML", cacheJson.optString("stemHTML", ""));
                                    entry.put("stemPreview", cacheJson.optString("stemPreview", ""));
                                    if (cacheJson.has("options")) entry.put("options", cacheJson.optJSONArray("options"));
                                    entry.put("correctLabels", cacheJson.optString("correctLabels", ""));
                                    entry.put("optionsHTML", cacheJson.optString("optionsHTML", ""));
                                    entry.put("answerHTML", cacheJson.optString("answerHTML", ""));
                                    entry.put("solutionHTML", cacheJson.optString("solutionHTML", ""));
                                } catch (Exception ignored) {}
                            }
                            entry.put("category", "收藏");
                            entry.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()));
                            entry.put("rawMastery", cache != null && cache.mastery != null ? cache.mastery : "not_started");
                            entry.put("needSync", true);
                            entry.put("lastModifyTime", System.currentTimeMillis());
                            favoritesData.put(entry);
                            changed = true;
                        }
                    } else if ("mastered".equals(action) || "needs_practice".equals(action) || "not_known".equals(action) || "not_started".equals(action) || "familiar".equals(action) || "unknown".equals(action)) {
                        String finalAct = action;
                        if ("familiar".equals(action)) finalAct = "needs_practice";
                        else if ("unknown".equals(action)) finalAct = "not_known";
                        for (int i = 0; i < favoritesData.length(); i++) {
                            JSONObject obj = favoritesData.optJSONObject(i);
                            if (obj != null && qId.equals(obj.optString("id", ""))) {
                                obj.put("rawMastery", finalAct);
                                String masteryLabel = "收藏";
                                switch (finalAct) {
                                    case "needs_practice": masteryLabel = "不熟练"; break;
                                    case "not_known": masteryLabel = "完全不会"; break;
                                    case "mastered": masteryLabel = "掌握"; break;
                                    default: masteryLabel = "收藏"; break;
                                }
                                obj.put("category", masteryLabel);
                                changed = true;
                                break;
                            }
                        }
                    } else if ("note".equals(action)) {
                        for (int i = 0; i < favoritesData.length(); i++) {
                            JSONObject obj = favoritesData.optJSONObject(i);
                            if (obj != null && qId.equals(obj.optString("id", ""))) {
                                obj.put("note", extraData != null ? extraData : "");
                                changed = true;
                                break;
                            }
                        }
                    }
                }
                if (changed) {
                    scheduleSave();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * 静默同步：从 localStorage 读取 daguan_token，带 Bearer 头调用收藏 API
     */
    public void startSync(WebView webView) {
        if (isSyncing || webView == null) return;
        isSyncing = true;

        String js = "javascript:(function(){\n"
                + "var token = localStorage.getItem('daguan_token');\n"
                + "if (!token) {\n"
                + "  window.__favSyncResult = JSON.stringify({error:'not_logged_in'});\n"
                + "  return;\n"
                + "}\n"
                + "var allItems = [];\n"
                + "var page = 1;\n"
                + "var perPage = 50;\n"
                + "function fetchPage(p) {\n"
                + "  return fetch('/api/user/collections?page='+p+'&per_page='+perPage, {\n"
                + "    headers: { 'Authorization': 'Bearer ' + token }\n"
                + "  })\n"
                + "    .then(function(r) { return r.json(); })\n"
                + "    .then(function(d) {\n"
                + "      if (d.code !== 0) return {success:false, error:'API:'+d.code};\n"
                + "      if (d.data && d.data.items && d.data.items.length > 0) {\n"
                + "        allItems = allItems.concat(d.data.items);\n"
                + "        if (d.data.items.length === perPage) {\n"
                + "          return fetchPage(p+1);\n"
                + "        }\n"
                + "      }\n"
                + "      return {success:true, items:allItems};\n"
                + "    });\n"
                + "}\n"
                + "fetchPage(1).then(function(result) {\n"
                + "  window.__favSyncResult = JSON.stringify(result);\n"
                + "}).catch(function(e) {\n"
                + "  window.__favSyncResult = JSON.stringify({success:false, error:e.message});\n"
                + "});\n"
                + "})()";

        webView.evaluateJavascript(js, null);
        mainHandler.postDelayed(() -> pollSyncResult(webView, 0), 1000);
    }

    private void pollSyncResult(WebView webView, int attempt) {
        if (attempt > 6) {
            isSyncing = false;
            if (listener != null) {
                mainHandler.post(() -> listener.onError("同步超时"));
            }
            return;
        }

        webView.evaluateJavascript("window.__favSyncResult", result -> {
            if (result == null || result.equals("null") || result.isEmpty()) {
                mainHandler.postDelayed(() -> pollSyncResult(webView, attempt + 1), 1000);
                return;
            }
            isSyncing = false;
            processSyncResult(result);
        });
    }

    private void processSyncResult(String rawResult) {
        try {
            // WebView evaluateJavascript 会把 JS 字符串再包一层 JSON 字符串。
            // 必须先用 JSONTokener 解析外层字符串，不能手工 replace 反斜杠，否则复杂 LaTeX 会损坏。
            Object parsed = new JSONTokener(rawResult).nextValue();
            String json = parsed instanceof String ? (String) parsed : String.valueOf(parsed);
            json = json.trim();

            JSONObject root = new JSONObject(json);

            // 检查错误
            if (root.has("error")) {
                String err = root.optString("error", "");
                if ("not_logged_in".equals(err)) {
                    // 未登录，静默处理
                    if (listener != null) mainHandler.post(() -> listener.onComplete(0));
                    return;
                }
                if (listener != null) mainHandler.post(() -> listener.onError("同步失败: " + err));
                return;
            }

            if (!root.optBoolean("success", false)) {
                if (listener != null) {
                    mainHandler.post(() -> listener.onError("同步失败"));
                }
                return;
            }

            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) {
                // Preserve locally queued additions until their write request is acknowledged.
                JSONArray pending = new JSONArray();
                synchronized (this) {
                    for (int i = 0; i < favoritesData.length(); i++) {
                        JSONObject item = favoritesData.optJSONObject(i);
                        if (item != null && item.optBoolean("needSync", false)) pending.put(item);
                    }
                    favoritesData = pending;
                }
                saveToDisk();
                if (listener != null) mainHandler.post(() -> listener.onComplete(pending.length()));
                return;
            }

            // 检查是否有本地未同步的操作（needSync=true），如果有，则予以保留
            List<JSONObject> pendingSyncEntries = new ArrayList<>();
            synchronized (this) {
                for (int i = 0; i < favoritesData.length(); i++) {
                    JSONObject oldObj = favoritesData.optJSONObject(i);
                    if (oldObj != null && oldObj.optBoolean("needSync", false)) {
                        pendingSyncEntries.add(oldObj);
                    }
                }
            }

            // 用 API 最新结果重建
            JSONArray newFavData = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                JSONObject entry = new JSONObject();

                String itemId = item.optString("id", "");
                entry.put("id", itemId);
                entry.put("questionNumber", String.valueOf(i + 1));
                entry.put("source", item.optString("source", "未知来源"));

                // 题目：stem 字段是 markdown（含 LaTeX）
                entry.put("stemHTML", item.optString("stem", ""));

                // 题目预览：取 stem 前面的文字部分
                String stem = item.optString("stem", "");
                String preview = stem.replaceAll("\\$\\$[\\s\\S]*?\\$\\$", "")
                        .replaceAll("\\$[\\s\\S]*?\\$", "")
                        .replaceAll("\\\\[a-zA-Z]+", "")
                        .replaceAll("[\\n\\r]+", " ")
                        .trim();
                if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
                entry.put("stemPreview", preview.isEmpty() ? "无题目预览" : preview);

                // 选项：组装成 HTML
                JSONArray options = item.optJSONArray("options");
                if (options != null && options.length() > 0) {
                    JSONObject answerObj = item.optJSONObject("answer");
                    StringBuilder correctLabels = new StringBuilder();
                    if (answerObj != null) {
                        JSONArray correctIds = answerObj.optJSONArray("option_ids");
                        if (correctIds != null && correctIds.length() > 0) {
                            for (int j = 0; j < correctIds.length(); j++) {
                                String optId = correctIds.optString(j, "");
                                for (int k = 0; k < options.length(); k++) {
                                    JSONObject opt = options.optJSONObject(k);
                                    if (opt == null) continue;
                                    if (optId.equals(opt.optString("id", ""))) {
                                        if (correctLabels.length() > 0) correctLabels.append(",");
                                        correctLabels.append(opt.optString("label", ""));
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    entry.put("options", options);
                    entry.put("correctLabels", correctLabels.toString());

                    StringBuilder optsHtml = new StringBuilder();
                    optsHtml.append("<div class=\"options\">");
                    for (int j = 0; j < options.length(); j++) {
                        JSONObject opt = options.optJSONObject(j);
                        if (opt == null) continue;
                        String label = opt.optString("label", "");
                        String content = opt.optString("content_md", opt.optString("content", opt.optString("text", "")));
                        optsHtml.append("<div class=\"option option-item\" data-label=\"").append(label)
                                .append("\" data-correct=\"").append(correctLabels.toString())
                                .append("\" onclick=\"checkOpt(this)\">")
                                .append("<div class=\"olabel opt-label\">").append(label).append("</div>")
                                .append("<div class=\"content opt-text\">").append(content).append("</div>")
                                .append("</div>");
                    }
                    optsHtml.append("</div>");
                    entry.put("optionsHTML", optsHtml.toString());

                    if (answerObj != null) {
                        JSONArray correctIds = answerObj.optJSONArray("option_ids");
                        if (correctIds != null && correctIds.length() > 0) {
                            String cLabelsStr = correctLabels.toString().replace(",", "、");
                            entry.put("answerHTML",
                                    "<p><strong>正确答案：" + cLabelsStr + "</strong></p>");
                        }
                    }
                } else {
                    JSONObject answerObj = item.optJSONObject("answer");
                    if (answerObj != null) {
                        String refAnswer = answerObj.optString("reference_answer_md", "");
                        entry.put("answerHTML", "<p>" + refAnswer + "</p>");
                    }
                    if (entry.optString("answerHTML", "").isEmpty()) {
                        String ca = item.optString("correct_answer", "");
                        if (!ca.isEmpty()) entry.put("answerHTML", "<p>" + ca + "</p>");
                    }
                }

                // 解析
                String explanation = item.optString("answer_explanation", "");
                if (explanation.isEmpty()) {
                    JSONObject doc = item.optJSONObject("document");
                    if (doc != null) {
                        explanation = doc.optString("explanation_md",
                                doc.optString("answer_explanation", ""));
                    }
                }
                entry.put("solutionHTML", explanation);

                // 检查是否有本地待同步的同 ID 覆盖
                JSONObject pendingMatch = null;
                for (JSONObject p : pendingSyncEntries) {
                    if (itemId.equals(p.optString("id", ""))) {
                        pendingMatch = p;
                        break;
                    }
                }

                if (pendingMatch != null) {
                    entry.put("category", pendingMatch.optString("category", "收藏"));
                    entry.put("time", pendingMatch.optString("time", ""));
                    entry.put("rawMastery", pendingMatch.optString("rawMastery", "not_started"));
                    entry.put("note", pendingMatch.optString("note", ""));
                    entry.put("needSync", true);
                    entry.put("lastModifyTime", pendingMatch.optLong("lastModifyTime", System.currentTimeMillis()));
                } else {
                    JSONObject userState = item.optJSONObject("user_state");
                    String masteryRaw = "not_started";
                    String favTime = "";
                    String noteStr = "";
                    if (userState != null) {
                        masteryRaw = userState.optString("mastery", "not_started");
                        favTime = userState.optString("favorited_at", "");
                        noteStr = userState.optString("note", "");
                    }
                    String masteryLabel = masteryRaw;
                    switch (masteryRaw) {
                        case "not_started": masteryLabel = "收藏"; break;
                        case "familiar": masteryLabel = "不熟练"; break;
                        case "unfamiliar": masteryLabel = "完全不会"; break;
                        case "mastered": masteryLabel = "掌握"; break;
                        default: masteryLabel = "收藏"; break;
                    }
                    String rawMastery = masteryRaw;
                    switch (masteryRaw) {
                        case "familiar": rawMastery = "needs_practice"; break;
                        case "unfamiliar": rawMastery = "not_known"; break;
                    }
                    entry.put("category", masteryLabel);
                    entry.put("time", favTime);
                    entry.put("rawMastery", rawMastery);
                    entry.put("rawFavoritedAt", favTime);
                    entry.put("note", noteStr);
                    entry.put("needSync", false);
                    entry.put("lastModifyTime", System.currentTimeMillis());
                }

                String catPath = item.optString("category_name",
                        item.optString("category_full_path", ""));
                entry.put("categoryPath", catPath);

                newFavData.put(entry);
            }

            synchronized (this) {
                favoritesData = newFavData;
            }

            saveToDisk(() -> {
                if (listener != null) {
                    int count = favoritesData.length();
                    listener.onComplete(count);
                }
            });

        } catch (Exception e) {
            if (listener != null) {
                mainHandler.post(() -> listener.onError("解析失败: " + e.getMessage()));
            }
        }
    }

    // ============ 本地存储 (Room 数据库) ============

    private void saveToDisk() {
        scheduleSave();
    }

    private void scheduleSave() {
        mainHandler.removeCallbacks(deferredSave);
        mainHandler.postDelayed(deferredSave, 250);
    }

    private void saveToDisk(Runnable onComplete) {
        ioExecutor.execute(() -> saveToDiskNow(onComplete));
    }

    private void saveToDiskNow(Runnable onComplete) {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_DATA_VERSION, CURRENT_VERSION)
                        .putString(KEY_LAST_SYNC,
                                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()))
                        .apply();

                List<FavoriteQuestion> list = new ArrayList<>();
                synchronized (this) {
                    for (int i = 0; i < favoritesData.length(); i++) {
                        JSONObject entry = favoritesData.optJSONObject(i);
                        if (entry == null) continue;
                        FavoriteQuestion q = new FavoriteQuestion();
                        q.id = entry.optString("id", String.valueOf(i));
                        q.itemOrder = i;
                        q.questionNumber = entry.optString("questionNumber", String.valueOf(i + 1));
                        q.source = entry.optString("source", "");
                        q.stemHTML = entry.optString("stemHTML", "");
                        q.stemPreview = entry.optString("stemPreview", "");
                        q.optionsJson = entry.optJSONArray("options") != null ? entry.optJSONArray("options").toString() : "[]";
                        q.correctLabels = entry.optString("correctLabels", "");
                        q.optionsHTML = entry.optString("optionsHTML", "");
                        q.answerHTML = entry.optString("answerHTML", "");
                        q.solutionHTML = entry.optString("solutionHTML", "");
                        q.category = entry.optString("category", "");
                        q.time = entry.optString("time", "");
                        q.rawMastery = entry.optString("rawMastery", "");
                        q.rawFavoritedAt = entry.optString("rawFavoritedAt", "");
                        q.note = entry.optString("note", "");
                        q.categoryPath = entry.optString("categoryPath", "");
                        q.needSync = entry.optBoolean("needSync", false);
                        q.lastModifyTime = entry.optLong("lastModifyTime", System.currentTimeMillis());
                        list.add(q);
                    }
                }

                AppDatabase db = AppDatabase.getInstance(context);
                db.runInTransaction(() -> {
                    db.favoriteQuestionDao().deleteAll();
                    if (!list.isEmpty()) db.favoriteQuestionDao().insertAll(list);
                });
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (onComplete != null) {
                    mainHandler.post(onComplete);
                }
            }
    }

    private void loadFromDisk() {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            List<FavoriteQuestion> list = db.favoriteQuestionDao().getAll();
            if (list != null && !list.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (FavoriteQuestion q : list) {
                    JSONObject entry = new JSONObject();
                    entry.put("id", q.id);
                    entry.put("questionNumber", q.questionNumber);
                    entry.put("source", q.source);
                    entry.put("stemHTML", q.stemHTML);
                    entry.put("stemPreview", q.stemPreview);
                    if (q.optionsJson != null && !q.optionsJson.isEmpty()) {
                        entry.put("options", new JSONArray(q.optionsJson));
                    } else {
                        entry.put("options", new JSONArray());
                    }
                    entry.put("correctLabels", q.correctLabels);
                    entry.put("optionsHTML", q.optionsHTML);
                    entry.put("answerHTML", q.answerHTML);
                    entry.put("solutionHTML", q.solutionHTML);
                    entry.put("category", q.category);
                    entry.put("time", q.time);
                    entry.put("rawMastery", q.rawMastery);
                    entry.put("rawFavoritedAt", q.rawFavoritedAt);
                    entry.put("note", q.note);
                    entry.put("categoryPath", q.categoryPath);
                    entry.put("needSync", q.needSync);
                    entry.put("lastModifyTime", q.lastModifyTime);
                    arr.put(entry);
                }
                synchronized (this) {
                    favoritesData = arr;
                }
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 兼容迁移：如果 Room 中尚无数据，尝试从 SharedPreferences / 磁盘 JSON 读取并写回 Room
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int savedVersion = sp.getInt(KEY_DATA_VERSION, 0);
        if (savedVersion < CURRENT_VERSION) {
            favoritesData = new JSONArray();
            sp.edit().putInt(KEY_DATA_VERSION, CURRENT_VERSION).apply();
            return;
        }
        try {
            String json = sp.getString(KEY_DATA_JSON, "[]");
            favoritesData = new JSONArray(json);
            if (favoritesData.length() > 0) {
                saveToDisk();
            }
        } catch (Exception e) {
            favoritesData = new JSONArray();
        }
    }

    public String getLastSyncTime() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_SYNC, "从未同步");
    }
}
