package cxyonly.fans;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import cxyonly.fans.db.*;

import cxyonly.fans.view.ProgressWebViewClient;
import cxyonly.fans.view.WebViewConfig;

public class MainActivity extends AppCompatActivity {

    private static final String HOME_URL = "https://cxyonly.fans/";
    private static final String LOGIN_URL = "https://cxyonly.fans/m/login?redirect=/m/home";
    private static final String HOME_PAGE_URL = "https://cxyonly.fans/m/home";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String PREFS_AUTH = "auth_prefs";
    private static final String KEY_TOKEN = "cached_token";
    private static final String KEY_CSRF = "cached_csrf";

    private WebView webView;
    private WebView backgroundWebView;
    private String cachedAuthToken = ""; // 缓存 JWT token，避免从 WebView 异步读取
    private String cachedCsrfToken = ""; // 缓存 CSRF token，API PATCH/PUT 必需
    private SwipeRefreshLayout swipeRefresh;
    private View progressBar;
    private FrameLayout loadingOverlay;
    private FrameLayout errorOverlay;
    private TextView errorMsg;
    private Button retryBtn;
    private LinearLayout topBar;
    private TextView btnFavorites;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isLoading = false;
    private boolean isPageLoaded = false;
    private long lastBackPressTime = 0;
    private long loadingStartTime = 0;
    private boolean isAutoRedirect = false;

    private ClipboardLoginHelper loginHelper;
    private boolean isFirstLoad = true;
    private boolean loginGuideShown = false;
    private boolean weChatOpening = false;
    private boolean isLoggedIn = false;
    private boolean loginCheckInProgress = false;
    private String pendingReturnUrl = "";
    private boolean pullRefreshAllowed = false;

    private java.util.Stack<String> pageHistory = new java.util.Stack<>();
    private FavoritesManager favoritesManager;

    private int currentProgress = 0;
    private static final int PROGRESS_MAX = 100;
    private static final int PROGRESS_ANIM_DELAY = 16;
    private static final long FAV_SYNC_INTERVAL_MS = 2 * 60 * 1000L;
    private static final int MAX_PRECACHE_CATEGORIES = 12;
    private Runnable favoritesSyncRunnable;

    private Runnable loginWatchdogRunnable;
    private Runnable networkRetryRunnable;
    private FavoritesManager.SyncListener originalFavListener;
    private String cachedAppFrontendDataJson;
    private long cachedAppFrontendDataTime = 0L;
    private boolean appFrontendDataLoading = false;
    private static final long APP_FRONTEND_DATA_CACHE_TTL_MS = 60 * 1000L;
    private static final long ACTION_SYNC_GRACE_MS = 30 * 1000L;
    private volatile boolean preCacheScheduled = false;
    private Boolean lastPullRefreshEnabled;
    private long lastPracticeActionTime;

    // ==================== Token/CSRF 持久化 ====================
    private void restoreAuthCache() {
        android.content.SharedPreferences sp = getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE);
        String tok = sp.getString(KEY_TOKEN, "");
        String cs = sp.getString(KEY_CSRF, "");
        if (!tok.isEmpty()) cachedAuthToken = tok;
        if (!cs.isEmpty()) cachedCsrfToken = cs;
        if (!cachedAuthToken.isEmpty()) isLoggedIn = true;
    }
    private void saveAuthCache() {
        getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, cachedAuthToken != null ? cachedAuthToken : "")
            .putString(KEY_CSRF, cachedCsrfToken != null ? cachedCsrfToken : "")
            .apply();
        if (cachedAuthToken != null && !cachedAuthToken.isEmpty()) {
            isLoggedIn = true;
        }
    }
    private void clearAuthCache() {
        getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE).edit().clear().apply();
        cachedAuthToken = "";
        cachedCsrfToken = "";
        isLoggedIn = false;
    }
    private void syncTokenToWebViews(String token, String csrf) {
        if (token != null && !token.isEmpty()) {
            cachedAuthToken = token;
        }
        if (csrf != null && !csrf.isEmpty()) {
            cachedCsrfToken = csrf;
        }
        saveAuthCache();
        if (cachedAuthToken == null || cachedAuthToken.isEmpty()) return;

        final String safeToken = escapeJsString(cachedAuthToken);
        final String safeCsrf = escapeJsString(cachedCsrfToken);

        mainHandler.post(() -> {
            String js = "javascript:(function(){try{"
                + "localStorage.setItem('daguan_token','" + safeToken + "');"
                + (safeCsrf.isEmpty() ? "" : "localStorage.setItem('csrf_token','" + safeCsrf + "');")
                + "}catch(e){}})()";

            if (backgroundWebView != null) {
                backgroundWebView.evaluateJavascript(js, null);
            }
            if (webView != null) {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    @SuppressLint({"SetJavaScriptEnabled", "MissingInflatedId"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        setContentView(R.layout.activity_main);
        setupStatusBar();
        initViews();
        setupTopBar();
        setupWebView();
        setupBackgroundWebView();
        setupSwipeRefresh();
        setupErrorRetry();

        AppLogger.init(getApplicationContext());
        AppLogger.log("SYSTEM", "onCreate started, token=" + (!cachedAuthToken.isEmpty()) + ", csrf=" + (!cachedCsrfToken.isEmpty()));

        loginHelper = new ClipboardLoginHelper(this, webView);
        loginHelper.setListener(new ClipboardLoginHelper.OnLoginCodeListener() {
            @Override public void onCodeDetected(String code) { }
            @Override public void onCodeFilled() { }
        });

        // 从持久化存储恢复 token/csrf（避免重启后 WebView localStorage 丢失）
        restoreAuthCache();

        startSilentStartupCheck();

        // 启动时自动检测更新（有网且静默检测）
        if (WebViewConfig.isNetworkAvailable()) {
            UpdateChecker.check(this);
        }

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void logJs(String tag, String msg) {
                AppLogger.log("JS_" + (tag != null ? tag : "LOG"), msg != null ? msg : "");
            }

            @android.webkit.JavascriptInterface
            public String getLogPath() {
                return AppLogger.getLogFilePath();
            }

            @android.webkit.JavascriptInterface
            public void closeFavorites() {
                mainHandler.post(() -> {
                    cachedAppFrontendDataJson = null;
                    if (!pageHistory.isEmpty()) {
                        String prev = pageHistory.pop();
                        if (prev != null && !prev.startsWith("file:///android_asset/favorites_viewer")
                                && !prev.startsWith("file:///android_asset/practice_frontend")) {
                            webView.loadUrl(prev);
                            return;
                        }
                    }
                    loadAppFrontend();
                });
            }

            @android.webkit.JavascriptInterface
            public String getFavoritesData() {
                if (favoritesManager == null) return "[]";
                return favoritesManager.getData().toString();
            }

            @android.webkit.JavascriptInterface
            public void openFavorites() {
                mainHandler.post(MainActivity.this::openFavoritesViewer);
            }

            @android.webkit.JavascriptInterface
            public void checkUpdate() {
                mainHandler.post(() -> UpdateChecker.checkWithDialog(MainActivity.this));
            }

            @android.webkit.JavascriptInterface
            public void syncFavorites() {
                mainHandler.post(() -> {
                    if (!WebViewConfig.isNetworkAvailable() || !isLoggedIn || favoritesManager == null || getAuthWebView() == null) {
                        return;
                    }
                    favoritesManager.setListener(new FavoritesManager.SyncListener() {
                        @Override public void onProgress(String message) { }
                        @Override public void onComplete(int count) { favoritesManager.setListener(originalFavListener); if (isFavoritesPageVisible()) { webView.evaluateJavascript("javascript:(function(){if(window.updateFavoritesData){window.updateFavoritesData();}})()", null); } }
                        @Override public void onError(String error) { favoritesManager.setListener(originalFavListener); }
                    });
                    favoritesManager.startSync(getAuthWebView());
                });
            }

            @android.webkit.JavascriptInterface
            public void openPractice(String categoryId) {
                mainHandler.post(() -> loadPracticeFrontend(categoryId, null));
            }

            @android.webkit.JavascriptInterface
            public void openPracticeWithQuestion(String categoryId, String questionId) {
                mainHandler.post(() -> loadPracticeFrontend(categoryId, questionId));
            }
            @android.webkit.JavascriptInterface
            public void requestAppData() {
                mainHandler.post(MainActivity.this::fetchAppFrontendData);
            }

            @android.webkit.JavascriptInterface
            public void startPreCache() {
                mainHandler.post(() -> {
                    if (!preCacheScheduled) triggerCategoryPreCache();
                });
            }

            @android.webkit.JavascriptInterface
            public void setPullRefreshEnabled(String enabledFlag) {
                mainHandler.post(() -> {
                    String cur = webView != null ? webView.getUrl() : null;
                    pullRefreshAllowed = "true".equalsIgnoreCase(String.valueOf(enabledFlag))
                            && cur != null && cur.startsWith("file:///android_asset/app_frontend.html");
                    if (swipeRefresh != null && !java.util.Objects.equals(lastPullRefreshEnabled, pullRefreshAllowed)) {
                        lastPullRefreshEnabled = pullRefreshAllowed;
                        swipeRefresh.setEnabled(pullRefreshAllowed);
                    }
                });
            }


            @android.webkit.JavascriptInterface
            public void requestPracticeData(String categoryId) {
                mainHandler.post(() -> fetchPracticeData(categoryId));
            }

            @android.webkit.JavascriptInterface
            public void sessionExpiredFromPractice() {
                mainHandler.post(() -> {
                    if (isLoggedIn) showSessionExpiredDialog();
                });
            }

            @android.webkit.JavascriptInterface
            public void practiceDataLoaded() {
                mainHandler.post(() -> pendingReturnUrl = "");
            }

            @android.webkit.JavascriptInterface
            public void practiceAction(String data) {
                AppLogger.log("JS_INTERFACE", "practiceAction invoked with data=" + data);
                mainHandler.post(() -> performPracticeAction(data, null));
            }

            @android.webkit.JavascriptInterface
            public void recordPractice(String categoryId) {
                mainHandler.post(() -> {
                    if (categoryId == null || categoryId.trim().isEmpty() || getAuthWebView() == null || !WebViewConfig.isNetworkAvailable()) return;
                    warmAuthWebView(() -> {
                        String safeId = escapeJsString(categoryId.trim());
                        String js = "javascript:fetch('/api/user/practice_events',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'practice',category_id:" + safeId + "})}).catch(function(){});";
                        getAuthWebView().evaluateJavascript(js, null);
                    });
                });
            }

            @android.webkit.JavascriptInterface
            public void requestHistoryData() {
                mainHandler.post(MainActivity.this::fetchHistoryData);
            }

            @android.webkit.JavascriptInterface
            public void submitLoginCode(String code) {
                mainHandler.post(() -> submitLoginCodeFromLocalPage(code));
            }

            @android.webkit.JavascriptInterface
            public void fillClipboardCode() {
                mainHandler.post(MainActivity.this::sendClipboardCodeToLoginPage);
            }

            @android.webkit.JavascriptInterface
            public void openWeChatFromLogin() {
                mainHandler.post(MainActivity.this::openWeChat);
            }

            @android.webkit.JavascriptInterface
            public void goHome() {
                mainHandler.post(MainActivity.this::loadAppFrontend);
            }

            @android.webkit.JavascriptInterface
public void goBackFromPractice() {
mainHandler.post(() -> {
cachedAppFrontendDataJson = null;
if (!pageHistory.isEmpty()) {
String prev = pageHistory.pop();
if (prev != null && !prev.startsWith("file:///android_asset/practice_frontend")
&& !prev.startsWith("file:///android_asset/favorites_viewer")) {
webView.loadUrl(prev);
return;
}
}
loadAppFrontend();
});
}

            @android.webkit.JavascriptInterface
            public void logout() {
                mainHandler.post(MainActivity.this::logout);
            }

            @android.webkit.JavascriptInterface
            public void retryMaintenance() {
                mainHandler.post(() -> {
                    if (!WebViewConfig.isNetworkAvailable()) {
                        toast("网络连接不可用，已进入本地模式");
                        loadAppFrontend();
                        return;
                    }
                    showLoadingOverlay(true);
                    WebView authView = getAuthWebView();
                    if (authView != null) {
                        authView.loadUrl(HOME_PAGE_URL);
                        mainHandler.postDelayed(() -> {
                            showLoadingOverlay(false);
                            String mJs = "javascript:(function(){var b=document.body?document.body.innerText:'';if(b.indexOf('\\u7ef4\\u62a4')>=0||b.indexOf('\\u5347\\u7ea7')>=0)return'maintenance';return'ok';})()";
                            authView.evaluateJavascript(mJs, mResult -> {
                                String mr = mResult != null ? mResult.replaceAll("\"", "") : "ok";
                                if ("maintenance".equals(mr)) {
                                    toast("网站仍在维护中，可使用本地缓存刷题");
                                    loadMaintenanceFrontend();
                                } else {
                                    toast("网络服务已恢复");
                                    cachedAppFrontendDataJson = null;
                                    loadAppFrontend();
                                }
                            });
                        }, 1200);
                    } else {
                        showLoadingOverlay(false);
                        loadAppFrontend();
                    }
                });
            }

            @android.webkit.JavascriptInterface
            public void openWebHome() {
                mainHandler.post(() -> {
                    cachedAppFrontendDataJson = null;
                    loadAppFrontend();
                });
            }
        }, "Android");

        favoritesManager = new FavoritesManager(this);
        originalFavListener = new FavoritesManager.SyncListener() {
            @Override public void onProgress(String message) { }
            @Override public void onComplete(int count) { updateTopBarVisibility(); }
            @Override public void onError(String error) { }
        };
        favoritesManager.setListener(originalFavListener);
        updateTopBarVisibility();
    }

    // ==================== 状态栏 ====================
    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) ctrl.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.topBar), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
    }

    private void initViews() {
        progressBar = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        webView = findViewById(R.id.webView);
        webView.setBackgroundColor(0xFF1A1A2E); // 与 splash_bg 一致，避免启动时白屏过渡
        backgroundWebView = findViewById(R.id.backgroundWebView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        errorOverlay = findViewById(R.id.errorOverlay);
        errorMsg = findViewById(R.id.errorMsg);
        retryBtn = findViewById(R.id.retryBtn);
        topBar = findViewById(R.id.topBar);
        btnFavorites = findViewById(R.id.btnFavorites);
        progressBar.setVisibility(View.GONE);
    }

    private void setupTopBar() {
        if (btnFavorites != null) btnFavorites.setVisibility(View.GONE);
    }

    private void updateTopBarVisibility() {
        if (btnFavorites != null) btnFavorites.setVisibility(View.GONE);
    }

    // ==================== WebView ====================
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebViewConfig.configure(webView);
        // 允许 file:// 资产页面直接 fetch https:// API（避免跨域限制）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
            webView.getSettings().setAllowFileAccessFromFileURLs(true);
        }
        webView.setLayerType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_SOFTWARE, null);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) { super.onProgressChanged(view, newProgress); animateProgressBar(newProgress); }
        });
        webView.setWebViewClient(new ProgressWebViewClient(new ProgressWebViewClient.OnPageCallback() {
            @Override public void onProgressChanged(int progress) { animateProgressBar(progress); }
            @Override
            public void onPageStarted(String url) {
                isLoading = true; isPageLoaded = false; loadingStartTime = System.currentTimeMillis();
                boolean isLocalAsset = url != null && url.startsWith("file:///android_asset/");
                if (!isLocalAsset) {
                    showLoadingOverlay(true); 
                    progressBar.setVisibility(View.VISIBLE);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
                errorOverlay.setVisibility(View.GONE);
                if (!isLoggedIn && url.contains("/login")) {
                    mainHandler.postDelayed(() -> { if (loginHelper != null) loginHelper.checkAndFillLoginCode(); }, 800);
                }
            }
            @Override
            public void onPageFinished(String url) {
                isLoading = false; isPageLoaded = true;
                updatePullRefreshForUrl(url);
                if (loginCheckInProgress) { } else {
                    long elapsed = System.currentTimeMillis() - loadingStartTime;
                    long delay = isAutoRedirect ? Math.max(80, 1000 - elapsed) : 80;
                    mainHandler.postDelayed(() -> { showLoadingOverlay(false); animateProgressBar(PROGRESS_MAX); mainHandler.postDelayed(() -> progressBar.setVisibility(View.GONE), 300); }, delay);
                }
                isAutoRedirect = false;
                if (isFirstLoad && url.startsWith("http")) { isFirstLoad = false; handleHomePageLoaded(); }
            }
            @Override
            public void onPageError(String errorInfo) { isLoading = false; showLoadingOverlay(false); progressBar.setVisibility(View.GONE); toast("\u26a0\ufe0f 网络连接异常"); }
        }));
    }

    private void setupBackgroundWebView() {
        if (backgroundWebView == null) return;
        WebViewConfig.configure(backgroundWebView);
        backgroundWebView.setWebChromeClient(new WebChromeClient());
        backgroundWebView.setWebViewClient(new android.webkit.WebViewClient());
        backgroundWebView.loadUrl(HOME_URL);
    }

    private void startSilentStartupCheck() {
        isAutoRedirect = true;
        isFirstLoad = false;
        WebView authView = getAuthWebView();
        if (authView != null) authView.loadUrl(HOME_URL);

        // 【优化】：如果有缓存，直接静默加载主页，不再展示 Loading 过渡
        if (!cachedAuthToken.isEmpty()) {
            isLoggedIn = true;
            loginGuideShown = false;
            showLoadingOverlay(false);
            loadAppFrontend();
            
            // 后台静默检测维护和 Token 更新
            mainHandler.postDelayed(this::silentMaintenanceCheck, 1500);
        } else {
            // 没有缓存时，稍微走一下正常的加载检查逻辑并展示 Loading
            showLoadingOverlay(true);
            loadingStartTime = System.currentTimeMillis();
            mainHandler.postDelayed(this::handleHomePageLoaded, 900);
        }
    }

    private void silentMaintenanceCheck() {
        if (!WebViewConfig.isNetworkAvailable()) return;
        String mJs = "javascript:(function(){var b=document.body?document.body.innerText:'';if(b.indexOf('\u7ef4\u62a4')>=0||b.indexOf('\u5347\u7ea7')>=0)return'maintenance';return'ok';})()";
        WebView authView = getAuthWebView();
        if (authView == null) return;
        authView.evaluateJavascript(mJs, mResult -> {
            String mr = mResult != null ? mResult.replaceAll("\"", "") : "ok";
            if ("maintenance".equals(mr)) { 
                loadMaintenanceFrontend(); 
                return; 
            }
            // 维护检查通过后，静默验证Token
            checkLoginSilent();
        });
    }

    private void checkLoginSilent() {
        warmAuthWebView(() -> {
            String js = "javascript:(function(){var token=localStorage.getItem('daguan_token')||'';var csrf=localStorage.getItem('csrf_token')||'';return JSON.stringify({logged_in:!!token,token:token,csrf:csrf});})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                try {
                    String jsonStr = result != null ? result : "{}";
                    if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                        jsonStr = jsonStr.substring(1, jsonStr.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                    }
                    org.json.JSONObject info = new org.json.JSONObject(jsonStr);
                    boolean loggedIn = info.optBoolean("logged_in", false);
                        if (loggedIn) {
                        String tok = info.optString("token", "");
                        String cs = info.optString("csrf", "");
                        if (!tok.isEmpty()) cachedAuthToken = tok;
                        if (!cs.isEmpty()) cachedCsrfToken = cs;
                        saveAuthCache();
                        if (!tok.isEmpty()) {
                            mainHandler.postDelayed(this::fetchAndCacheCsrf, 500);
                        }
                        mainHandler.postDelayed(() -> { if (WebViewConfig.isNetworkAvailable()) { startLoginWatchdog(); startFavoritesPeriodicSync(); } }, 1000);
                    } else {
                        // 同步我们存储的缓存到 WebView
                        warmAuthWebView(() -> {
                            WebView av = getAuthWebView();
                            if (av != null) {
                                String j = "javascript:(function(){localStorage.setItem('daguan_token','"+escapeJsString(cachedAuthToken)+"');})()";
                                av.evaluateJavascript(j, null);
                                fetchAndCacheCsrf();
                            }
                        });
                    }
                } catch (Exception ignored) {}
            });
        });
    }

    // ==================== 首页 → 维护检测 → 自动路由 ====================
    private void handleHomePageLoaded() {
        String mJs = "javascript:(function(){var b=document.body?document.body.innerText:'';if(b.indexOf('\u7ef4\u62a4')>=0||b.indexOf('\u5347\u7ea7')>=0)return'maintenance';return'ok';})()";
        WebView authView = getAuthWebView();
        if (authView == null) return;
        authView.evaluateJavascript(mJs, mResult -> {
            String mr = mResult != null ? mResult.replaceAll("\"", "") : "ok";
            if ("maintenance".equals(mr)) { loadMaintenanceFrontend(); return; }
            checkLoginAndRoute();
        });
    }

    private void checkLoginAndRoute() {
        if (!WebViewConfig.isNetworkAvailable()) {
            showLoadingOverlay(false);
            toast("\u26a0\ufe0f 网络连接异常");
            // 断网时仍然加载本地前端，保证用户能看到 UI（收藏、底部功能栏等）
            loadAppFrontend();
            startNetworkRetry();
            return;
        }
        warmAuthWebView(() -> {
            String js = "javascript:(function(){var token=localStorage.getItem('daguan_token')||'';var csrf=localStorage.getItem('csrf_token')||'';return JSON.stringify({logged_in:!!token,token:token,csrf:csrf});})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                try {
                    String jsonStr = result != null ? result : "{}";
                    if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                        jsonStr = jsonStr.substring(1, jsonStr.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                    }
                    org.json.JSONObject info = new org.json.JSONObject(jsonStr);
                    boolean loggedIn = info.optBoolean("logged_in", false);
                    if (loggedIn) {
                        String tok = info.optString("token", "");
                        String cs = info.optString("csrf", "");
                        syncTokenToWebViews(tok, cs);
                        isLoggedIn = true; loginGuideShown = false; showLoadingOverlay(false); isFirstLoad = false; isAutoRedirect = false;
                        if (pendingReturnUrl != null && !pendingReturnUrl.isEmpty()) {
                            String target = refreshPracticeReturnUrlToken(pendingReturnUrl, cachedAuthToken);
                            pendingReturnUrl = "";
                            webView.loadUrl(target);
                        } else {
                            loadAppFrontend();
                        }
                        mainHandler.postDelayed(() -> { if (WebViewConfig.isNetworkAvailable()) { startLoginWatchdog(); startFavoritesPeriodicSync(); } }, 1000);
                    } else {
                        // WebView localStorage 无 token，但 SharedPreferences 有缓存
                        if (!cachedAuthToken.isEmpty()) {
                            syncTokenToWebViews(cachedAuthToken, cachedCsrfToken);
                            isLoggedIn = true; loginGuideShown = false; showLoadingOverlay(false);
                            loadAppFrontend();
                        } else {
                            startSilentLoginCheck();
                        }
                    }
                } catch (Exception e) {
                    startSilentLoginCheck();
                }
            });
        });
    }

    private WebView getAuthWebView() {
        return backgroundWebView != null ? backgroundWebView : webView;
    }

    private void warmAuthWebView(Runnable afterWarm) {
        WebView authView = getAuthWebView();
        if (authView == null) return;
        // 断网时不尝试加载页面或发请求
        if (!WebViewConfig.isNetworkAvailable()) { if (afterWarm != null) afterWarm.run(); return; }
        String url = authView.getUrl();
        if (url == null || !url.startsWith("https://cxyonly.fans")) {
            authView.loadUrl(HOME_PAGE_URL);
            mainHandler.postDelayed(() -> warmAuthWebView(afterWarm), 900);
        } else {
            authView.evaluateJavascript("javascript:(function(){fetch('/api/site/math-home-config').catch(function(){});return localStorage.getItem('daguan_token')?'warm_token':'warm';})()", r -> mainHandler.postDelayed(afterWarm, 300));
        }
    }

    // 启动时做一次轻量 auth 检测：取消收藏同步 → 发测试请求 → 有问题弹窗 → 没问题恢复同步
    private void performStartupAuthCheck() {
        if (!WebViewConfig.isNetworkAvailable() || getAuthWebView() == null) {
            // 断网时跳过检测，直接启动定时收藏同步和看门狗
            startFavoritesPeriodicSync();
            return;
        }
        // 先取消定时的收藏同步（避免检测过程中冲突）
        stopFavoritesPeriodicSync();
        // 先预热后台 WebView，确保在 cxyonly.fans 域上执行 JS
        warmAuthWebView(() -> {
            // 用轻量 API 验证 token 是否有效（GET /api/user/total_stats 需要认证）
            String js = "javascript:(function(){"
                + "var token=localStorage.getItem('daguan_token')||'" + escapeJsString(cachedAuthToken) + "';"
                + "var h=token?{'Authorization':'Bearer '+token}:{};"
                + "try{var x=new XMLHttpRequest();"
                + "x.open('GET','/api/user/total_stats',false);"
                + "Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});"
                + "x.send();"
                + "return JSON.stringify({status:x.status,ok:x.status>=200&&x.status<300});"
                + "}catch(e){return JSON.stringify({status:0,ok:false,error:e.message});}"
                + "})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                try {
                    String jsonStr = result != null ? result : "{}";
                    if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                        jsonStr = jsonStr.substring(1, jsonStr.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                    }
                    org.json.JSONObject r = new org.json.JSONObject(jsonStr);
                    if (r.optBoolean("ok", false) || r.optInt("status", 0) != 401) {
                        startFavoritesPeriodicSync();
                    } else {
                        showSessionExpiredDialog();
                    }
                } catch (Exception ignored) {
                    startFavoritesPeriodicSync();
                }
            });
        });
    }

    private void loadAppFrontend() {
        if (webView == null) return;
        isAutoRedirect = false;
        showLoadingOverlay(false);
        pendingReturnUrl = "";
        pageHistory.clear();
        int favoriteCount = favoritesManager != null ? favoritesManager.getCount() : 0;
        String json = "{\"favoritesCount\":" + favoriteCount + "}";
        String b64 = android.util.Base64.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        webView.loadUrl("file:///android_asset/app_frontend.html#data=" + b64);
    }

    private void loadLoginFrontend() {
        if (webView == null) return;
        isAutoRedirect = false;
        showLoadingOverlay(false);
        webView.loadUrl("file:///android_asset/login_frontend.html");
        mainHandler.postDelayed(this::sendClipboardCodeToLoginPage, 700);
    }

    private void loadMaintenanceFrontend() {
        if (webView == null) return;
        isAutoRedirect = false;
        showLoadingOverlay(false);
        stopLoginWatchdog();
        stopFavoritesPeriodicSync();
        int favCount = favoritesManager != null ? favoritesManager.getCount() : 0;
        String json = "{\"favoritesCount\":" + favCount + "}";
        String b64 = android.util.Base64.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        webView.loadUrl("file:///android_asset/maintenance_frontend.html#data=" + b64);
    }

    private void fetchAppFrontendData() {
        if (webView == null) return;

        long now = System.currentTimeMillis();
        if (cachedAppFrontendDataJson != null && now - cachedAppFrontendDataTime < APP_FRONTEND_DATA_CACHE_TTL_MS) {
            sendAppFrontendData(cachedAppFrontendDataJson);
            return;
        }
        if (appFrontendDataLoading) return;
        appFrontendDataLoading = true;

        // 1. 【缓存优先模式】：优先从 Room 数据库异步读取本地 App 缓存并立刻渲染 UI（避免空白加载页）
        new Thread(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                AppCacheEntity entity = db.appCacheDao().getByKey("app_frontend_data");
                if (entity != null && entity.jsonContent != null && !entity.jsonContent.isEmpty()) {
                    mainHandler.post(() -> sendAppFrontendData(entity.jsonContent));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        // 2. 若网络不可用或 Webview 未就绪，使用 Room 缓存，不弹窗报错
        if (getAuthWebView() == null || !WebViewConfig.isNetworkAvailable()) {
            appFrontendDataLoading = false;
            return;
        }
        warmAuthWebView(() -> {
            // 同步 XMLHttpRequest + 从 localStorage 取 token/csrf + 加 Authorization 头
            String js = "javascript:(function(){"
                + "var token=localStorage.getItem('daguan_token')||'" + escapeJsString(cachedAuthToken) + "';"
                + "var csrf=localStorage.getItem('csrf_token')||'" + escapeJsString(cachedCsrfToken) + "';"
                + "var r={success:true,access_token:token,csrf_token:csrf,auth_failed:false};"
                + "var h=token?{'Authorization':'Bearer '+token}:{};"
                + "if(csrf)h['X-CSRF-Token']=csrf;"
                + "try{var x=new XMLHttpRequest();"
                + "x.open('GET','/api/site/math-home-config',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();"
                + "if(x.status==401){r.auth_failed=true;}else{r.home=JSON.parse(x.responseText);}"
                + "if(!r.auth_failed){"
                + "x.open('GET','/api/categories?include_stats=true',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();if(x.status==401){r.auth_failed=true;}else{r.categories=JSON.parse(x.responseText);}"
                + "}"
                + "if(!r.auth_failed){"
                + "x.open('GET','/api/questions/user/last_study',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();if(x.status==401){r.auth_failed=true;}else{r.last_study=JSON.parse(x.responseText);}"
                + "}"
                + "if(!r.auth_failed){"
                + "x.open('GET','/api/user/daily_stats',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();if(x.status==401){r.auth_failed=true;}else{r.daily_stats=JSON.parse(x.responseText);}"
                + "}"
                + "if(!r.auth_failed){"
                + "x.open('GET','/api/user/total_stats',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();if(x.status==401){r.auth_failed=true;}else{r.total_stats=JSON.parse(x.responseText);}"
                + "}"
                + "}catch(e){r.success=false;r.error=e.message;}"
                + "return JSON.stringify(r);"
                + "})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                try {
                    Object parsed = new org.json.JSONTokener(result).nextValue();
                    String jsonStr = parsed instanceof String ? (String) parsed : String.valueOf(parsed);
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(jsonStr);
                        // 仅当 API 明确返回 401 时认定 token 失效
                        if (obj.optBoolean("auth_failed", false)) {
                            cachedAuthToken = "";
                            cachedCsrfToken = "";
                            clearAuthCache();
                            appFrontendDataLoading = false;
                            showSessionExpiredDialog();
                            return;
                        }
                        // 响应包含 token / csrf 时更新
                        if (obj.has("access_token")) {
                            String t = obj.optString("access_token", "");
                            if (!t.isEmpty()) { cachedAuthToken = t; }
                        }
                        if (obj.has("csrf_token")) {
                            String c = obj.optString("csrf_token", "");
                            if (!c.isEmpty()) { cachedCsrfToken = c; }
                        }
                        // 若本地有 Token，主动回注 WebView
                        if (!cachedAuthToken.isEmpty()) {
                            syncTokenToWebViews(cachedAuthToken, cachedCsrfToken);
                        }
                        // 注入收藏数
                        obj.put("favoritesCount", favoritesManager != null ? favoritesManager.getCount() : 0);
                        jsonStr = obj.toString();
                    } catch (Exception ignored) {}

                    final String finalAppJson = jsonStr;
                    cachedAppFrontendDataJson = finalAppJson;
                    cachedAppFrontendDataTime = System.currentTimeMillis();
                    appFrontendDataLoading = false;

                    // 后台静默更新 Room 持久化缓存
                    new Thread(() -> {
                        try {
                            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                            AppCacheEntity cache = new AppCacheEntity();
                            cache.cacheKey = "app_frontend_data";
                            cache.jsonContent = finalAppJson;
                            cache.lastModifyTime = System.currentTimeMillis();
                            db.appCacheDao().insert(cache);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();

                    sendAppFrontendData(finalAppJson);
                } catch (Exception e) {
                    appFrontendDataLoading = false;
                    // 网络波动解析失败时不覆盖 Room 缓存，保持页面不空白
                }
            });
        });
    }

    private void sendAppFrontendData(String json) {
        if (webView == null) return;
        String b64 = android.util.Base64.encodeToString((json == null ? "{}" : json).getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        webView.evaluateJavascript("javascript:(function(){if(window.onAppData){window.onAppData('" + b64 + "');}})()", null);
    }

    private void submitLoginCodeFromLocalPage(String code) {
        AppLogger.log("LOGIN_START", "submitLoginCodeFromLocalPage called");
        if (code == null || code.trim().isEmpty()) {
            notifyLoginPage("请输入登录码", false);
            return;
        }
        if (!WebViewConfig.isNetworkAvailable()) {
            AppLogger.log("LOGIN_ERR", "Network unavailable during login");
            notifyLoginPage("网络连接异常", false);
            return;
        }
        final String finalCode = code.trim();
        notifyLoginPage("正在登录…", true);
        // 用新线程执行 HTTP 请求，不阻塞 UI
        new Thread(() -> {
            try {
                URL url = new URL("https://cxyonly.fans/api/auth/login");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                String body = "{\"verification_code\":\"" + escapeJsonString(finalCode) + "\",\"login_mode\":\"new\"}";
                OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), "UTF-8");
                writer.write(body);
                writer.flush();
                writer.close();
                int httpCode = conn.getResponseCode();
                AppLogger.log("LOGIN_HTTP", "Login HTTP status: " + httpCode);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();
                conn.disconnect();
                String json = response.toString();
                AppLogger.log("LOGIN_RESP", json);
                // 解析 JSON 响应
                org.json.JSONObject root = new org.json.JSONObject(json);
                int codeVal = root.optInt("code", -1);
                if (codeVal == 0) {
                    org.json.JSONObject data = root.optJSONObject("data");
                    if (data == null) data = root;
                    String token = data.optString("access_token", "");
                    if (token.isEmpty()) token = data.optString("token", "");
                    if (!token.isEmpty()) {
                        final String finalToken = token;
                        final String finalUser = data.has("user") ? data.get("user").toString() : "";
                        final String finalCsrf = data.optString("csrf_token", "");
                        AppLogger.log("LOGIN_SUCCESS", "Got token length=" + finalToken.length() + " csrf=" + finalCsrf);
                        // 回到主线程：先预热后台 WebView，写入 token，再走统一检测路由
                        mainHandler.post(() -> finishLoginWithToken(finalToken, finalUser, finalCsrf));
                        return;
                    }
                }
                // 登录失败
                final String errMsg = root.optString("message", root.optString("error", "登录失败（" + httpCode + "）"));
                AppLogger.log("LOGIN_FAIL", errMsg);
                mainHandler.post(() -> notifyLoginPage(errMsg, false));
            } catch (Exception e) {
                AppLogger.log("LOGIN_EX", e.toString());
                final String err = e.getMessage() != null ? e.getMessage() : "网络请求异常";
                mainHandler.post(() -> notifyLoginPage(err, false));
            }
        }).start();
    }

    private void finishLoginWithToken(String token, String user, String csrf) {
// 0. 缓存 token 和 csrf 供后续直连 HTTP 使用
cachedAuthToken = token != null ? token : "";
cachedCsrfToken = csrf != null ? csrf : "";
saveAuthCache();
        // 1. 预热：确保后台 WebView 已在 cxyonly.fans 域
        warmAuthWebView(() -> {
            // 2. 现在域已正确，写入 token 到 localStorage
            WebView authView = getAuthWebView();
            if (authView == null) {
                notifyLoginPage("登录环境未就绪", false);
                return;
            }
            StringBuilder js = new StringBuilder();
            js.append("javascript:(function(){");
            js.append("localStorage.setItem('daguan_token','").append(escapeJsString(token)).append("');");
            if (user != null && !user.isEmpty()) {
                js.append("try{localStorage.setItem('daguan_user',").append(user).append(");}catch(e){}");
            }
            if (csrf != null && !csrf.isEmpty()) {
                js.append("localStorage.setItem('csrf_token','").append(escapeJsString(csrf)).append("');");
            }
            js.append("return 'ok';})()");
            authView.evaluateJavascript(js.toString(), null);
            // 3. 登录后立即获取 csrf token 并缓存
            mainHandler.postDelayed(() -> fetchAndCacheCsrf(), 600);
            // 4. 写入后稍等片刻，调用 checkLoginAndRoute 检测 token 并跳转
            mainHandler.postDelayed(this::checkLoginAndRoute, 400);
        });
    }

    private void notifyLoginPage(String message, boolean success) {
        if (webView == null) return;
        String js = "javascript:(function(){if(window.onNativeLoginResult){window.onNativeLoginResult('"
                + escapeJsString(message == null ? "" : message) + "'," + success + ");}})()";
        webView.evaluateJavascript(js, null);
    }

    private void sendClipboardCodeToLoginPage() {
        if (webView == null) return;
        String code = readLoginCodeFromClipboard();
        if (code == null || code.isEmpty()) {
            notifyLoginPage("剪贴板未检测到登录码", false);
            return;
        }
        String js = "javascript:(function(){if(window.fillLoginCode){window.fillLoginCode('" + escapeJsString(code) + "');}})()";
        webView.evaluateJavascript(js, null);
    }

    private String readLoginCodeFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return null;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) return null;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("登录码[：:]\\s*([a-zA-Z0-9]+)").matcher(clip.getItemAt(0).getText().toString());
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void loadPracticeFrontend(String categoryId, String questionId) {
        if (webView == null || categoryId == null || categoryId.trim().isEmpty()) return;
        refreshCsrfViaApi(null);
        String cur = webView.getUrl();
        if (cur != null && !cur.startsWith("file:///android_asset/practice_frontend")) {
            pageHistory.push(cur);
        }
        pendingReturnUrl = buildPracticeReturnUrl(categoryId, questionId, cachedAuthToken);
        isAutoRedirect = false;
        showLoadingOverlay(false);
        // 先从 auth WebView 获取 access_token 和 csrf_token，再加载练习页
        final WebView authView = getAuthWebView();
        if (authView != null) {
            String js = "javascript:(function(){return JSON.stringify({token:localStorage.getItem('daguan_token')||'',csrf:localStorage.getItem('csrf_token')||''});})()";
            authView.evaluateJavascript(js, tokenResult -> {
                String token = "";
                String csrf = "";
                try {
                    String jsonStr = tokenResult != null ? tokenResult : "{}";
                    if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                        jsonStr = jsonStr.substring(1, jsonStr.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                    }
                    org.json.JSONObject info = new org.json.JSONObject(jsonStr);
                    token = info.optString("token", "");
                    csrf = info.optString("csrf", "");
                } catch (Exception ignored) {}
                // ★ 关键修复：WebView 的 token 被 Vue 清掉，但 Java 有缓存 → 回注回 WebView
                if (token.isEmpty() && !cachedAuthToken.isEmpty()) {
                    token = cachedAuthToken;
                    String injectJs = "javascript:(function(){"
                        + "localStorage.setItem('daguan_token','" + escapeJsString(token) + "');"
                        + "return 'ok';})()";
                    authView.evaluateJavascript(injectJs, null);
                }
                // ★ 双重保障：如果都为空，从 SharedPreferences 再读一次
                if (token.isEmpty()) {
                    restoreAuthCache();
                    if (!cachedAuthToken.isEmpty()) {
                        token = cachedAuthToken;
                    }
                }
                if (!token.isEmpty()) cachedAuthToken = token;
                if (!csrf.isEmpty()) { cachedCsrfToken = csrf; saveAuthCache(); }
                pendingReturnUrl = buildPracticeReturnUrl(categoryId, questionId, token);
                StringBuilder json = new StringBuilder();
                json.append("{\"categoryId\":\"").append(escapeJsonString(categoryId.trim())).append("\"");
                json.append(",\"access_token\":\"").append(escapeJsonString(token)).append("\"");
                if (questionId != null && !questionId.trim().isEmpty()) {
                    json.append(",\"questionId\":\"").append(escapeJsonString(questionId.trim())).append("\"");
                }
                json.append("}");
                String b64 = android.util.Base64.encodeToString(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                webView.loadUrl("file:///android_asset/practice_frontend.html#data=" + b64);
                fetchPracticeData(categoryId);
            });
        } else {
            StringBuilder json = new StringBuilder();
            json.append("{\"categoryId\":\"").append(escapeJsonString(categoryId.trim())).append("\"");
            if (questionId != null && !questionId.trim().isEmpty()) {
                json.append(",\"questionId\":\"").append(escapeJsonString(questionId.trim())).append("\"");
            }
            json.append("}");
            String b64 = android.util.Base64.encodeToString(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            pendingReturnUrl = "file:///android_asset/practice_frontend.html#data=" + b64;
            webView.loadUrl("file:///android_asset/practice_frontend.html#data=" + b64);
            fetchPracticeData(categoryId);
        }
    }

    private void fetchPracticeData(String categoryId) {
        if (categoryId == null || categoryId.trim().isEmpty()) return;
        refreshCsrfViaApi(null);
        String safeId = categoryId.trim();

        // 1. 【缓存优先模式】：优先从 Room 数据库异步读取本地题目分类缓存并立刻渲染 UI
        new Thread(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                PracticeCacheEntity entity = db.practiceCacheDao().getByCategory(safeId);
                if (entity != null && entity.jsonContent != null && !entity.jsonContent.isEmpty()) {
                    String enriched = enrichPracticeJsonWithLocalState(entity.jsonContent);
                    mainHandler.post(() -> sendPracticeData(enriched));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        // 2. 网络不可用或后台 WebView 未准备好，仅使用 Room 缓存，不做崩溃报错
        if (getAuthWebView() == null || !WebViewConfig.isNetworkAvailable()) {
            return;
        }

        final WebView authView = getAuthWebView();
        Runnable doFetch = () -> {
            String ensureTokenJs = "javascript:(function(){"
                + "var tk=localStorage.getItem('daguan_token');"
                + "if(!tk || tk==''){localStorage.setItem('daguan_token','" + escapeJsString(cachedAuthToken) + "');}"
                + "return 'ok';})()";
            authView.evaluateJavascript(ensureTokenJs, null);
            String js = "javascript:(function(){"
                + "var token=localStorage.getItem('daguan_token')||'" + escapeJsString(cachedAuthToken) + "';"
                + "var h=token?{'Authorization':'Bearer '+token}:{};"
                + "try{var x=new XMLHttpRequest();"
                + "x.open('GET','/api/questions?category_id=" + escapeJsString(safeId) + "&include_children=true&page=1&per_page=20&sort=mobile',false);"
                + "Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});"
                + "x.send();if(x.status==401)return JSON.stringify({auth_failed:true,status:x.status});return x.responseText;"
                + "}catch(e){return JSON.stringify({success:false,error:e.message});}"
                + "})()";
            authView.evaluateJavascript(js, result -> {
                try {
                    Object parsed = new org.json.JSONTokener(result).nextValue();
                    String jsonStr = parsed instanceof String ? (String) parsed : String.valueOf(parsed);
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(jsonStr);
                        if (obj.optBoolean("auth_failed", false)) {
                            showSessionExpiredDialog();
                            return;
                        }
                    } catch (Exception ignored) { }

                    final String finalPracticeJson = jsonStr;

                    // 写入 Room 本地分类题目数据库缓存
                    new Thread(() -> {
                        try {
                            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                            PracticeCacheEntity cache = new PracticeCacheEntity();
                            cache.categoryId = safeId;
                            cache.jsonContent = finalPracticeJson;
                            cache.lastModifyTime = System.currentTimeMillis();
                            db.practiceCacheDao().insert(cache);
                            saveQuestionsToRoomCache(finalPracticeJson);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();

                    String enriched = enrichPracticeJsonWithLocalState(finalPracticeJson);
                    sendPracticeData(enriched);
                } catch (Exception e) {
                    // 网络波动失败时不覆盖 Room 本地数据
                }
            });
        };
        String url = authView.getUrl();
        if (url != null && url.startsWith("https://cxyonly.fans")) {
            doFetch.run();
        } else {
            authView.loadUrl(HOME_PAGE_URL);
            mainHandler.postDelayed(doFetch, 900);
        }
    }

    private void sendPracticeData(String json) {
        if (webView == null) return;
        String b64 = android.util.Base64.encodeToString((json == null ? "{}" : json).getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        webView.evaluateJavascript("javascript:(function(){if(window.onPracticeData){window.onPracticeData('" + b64 + "');}})()", null);
    }

    private String buildPracticeReturnUrl(String categoryId, String questionId, String token) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\"categoryId\":\"").append(escapeJsonString(categoryId.trim())).append("\"");
            if (token != null && !token.trim().isEmpty()) {
                json.append(",\"access_token\":\"").append(escapeJsonString(token.trim())).append("\"");
            }
            if (questionId != null && !questionId.trim().isEmpty()) {
                json.append(",\"questionId\":\"").append(escapeJsonString(questionId.trim())).append("\"");
            }
            json.append("}");
            String b64 = android.util.Base64.encodeToString(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            return "file:///android_asset/practice_frontend.html#data=" + b64;
        } catch (Exception e) {
            return "file:///android_asset/practice_frontend.html";
        }
    }

    private String refreshPracticeReturnUrlToken(String url, String token) {
        if (url == null || !url.startsWith("file:///android_asset/practice_frontend.html") || token == null || token.trim().isEmpty()) {
            return url;
        }
        int idx = url.indexOf("#data=");
        if (idx < 0) return url;
        try {
            String b64 = url.substring(idx + 6);
            String json = new String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP), java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject obj = new org.json.JSONObject(json);
            obj.put("access_token", token.trim());
            String nextB64 = android.util.Base64.encodeToString(obj.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            return url.substring(0, idx) + "#data=" + nextB64;
        } catch (Exception e) {
            return url;
        }
    }

    // ==================== 交互按钮状态操作 ====================
    private void notifyActionComplete() {
        // The action is already applied optimistically. Do not trigger a full app refresh here,
        // because the aggregate endpoints can briefly lag behind the acknowledged write.
        if (webView != null) {
            webView.evaluateJavascript("javascript:(function(){try{if(window.onPracticeActionComplete)window.onPracticeActionComplete();if(window.updateFavoritesData)window.updateFavoritesData();}catch(e){}})()", null);
        }
    }

    private void notifyActionCompleteWithData(String responseBody) {
        if (webView != null) {
            if (responseBody != null && !responseBody.isEmpty()) {
                String b64 = android.util.Base64.encodeToString(responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                webView.evaluateJavascript("javascript:(function(){try{if(window.onPracticeActionCompleteWithData)window.onPracticeActionCompleteWithData('" + b64 + "');if(window.updateFavoritesData)window.updateFavoritesData();}catch(e){}})()", null);
            } else {
                notifyActionComplete();
            }
        }
    }

    private void performPracticeAction(String action, String questionId) {
        AppLogger.log("ACTION", "performPracticeAction: action=" + action + ", questionId=" + questionId);
        if (action == null || action.trim().isEmpty()) {
            notifyActionComplete();
            return;
        }
        String safe = action.trim();
        lastPracticeActionTime = System.currentTimeMillis();
        // Keep aggregate data cached during the grace period. The write response is authoritative
        // for the question action, while aggregate endpoints may still return an older snapshot.

        // 乐观本地优先：立刻将交互（收藏、练习记录、笔记）写入 Room 数据库与 FavoritesManager
        new Thread(() -> {
            try {
                String[] parts = safe.split("\\|");
                if (parts.length >= 2) {
                    String act = parts[0];
                    String qId = parts[1];
                    String noteVal = parts.length >= 3 ? java.net.URLDecoder.decode(parts[2], "UTF-8") : null;

                    if (favoritesManager != null) {
                        favoritesManager.updateQuestionStateLocally(qId, act, noteVal);
                    }

                    AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                    QuestionCacheEntity cache = db.questionCacheDao().getById(qId);
                    if (cache == null) {
                        cache = new QuestionCacheEntity();
                        cache.questionId = qId;
                    }
                    cache.lastModifyTime = System.currentTimeMillis();
                    // Keep the operation queued until the server acknowledges it. This also
                    // covers online failures and lets the lifecycle sync retry later.
                    cache.needSync = true;

                    if ("fav_true".equals(act)) {
                        cache.isFavorite = true;
                        cache.favoritePending = true;
                    } else if ("fav_false".equals(act)) {
                        cache.isFavorite = false;
                        cache.favoritePending = true;
                    } else if ("mastered".equals(act) || "needs_practice".equals(act) || "not_known".equals(act) || "not_started".equals(act) || "familiar".equals(act) || "unknown".equals(act)) {
                        String finalAct = act;
                        if ("familiar".equals(act)) finalAct = "needs_practice";
                        else if ("unknown".equals(act)) finalAct = "not_known";
                        cache.mastery = finalAct;
                        cache.masteryPending = true;
                    } else if ("note".equals(act) && noteVal != null) {
                        cache.note = noteVal;
                        cache.notePending = true;
                    }
                    db.questionCacheDao().insert(cache);
                    AppLogger.log("ROOM", "Optimistic Room update success for qId=" + qId + " act=" + act);
                }
            } catch (Exception e) {
                AppLogger.log("ROOM_ERR", "Optimistic Room update failed: " + e.getMessage());
            }
            mainHandler.post(() -> submitPracticeAction(safe));
        }).start();
    }

    private void submitPracticeAction(String safe) {
        if (!WebViewConfig.isNetworkAvailable()) {
            AppLogger.log("ACTION", "Network unavailable, completed action locally");
            notifyActionComplete();
            return;
        }

        String token = cachedAuthToken;
        String csrf = getBestCsrfToken();
        if ((token == null || token.isEmpty()) && !cachedAuthToken.isEmpty()) {
            token = cachedAuthToken;
        }
        if (token == null || token.isEmpty()) {
            AppLogger.log("ACTION_ERR", "Token is empty, triggering refreshPageAndRetry");
            refreshPageAndRetry(safe);
            return;
        }

        // 使用直连 HTTP（更快，不依赖 WebView 状态）
        executePracticeHttp(safe, token, csrf, false);
    }

    private String getBestCsrfToken() {
        if (cachedCsrfToken != null && !cachedCsrfToken.trim().isEmpty()) {
            return cachedCsrfToken.trim();
        }
        try {
            android.content.SharedPreferences sp = getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE);
            String csSp = sp.getString(KEY_CSRF, "");
            if (csSp != null && !csSp.trim().isEmpty()) {
                cachedCsrfToken = csSp.trim();
                return cachedCsrfToken;
            }
        } catch (Exception ignored) {}
        try {
            String cookieStr = android.webkit.CookieManager.getInstance().getCookie("https://cxyonly.fans");
            if (cookieStr != null && !cookieStr.isEmpty()) {
                for (String pair : cookieStr.split(";")) {
                    String t = pair.trim();
                    if (t.startsWith("csrf_token=")) {
                        String cs = t.substring(11).trim();
                        if (!cs.isEmpty()) {
                            cachedCsrfToken = cs;
                            saveAuthCache();
                            return cs;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void markActionPending(String questionId, String action) {
        try {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            QuestionCacheEntity cache = db.questionCacheDao().getById(questionId);
            if (cache == null) {
                cache = new QuestionCacheEntity();
                cache.questionId = questionId;
            }
            if ("note".equals(action)) {
                cache.notePending = true;
            } else if ("fav_true".equals(action) || "fav_false".equals(action)) {
                cache.favoritePending = true;
            } else {
                cache.masteryPending = true;
            }
            cache.needSync = true;
            db.questionCacheDao().insert(cache);
        } catch (Exception e) {
            AppLogger.log("ROOM_ERR", "Failed to queue action " + action + " for " + questionId + ": " + e.getMessage());
        }
    }

    // 执行直连 HTTP 交互请求
    private void executePracticeHttp(String safeAction, String token, String csrf, boolean onFailureRefresh) {
        AppLogger.log("HTTP_START", "executePracticeHttp starting: action=" + safeAction + ", onFailureRefresh=" + onFailureRefresh);
        new Thread(() -> {
            try {
                String[] parts = safeAction.split("\\|");
                if (parts.length < 2) {
                    AppLogger.log("HTTP_ERR", "Invalid action parts length < 2: " + safeAction);
                    mainHandler.post(MainActivity.this::notifyActionComplete);
                    return;
                }
                String act = parts[0];
                String id = parts[1];

                java.net.URL url;
                String jsonBody;
                String method;
                if ("note".equals(act) && parts.length >= 3) {
                    String txt = java.net.URLDecoder.decode(parts[2], "UTF-8");
                    url = new java.net.URL("https://cxyonly.fans/api/v1/user/questions/" + id + "/note");
                    org.json.JSONObject b = new org.json.JSONObject();
                    b.put("note", txt);
                    b.put("expected_version", 0);
                    jsonBody = b.toString();
                    method = "PUT";
                } else {
                    url = new java.net.URL("https://cxyonly.fans/api/user/practice_events");
                    method = "POST";
                    int qid = Integer.parseInt(id);
                    org.json.JSONObject b = new org.json.JSONObject();
                    b.put("question_id", qid);
                    if ("fav_true".equals(act)) {
                        b.put("event_type", "favorite_mark");
                    } else if ("fav_false".equals(act)) {
                        b.put("event_type", "favorite_mark");
                        b.put("action", "favorite_unmark");
                    } else if ("mastered".equals(act)) {
                        b.put("event_type", "answer_submit");
                        b.put("action", "mastered_mark");
                        b.put("mastery", "mastered");
                    } else if ("needs_practice".equals(act) || "familiar".equals(act)) {
                        b.put("event_type", "answer_submit");
                        b.put("action", "needs_practice_mark");
                        b.put("mastery", "needs_practice");
                    } else if ("not_known".equals(act) || "unknown".equals(act)) {
                        b.put("event_type", "answer_submit");
                        b.put("action", "not_known_mark");
                        b.put("mastery", "not_known");
                    } else if ("not_started".equals(act)) {
                        b.put("event_type", "answer_submit");
                        b.put("mastery", "not_started");
                    } else {
                        AppLogger.log("HTTP_ERR", "Unknown action type: " + act);
                        mainHandler.post(MainActivity.this::notifyActionComplete);
                        return;
                    }
                    jsonBody = b.toString();
                }

                String activeCsrf = (csrf != null && !csrf.trim().isEmpty()) ? csrf.trim() : getBestCsrfToken();

                AppLogger.log("HTTP_REQ", "URL: " + url + " | Method: " + method);
                AppLogger.log("HTTP_REQ_HEADERS", "Auth: Bearer " + (token != null && token.length() > 10 ? token.substring(0, 10) + "..." : token) + " | CSRF: " + activeCsrf);
                AppLogger.log("HTTP_REQ_BODY", jsonBody);

                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                if (activeCsrf != null && !activeCsrf.isEmpty()) {
                    conn.setRequestProperty("X-CSRF-Token", activeCsrf);
                    conn.setRequestProperty("X-CSRFToken", activeCsrf);
                }
                conn.setRequestProperty("Referer", "https://cxyonly.fans/");
                conn.setRequestProperty("Origin", "https://cxyonly.fans");

                StringBuilder cookieBuf = new StringBuilder();
                if (activeCsrf != null && !activeCsrf.isEmpty()) {
                    cookieBuf.append("csrf_token=").append(activeCsrf);
                }
                String existingCookie = android.webkit.CookieManager.getInstance().getCookie("https://cxyonly.fans");
                if (existingCookie != null && !existingCookie.isEmpty()) {
                    for (String part : existingCookie.split(";")) {
                        String t = part.trim();
                        if (!t.startsWith("csrf_token=")) {
                            if (cookieBuf.length() > 0) cookieBuf.append("; ");
                            cookieBuf.append(t);
                        }
                    }
                }
                if (cookieBuf.length() > 0) {
                    conn.setRequestProperty("Cookie", cookieBuf.toString());
                }

                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.getOutputStream().write(jsonBody.getBytes("UTF-8"));
                int code = conn.getResponseCode();

                AppLogger.log("HTTP_RESP_CODE", "Response Code: " + code + " for action=" + safeAction);

                try {
                    java.util.List<String> cookies = conn.getHeaderFields().get("Set-Cookie");
                    if (cookies != null) {
                        for (String c : cookies) {
                            if (c.contains("csrf_token=")) {
                                int idx = c.indexOf("csrf_token=");
                                String val = c.substring(idx + 11).split(";")[0].trim();
                                if (!val.isEmpty()) {
                                    cachedCsrfToken = val;
                                    saveAuthCache();
                                    AppLogger.log("CSRF_HEADER", "Received Set-Cookie csrf_token=" + val);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}

                String responseBody = "";
                try {
                    java.io.InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                    if (is != null) {
                        java.util.Scanner s = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
                        responseBody = s.hasNext() ? s.next() : "";
                        is.close();
                    }
                } catch (Exception ignored) {}
                conn.disconnect();

                AppLogger.log("HTTP_RESP_BODY", responseBody);

                // 尝试从 Response Body 中提取新的 csrf_token
                String extractedCsrf = null;
                if (responseBody != null && !responseBody.trim().isEmpty()) {
                    try {
                        org.json.JSONObject respObj = new org.json.JSONObject(responseBody);
                        if (respObj.has("csrf_token")) {
                            extractedCsrf = respObj.optString("csrf_token", "");
                        } else if (respObj.has("data")) {
                            org.json.JSONObject dObj = respObj.optJSONObject("data");
                            if (dObj != null && dObj.has("csrf_token")) {
                                extractedCsrf = dObj.optString("csrf_token", "");
                            }
                        }
                    } catch (Exception ignored) {}
                }
                if (extractedCsrf != null && !extractedCsrf.trim().isEmpty()) {
                    cachedCsrfToken = extractedCsrf.trim();
                    saveAuthCache();
                    syncTokenToWebViews(cachedAuthToken, cachedCsrfToken);
                    AppLogger.log("CSRF_UPDATE", "Extracted new CSRF token from body: " + extractedCsrf);
                }

                if (code >= 200 && code < 300) {
                    AppLogger.log("HTTP_SUCCESS", "Action HTTP request succeeded with status " + code);
                    try {
                        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                        QuestionCacheEntity cache = db.questionCacheDao().getById(id);
                        if (cache != null) {
                            if ("note".equals(act)) {
                                cache.notePending = false;
                            } else if ("fav_true".equals(act) || "fav_false".equals(act)) {
                                cache.favoritePending = false;
                            } else {
                                cache.masteryPending = false;
                            }
                            cache.needSync = cache.favoritePending || cache.masteryPending || cache.notePending;
                            db.questionCacheDao().insert(cache);
                            if (!cache.needSync && favoritesManager != null) {
                                favoritesManager.markQuestionSynced(id);
                            }
                        }
                    } catch (Exception ignored) {}
                    final String resp = responseBody;
                    mainHandler.post(() -> notifyActionCompleteWithData(resp));
                    return;
                }

                if (code == 401) {
                    AppLogger.log("HTTP_401", "Unauthorized 401, clearing auth cache and showing session expired");
                    cachedAuthToken = "";
                    cachedCsrfToken = "";
                    clearAuthCache();
                    mainHandler.post(MainActivity.this::showSessionExpiredDialog);
                    return;
                }

                // 如果 HTTP 发生错误 (如 403 CSRF 失效) 且尚未重试过，调用 GET /api/auth/csrf 刷新并自动重试
                markActionPending(id, act);
                if (!onFailureRefresh) {
                    AppLogger.log("HTTP_CSRF_RETRY", "Action HTTP failed with code " + code + ", refreshing CSRF via API and retrying...");
                    refreshCsrfViaApi(() -> executePracticeHttp(safeAction, cachedAuthToken, cachedCsrfToken, true));
                    return;
                }
            } catch (Exception e) {
                AppLogger.log("HTTP_EX", "Exception during executePracticeHttp: " + e.toString());
                if (!onFailureRefresh) {
                    AppLogger.log("HTTP_EX_RETRY", "Exception during executePracticeHttp, refreshing CSRF via API and retrying...");
                    refreshCsrfViaApi(() -> executePracticeHttp(safeAction, cachedAuthToken, cachedCsrfToken, true));
                    return;
                }
            }
            AppLogger.log("HTTP_FAIL", "Action HTTP failed after CSRF retry for: " + safeAction);
            mainHandler.post(MainActivity.this::notifyActionComplete);
        }).start();
    }

    // 刷新 background WebView 页面获取新鲜 csrf，然后重试直连 HTTP
    private void refreshPageAndRetry(String safeAction) {
        AppLogger.log("CSRF_REFRESH", "refreshPageAndRetry starting for safeAction=" + safeAction);
        WebView av = getAuthWebView();
        if (av == null || !WebViewConfig.isNetworkAvailable()) { notifyActionComplete(); return; }
        final String oldCsrf = cachedCsrfToken;
        av.evaluateJavascript("javascript:localStorage.removeItem('csrf_token');", null);
        av.loadUrl(HOME_PAGE_URL);
        pollForFreshCsrf(safeAction, oldCsrf, 0);
    }

    private void pollForFreshCsrf(String safeAction, String oldCsrf, int attempt) {
        if (!WebViewConfig.isNetworkAvailable()) { notifyActionComplete(); return; }
        if (attempt > 12) {
            AppLogger.log("CSRF_POLL_TIMEOUT", "Poll attempt > 12. Falling back to executeInAuthWebView.");
            executeInAuthWebView(safeAction);
            return;
        }
        WebView av = getAuthWebView();
        if (av == null) { notifyActionComplete(); return; }
        String js = "javascript:(function(){"
            + "var csrf=localStorage.getItem('csrf_token')||'';"
            + "if(!csrf){var c=document.cookie.split(';');for(var i=0;i<c.length;i++){var t=c[i].trim();if(t.indexOf('csrf_token=')===0){csrf=t.substring(11);break;}}}"
            + "return csrf;})()";
        av.evaluateJavascript(js, r -> {
            String cs = (r != null && !"null".equals(r)) ? r.replaceAll("^\"|\"$", "") : "";
            boolean isFresh = !cs.isEmpty() && (oldCsrf.isEmpty() || !cs.equals(oldCsrf));
            AppLogger.log("CSRF_POLL", "Attempt #" + attempt + " polled CSRF=" + cs + " isFresh=" + isFresh);
            if (isFresh) {
                cachedCsrfToken = cs;
                saveAuthCache();
                AppLogger.log("CSRF_POLL_SUCCESS", "Got fresh CSRF token! Retrying executePracticeHttp.");
                executePracticeHttp(safeAction, cachedAuthToken, cs, true);
            } else {
                mainHandler.postDelayed(() -> pollForFreshCsrf(safeAction, oldCsrf, attempt + 1), 500);
            }
        });
    }

    private void refreshCsrfViaApi(Runnable onComplete) {
        if (cachedAuthToken == null || cachedAuthToken.trim().isEmpty() || !WebViewConfig.isNetworkAvailable()) {
            if (onComplete != null) mainHandler.post(onComplete);
            return;
        }
        new Thread(() -> {
            try {
                URL url = new URL("https://cxyonly.fans/api/auth/csrf");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + cachedAuthToken);
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                int code = conn.getResponseCode();
                AppLogger.log("CSRF_API_REFRESH", "GET /api/auth/csrf HTTP status=" + code);
                if (code >= 200 && code < 300) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    reader.close();
                    org.json.JSONObject root = new org.json.JSONObject(response.toString());
                    String newCsrf = "";
                    if (root.has("data")) {
                        org.json.JSONObject dObj = root.optJSONObject("data");
                        if (dObj != null) newCsrf = dObj.optString("csrf_token", "");
                    }
                    if (newCsrf.isEmpty()) newCsrf = root.optString("csrf_token", "");
                    if (!newCsrf.isEmpty()) {
                        cachedCsrfToken = newCsrf.trim();
                        saveAuthCache();
                        syncTokenToWebViews(cachedAuthToken, cachedCsrfToken);
                        AppLogger.log("CSRF_API_SUCCESS", "Got new CSRF token via API: " + cachedCsrfToken);
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                AppLogger.log("CSRF_API_ERR", e.toString());
            }
            if (onComplete != null) mainHandler.post(onComplete);
        }).start();
    }

    private void fetchAndCacheCsrf() {
        refreshCsrfViaApi(null);
    }

    // 在后台 WebView 中执行 action JS（回退方案）
    private void executeInAuthWebView(String safeAction) {
        AppLogger.log("AUTH_WEBVIEW", "executeInAuthWebView starting for safeAction=" + safeAction);
        WebView authView = getAuthWebView();
        if (authView == null) {
            AppLogger.log("AUTH_WEBVIEW_ERR", "AuthWebView is null");
            notifyActionComplete();
            return;
        }
        String safe = escapeJsString(safeAction);
        String safeTok = escapeJsString(cachedAuthToken != null ? cachedAuthToken : "");
        String safeCsrf = escapeJsString(getBestCsrfToken());

        String js = "javascript:(function(){"
            + "try{"
            + "if('" + safeTok + "')localStorage.setItem('daguan_token','" + safeTok + "');"
            + "if('" + safeCsrf + "' && !localStorage.getItem('csrf_token'))localStorage.setItem('csrf_token','" + safeCsrf + "');"
            + "}catch(e){}"
            + "var token=localStorage.getItem('daguan_token')||'" + safeTok + "'||'';"
            + "var csrf=localStorage.getItem('csrf_token')||'" + safeCsrf + "'||'';"
            + "if(!csrf){var c=document.cookie.split(';');for(var i=0;i<c.length;i++){var t=c[i].trim();if(t.indexOf('csrf_token=')===0){csrf=t.substring(11);break;}}}"
            + "var h=token?{'Authorization':'Bearer '+token}:{};"
            + "if(csrf)h['X-CSRF-Token']=csrf;"
            + "var act='',id='';var p='" + safe + "'.split('|');"
            + "act=p[0];id=p[1];"
            + "var x=new XMLHttpRequest();"
            + "if(act==='note'&&p.length>=3){"
            +   "var txt=decodeURIComponent(p.slice(2).join('|'));"
            +   "x.open('PUT','/api/v1/user/questions/'+id+'/note',false);"
            +   "x.setRequestHeader('Content-Type','application/json');"
            +   "Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});"
            +   "x.send(JSON.stringify({note:txt,expected_version:0}));"
            + "} else {"
            +   "var body={question_id:parseInt(id,10)};"
            +   "if(act==='fav_true'){body.event_type='favorite_mark';}"
            +   "else if(act==='fav_false'){body.event_type='favorite_mark';body.action='favorite_unmark';}"
            +   "else if(act==='mastered'){body.event_type='answer_submit';body.action='mastered_mark';body.mastery='mastered';}"
            +   "else if(act==='needs_practice'||act==='familiar'){body.event_type='answer_submit';body.action='needs_practice_mark';body.mastery='needs_practice';}"
            +   "else if(act==='not_known'||act==='unknown'){body.event_type='answer_submit';body.action='not_known_mark';body.mastery='not_known';}"
            +   "else if(act==='not_started'){body.event_type='answer_submit';body.mastery='not_started';}"
            +   "else{return '';}"
            +   "x.open('POST','/api/user/practice_events',false);"
            +   "x.setRequestHeader('Content-Type','application/json');"
            +   "Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});"
            +   "x.send(JSON.stringify(body));"
            + "}"
            + "var rt=x.responseText;"
            + "try{var o=JSON.parse(rt);if(o.csrf_token){localStorage.setItem('csrf_token',o.csrf_token);}else if(o.data&&o.data.csrf_token){localStorage.setItem('csrf_token',o.data.csrf_token);}}catch(e){}"
            + "return rt;"
            + "})()";
        String url = authView.getUrl();
        if (url != null && url.startsWith("https://cxyonly.fans")) {
            authView.evaluateJavascript(js, r -> {
                String resp = (r != null && !"null".equals(r)) ? r.replaceAll("^\"|\"$", "") : "";
                AppLogger.log("AUTH_WEBVIEW_RESP", "AuthWebView XHR result: " + resp);
                notifyActionCompleteWithData(resp);
            });
        } else {
            authView.loadUrl("https://cxyonly.fans/m/home");
            mainHandler.postDelayed(() -> authView.evaluateJavascript(js, r -> {
                String resp = (r != null && !"null".equals(r)) ? r.replaceAll("^\"|\"$", "") : "";
                AppLogger.log("AUTH_WEBVIEW_RESP", "AuthWebView delayed XHR result: " + resp);
                notifyActionCompleteWithData(resp);
            }), 1200);
        }
    }

    private String enrichPracticeJsonWithLocalState(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return rawJson;
        try {
            org.json.JSONObject root = new org.json.JSONObject(rawJson);
            org.json.JSONArray items = null;
            if (root.has("items")) {
                items = root.getJSONArray("items");
            } else if (root.has("data")) {
                Object dataObj = root.get("data");
                if (dataObj instanceof org.json.JSONObject && ((org.json.JSONObject) dataObj).has("items")) {
                    items = ((org.json.JSONObject) dataObj).getJSONArray("items");
                } else if (dataObj instanceof org.json.JSONArray) {
                    items = (org.json.JSONArray) dataObj;
                }
            } else if (root.has("questions")) {
                items = root.getJSONArray("questions");
            }
            if (items == null || items.length() == 0) return rawJson;

            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject qObj = items.optJSONObject(i);
                if (qObj == null) continue;
                String qId = qObj.optString("id", qObj.optString("question_id", ""));
                if (qId.isEmpty()) continue;

                QuestionCacheEntity cache = db.questionCacheDao().getById(qId);
                if (cache != null) {
                    org.json.JSONObject userState = qObj.optJSONObject("user_state");
                    if (userState == null) {
                        userState = new org.json.JSONObject();
                        qObj.put("user_state", userState);
                    }
                    if (cache.mastery != null && !cache.mastery.isEmpty()) {
                        userState.put("mastery", cache.mastery);
                    }
                    if (cache.isFavorite) {
                        if (!userState.has("favorited_at") || userState.isNull("favorited_at") || userState.optString("favorited_at").isEmpty()) {
                            userState.put("favorited_at", "2026-01-01T00:00:00Z");
                        }
                    } else if (cache.lastModifyTime > 0 && !cache.isFavorite) {
                        userState.remove("favorited_at");
                    }
                    if (cache.note != null) {
                        userState.put("note", cache.note);
                    }
                }
            }
            return root.toString();
        } catch (Exception e) {
            return rawJson;
        }
    }

    private void saveQuestionsToRoomCache(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return;
        try {
            org.json.JSONObject root = new org.json.JSONObject(rawJson);
            org.json.JSONArray items = null;
            if (root.has("items")) {
                items = root.getJSONArray("items");
            } else if (root.has("data")) {
                Object dataObj = root.get("data");
                if (dataObj instanceof org.json.JSONObject && ((org.json.JSONObject) dataObj).has("items")) {
                    items = ((org.json.JSONObject) dataObj).getJSONArray("items");
                } else if (dataObj instanceof org.json.JSONArray) {
                    items = (org.json.JSONArray) dataObj;
                }
            } else if (root.has("questions")) {
                items = root.getJSONArray("questions");
            }
            if (items == null || items.length() == 0) return;

            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            java.util.List<QuestionCacheEntity> caches = new java.util.ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject qObj = items.optJSONObject(i);
                if (qObj == null) continue;
                String qId = qObj.optString("id", qObj.optString("question_id", ""));
                if (qId.isEmpty()) continue;

                QuestionCacheEntity cache = db.questionCacheDao().getById(qId);
                if (cache == null) {
                    cache = new QuestionCacheEntity();
                    cache.questionId = qId;
                }
                cache.jsonContent = qObj.toString();
                org.json.JSONObject userState = qObj.optJSONObject("user_state");
                if (userState != null) {
                    if (!cache.masteryPending && userState.has("mastery") && !userState.isNull("mastery")) {
                        cache.mastery = userState.optString("mastery");
                    }
                    if (!cache.favoritePending && userState.has("favorited_at")) {
                        cache.isFavorite = !userState.isNull("favorited_at") && !userState.optString("favorited_at").isEmpty();
                    }
                    if (!cache.notePending && userState.has("note") && !userState.isNull("note")) {
                        cache.note = userState.optString("note");
                    }
                }
                cache.lastModifyTime = System.currentTimeMillis();
                caches.add(cache);
            }
            if (!caches.isEmpty()) db.questionCacheDao().insertAll(caches);
        } catch (Exception ignored) {}
    }

    // ==================== 题库后台静默预缓存机制 ====================
    private final java.util.concurrent.ExecutorService preCacheExecutor = 
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CategoryPreCacheThread");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

    private final java.util.Set<String> activePreCacheTasks = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private void triggerCategoryPreCache() {
        if (preCacheScheduled) return;
        preCacheScheduled = true;
        new Thread(() -> {
            String jsonToParse = cachedAppFrontendDataJson;
            if (jsonToParse == null || jsonToParse.isEmpty()) {
                try {
                    AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                    AppCacheEntity entity = db.appCacheDao().getByKey("app_frontend_data");
                    if (entity != null) jsonToParse = entity.jsonContent;
                } catch (Exception ignored) {}
            }
            if (jsonToParse != null && !jsonToParse.isEmpty()) {
                triggerCategoryPreCacheWithJson(jsonToParse);
            } else {
                preCacheScheduled = false;
            }
        }).start();
    }

    private void triggerCategoryPreCacheWithJson(String json) {
        if (json == null || json.isEmpty()) {
            preCacheScheduled = false;
            return;
        }
        new Thread(() -> {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(json);
                java.util.List<String> catIds = new java.util.ArrayList<>();
                if (obj.has("categories")) extractLeafCategoryIds(obj.get("categories"), catIds);
                if (!catIds.isEmpty()) {
                    startPreCachingCategories(catIds);
                } else {
                    preCacheScheduled = false;
                }
            } catch (Exception ignored) {
                preCacheScheduled = false;
            }
        }).start();
    }

    private void extractLeafCategoryIds(Object node, java.util.List<String> result) {
        if (node == null || result.size() >= MAX_PRECACHE_CATEGORIES) return;
        try {
            if (node instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) node;
                for (int i = 0; i < arr.length() && result.size() < MAX_PRECACHE_CATEGORIES; i++) {
                    extractLeafCategoryIds(arr.opt(i), result);
                }
                return;
            }
            if (!(node instanceof org.json.JSONObject)) return;
            org.json.JSONObject obj = (org.json.JSONObject) node;
            org.json.JSONArray children = obj.optJSONArray("children");
            if (children == null) children = obj.optJSONArray("subcategories");
            if (children == null) children = obj.optJSONArray("chapters");
            if (children != null && children.length() > 0) {
                extractLeafCategoryIds(children, result);
                return;
            }
            String id = obj.optString("id", obj.optString("category_id", ""));
            int count = obj.optInt("question_count", obj.optInt("total_question_count", 0));
            if (!id.isEmpty() && count > 0 && !result.contains(id)) result.add(id);
        } catch (Exception ignored) {}
    }

    private void startPreCachingCategories(java.util.List<String> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty() || !WebViewConfig.isNetworkAvailable()) {
            preCacheScheduled = false;
            return;
        }

        preCacheExecutor.submit(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());

            for (String catId : categoryIds) {
                if (catId == null || catId.trim().isEmpty()) continue;
                String safeId = catId.trim();

                if (activePreCacheTasks.contains(safeId)) continue;
                activePreCacheTasks.add(safeId);

                try {
                    PracticeCacheEntity existing = db.practiceCacheDao().getByCategory(safeId);
                    if (existing != null && existing.jsonContent != null && !existing.jsonContent.isEmpty()) {
                        long age = System.currentTimeMillis() - existing.lastModifyTime;
                        if (age < 2 * 3600_000L) {
                            activePreCacheTasks.remove(safeId);
                            continue;
                        }
                    }
                } catch (Exception ignored) {}

                fetchAndCacheSingleCategory(safeId, db);
                activePreCacheTasks.remove(safeId);

                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    break;
                }
            }
            preCacheScheduled = false;
        });
    }

    private void fetchAndCacheSingleCategory(String catId, AppDatabase db) {
        String token = cachedAuthToken;
        if (token == null || token.isEmpty()) return;
        try {
            int page = 1;
            int totalPages = 1;

            while (page <= totalPages && page <= 1) {
                String urlStr = "https://cxyonly.fans/api/questions?category_id=" + catId
                              + "&include_children=true&page=" + page + "&per_page=50&sort=mobile";
                java.net.URL url = new java.net.URL(urlStr);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                conn.setRequestProperty("Authorization", "Bearer " + token);
                String csrf = getBestCsrfToken();
                if (csrf != null && !csrf.isEmpty()) {
                    conn.setRequestProperty("X-CSRF-Token", csrf);
                }
                String cookie = android.webkit.CookieManager.getInstance().getCookie("https://cxyonly.fans");
                if (cookie != null && !cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    java.io.InputStream is = conn.getInputStream();
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        bos.write(buf, 0, len);
                    }
                    is.close();
                    String responseBody = bos.toString("UTF-8");
                    conn.disconnect();

                    if (responseBody != null && !responseBody.trim().isEmpty()) {
                        if (page == 1) {
                            PracticeCacheEntity cache = new PracticeCacheEntity();
                            cache.categoryId = catId;
                            cache.jsonContent = responseBody;
                            cache.lastModifyTime = System.currentTimeMillis();
                            db.practiceCacheDao().insert(cache);
                        }
                        saveQuestionsToRoomCache(responseBody);

                        try {
                            org.json.JSONObject obj = new org.json.JSONObject(responseBody);
                            if (obj.has("data") && obj.get("data") instanceof org.json.JSONObject) {
                                org.json.JSONObject dObj = obj.getJSONObject("data");
                                totalPages = dObj.optInt("total_pages", 1);
                            } else if (obj.has("total_pages")) {
                                totalPages = obj.optInt("total_pages", 1);
                            }
                        } catch (Exception ignored) {}
                    }
                } else {
                    conn.disconnect();
                    break;
                }
                page++;
                if (page <= totalPages) {
                    try { Thread.sleep(200); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    public void syncPendingActions() {
        if (!WebViewConfig.isNetworkAvailable()) return;
        new Thread(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                java.util.List<QuestionCacheEntity> pendingList = db.questionCacheDao().getPendingSync();
                if (pendingList == null || pendingList.isEmpty()) return;
                String token = cachedAuthToken;
                if (token == null || token.isEmpty()) return;
                String cookie = android.webkit.CookieManager.getInstance().getCookie("https://cxyonly.fans");

                for (QuestionCacheEntity q : pendingList) {
                    if (!q.needSync || q.questionId == null || q.questionId.isEmpty()) continue;
                    try {
                        boolean hasTypedPending = q.favoritePending || q.masteryPending || q.notePending;
                        boolean syncFavorite = q.favoritePending || !hasTypedPending;
                        boolean syncMastery = q.masteryPending || (!hasTypedPending && q.mastery != null && !q.mastery.isEmpty());
                        boolean syncNote = q.notePending || (!hasTypedPending && q.note != null);
                        boolean allSucceeded = true;

                        if (syncFavorite) {
                            org.json.JSONObject body = new org.json.JSONObject();
                            body.put("question_id", Integer.parseInt(q.questionId));
                            body.put("event_type", "favorite_mark");
                            if (!q.isFavorite) body.put("action", "favorite_unmark");
                            boolean success = sendHttpSync("POST", "https://cxyonly.fans/api/user/practice_events", body.toString(), token, cookie, false);
                            if (success) q.favoritePending = false;
                            allSucceeded &= success;
                        }
                        if (syncMastery) {
                            String act = q.mastery;
                            if (!"not_started".equals(act)) {
                                org.json.JSONObject body = new org.json.JSONObject();
                                body.put("question_id", Integer.parseInt(q.questionId));
                                body.put("event_type", "answer_submit");
                                body.put("mastery", act);
                                body.put("action", act + "_mark");
                                boolean success = sendHttpSync("POST", "https://cxyonly.fans/api/user/practice_events", body.toString(), token, cookie, false);
                                if (success) q.masteryPending = false;
                                allSucceeded &= success;
                            } else {
                                org.json.JSONObject body = new org.json.JSONObject();
                                body.put("question_id", Integer.parseInt(q.questionId));
                                body.put("event_type", "answer_submit");
                                body.put("mastery", "not_started");
                                boolean success = sendHttpSync("POST", "https://cxyonly.fans/api/user/practice_events", body.toString(), token, cookie, false);
                                if (success) q.masteryPending = false;
                                allSucceeded &= success;
                            }
                        }
                        if (syncNote) {
                            org.json.JSONObject b = new org.json.JSONObject();
                            b.put("note", q.note);
                            b.put("expected_version", 0);
                            boolean success = sendHttpSync("PUT", "https://cxyonly.fans/api/v1/user/questions/" + q.questionId + "/note", b.toString(), token, cookie, false);
                            if (success) q.notePending = false;
                            allSucceeded &= success;
                        }
                        q.needSync = q.favoritePending || q.masteryPending || q.notePending;
                        if (!hasTypedPending && allSucceeded) q.needSync = false;
                        db.questionCacheDao().insert(q);
                        if (!q.needSync && favoritesManager != null) {
                            favoritesManager.markQuestionSynced(q.questionId);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private boolean sendHttpSync(String method, String urlStr, String jsonBody, String token, String cookie, boolean csrfRetried) {
        try {
            java.net.URL url = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            String csrf = getBestCsrfToken();
            if (csrf != null && !csrf.isEmpty()) {
                conn.setRequestProperty("X-CSRF-Token", csrf);
            }
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.getOutputStream().write(jsonBody.getBytes("UTF-8"));
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 403 && !csrfRetried && refreshCsrfViaApiBlocking(token)) {
                return sendHttpSync(method, urlStr, jsonBody, token, cookie, true);
            }
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean refreshCsrfViaApiBlocking(String token) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL("https://cxyonly.fans/api/auth/csrf").openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                conn.disconnect();
                return false;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close();
            conn.disconnect();
            org.json.JSONObject root = new org.json.JSONObject(response.toString());
            org.json.JSONObject data = root.optJSONObject("data");
            String csrf = data != null ? data.optString("csrf_token", "") : root.optString("csrf_token", "");
            if (csrf.isEmpty()) return false;
            cachedCsrfToken = csrf;
            saveAuthCache();
            syncTokenToWebViews(cachedAuthToken, cachedCsrfToken);
            return true;
        } catch (Exception e) {
            AppLogger.log("CSRF_SYNC_ERR", e.toString());
            return false;
        }
    }

    private String getTokenFromAuthWebView() {
        WebView authView = getAuthWebView();
        if (authView == null) return "";
        final String[] result = {""};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mainHandler.post(() -> {
            authView.evaluateJavascript("javascript:(function(){return localStorage.getItem('daguan_token')||'';})()", r -> {
                if (r != null && !"null".equals(r)) {
                    result[0] = r.replaceAll("^\"|\"$", "");
                }
                latch.countDown();
            });
        });
        try { latch.await(3000, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return result[0];
    }

    private String getCsrfFromAuthWebView() {
  // ⚠️ 同步版本已弃用（会死锁），使用 asyncCsrfFetch 替代
  return cachedCsrfToken != null ? cachedCsrfToken : "";
}
// 异步从 WebView 获取 csrf_token（localStorage + cookie 双重检查）
private void asyncCsrfFetch() {
  WebView authView = getAuthWebView();
  if (authView == null) return;
  mainHandler.post(() -> {
    String js = "javascript:(function(){"
      + "var csrf=localStorage.getItem('csrf_token')||'';"
      + "if(!csrf){var c=document.cookie.split(';');for(var i=0;i<c.length;i++){var t=c[i].trim();if(t.indexOf('csrf_token=')===0){csrf=t.substring(11);break;}}}"
      + "return csrf;})()";
    authView.evaluateJavascript(js, r -> {
      if (r != null && !"null".equals(r)) {
        String cs = r.replaceAll("^\"|\"$", "");
        if (!cs.isEmpty()) cachedCsrfToken = cs;
      }
    });
  });
}

    private void fetchHistoryData() {
        refreshCsrfViaApi(null);
        if (webView == null || getAuthWebView() == null || !WebViewConfig.isNetworkAvailable()) {
            sendHistoryData("{\"success\":false,\"error\":\"offline\"}");
            return;
        }
        warmAuthWebView(() -> {
            String js = "javascript:(function(){"
                + "var token=localStorage.getItem('daguan_token')||'';"
                + "var r={success:true,access_token:token,events:[],last_study:{},daily_stats:{}};"
                + "var h=token?{'Authorization':'Bearer '+token}:{};"
                + "try{"
                + "var x=new XMLHttpRequest();"
                + "x.open('GET','/api/user/practice_events/recent?per_page=50',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();r.events=JSON.parse(x.responseText);"
                + "x.open('GET','/api/questions/user/last_study',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();r.last_study=JSON.parse(x.responseText);"
                + "x.open('GET','/api/user/daily_stats',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();r.daily_stats=JSON.parse(x.responseText);"
                + "}catch(e){r.success=false;r.error=e.message;}"
                + "return JSON.stringify(r);"
                + "})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                try {
                    Object parsed = new org.json.JSONTokener(result).nextValue();
                    sendHistoryData(parsed instanceof String ? (String) parsed : String.valueOf(parsed));
                } catch (Exception e) {
                    sendHistoryData("{\"success\":false,\"error\":\"parse\"}");
                }
            });
        });
    }

    private void sendHistoryData(String json) {
        if (webView == null) return;
        String b64 = android.util.Base64.encodeToString((json == null ? "{}" : json).getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        webView.evaluateJavascript("javascript:(function(){if(window.onHistoryData){window.onHistoryData('" + b64 + "');}})()", null);
    }

    private static String escapeJsString(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String escapeJsonString(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ==================== 登录检测 ====================
    private void startSilentLoginCheck() {
        if (loginCheckInProgress) return;
        if (!cachedAuthToken.isEmpty()) {
            isLoggedIn = true;
            syncTokenToWebViews(cachedAuthToken, cachedCsrfToken);
            loadAppFrontend();
            startLoginWatchdog();
            startFavoritesPeriodicSync();
            return;
        }
        if (!WebViewConfig.isNetworkAvailable()) return;
        loginCheckInProgress = true;
        warmAuthWebView(() -> {
            String js = "javascript:(function(){var token=localStorage.getItem('daguan_token');return token?'logged_in':'not_logged_in';})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                loginCheckInProgress = false;
                String r = result != null ? result.replaceAll("\"", "") : "";
                if ("logged_in".equals(r)) {
                    isLoggedIn = true; loginGuideShown = false; showLoadingOverlay(false); isAutoRedirect = true; loadingStartTime = System.currentTimeMillis();
                    mainHandler.postDelayed(MainActivity.this::loadAppFrontend, 1500);
                    mainHandler.postDelayed(() -> { startLoginWatchdog(); startFavoritesPeriodicSync(); }, 2000);
                } else if (!cachedAuthToken.isEmpty()) {
                    isLoggedIn = true;
                    syncTokenToWebViews(cachedAuthToken, cachedCsrfToken);
                    loadAppFrontend();
                } else {
                    goToLoginFlow();
                }
            });
        });
    }

    private void goToLoginFlow() {
        isLoggedIn = false;
        loginGuideShown = false;
        showLoadingOverlay(false);
        isAutoRedirect = false;
        mainHandler.postDelayed(this::loadLoginFrontend, 300);
    }

    private void logout() {
isLoggedIn = false;
loginGuideShown = false;
loginCheckInProgress = false;
cachedAuthToken = "";
cachedCsrfToken = "";
clearAuthCache();
stopLoginWatchdog();
        stopFavoritesPeriodicSync();
        WebView authView = getAuthWebView();
        if (authView != null) {
            authView.evaluateJavascript("javascript:(function(){localStorage.removeItem('daguan_token');localStorage.removeItem('daguan_user');localStorage.removeItem('csrf_token');return 'ok';})()", null);
        }
        clearAuthCookies();
        loadLoginFrontend();
    }

    private void clearAuthCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setCookie("https://cxyonly.fans", "daguan_token=; Max-Age=0; Path=/");
            cm.setCookie("https://cxyonly.fans", "csrf_token=; Max-Age=0; Path=/");
            cm.setCookie("https://cxyonly.fans", "session=; Max-Age=0; Path=/");
            cm.setCookie("https://cxyonly.fans", "access_token=; Max-Age=0; Path=/");
            cm.setCookie("https://cxyonly.fans", "refresh_token=; Max-Age=0; Path=/");
            cm.setCookie("https://cxyonly.fans", "daguan_token=; Max-Age=0; Path=/; Domain=.cxyonly.fans");
            cm.setCookie("https://cxyonly.fans", "csrf_token=; Max-Age=0; Path=/; Domain=.cxyonly.fans");
            cm.setCookie("https://cxyonly.fans", "session=; Max-Age=0; Path=/; Domain=.cxyonly.fans");
            cm.setCookie("https://cxyonly.fans", "access_token=; Max-Age=0; Path=/; Domain=.cxyonly.fans");
            cm.setCookie("https://cxyonly.fans", "refresh_token=; Max-Age=0; Path=/; Domain=.cxyonly.fans");
            cm.removeSessionCookies(null);
            cm.flush();
        } catch (Exception ignored) { }
    }

    // ==================== 网络恢复重试 ====================
    private void startNetworkRetry() {
        stopNetworkRetry();
        networkRetryRunnable = () -> {
            if (WebViewConfig.isNetworkAvailable()) {
                warmAuthWebView(() -> mainHandler.postDelayed(this::checkLoginAndRoute, 800));
                return;
            }
            mainHandler.postDelayed(networkRetryRunnable, 1500);
        };
        mainHandler.postDelayed(networkRetryRunnable, 1500);
    }
    private void stopNetworkRetry() { if (networkRetryRunnable != null) { mainHandler.removeCallbacks(networkRetryRunnable); networkRetryRunnable = null; } }

    // ==================== Watchdog ====================
    private void startLoginWatchdog() {
        stopLoginWatchdog();
        loginWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (getAuthWebView() == null) return;
                if (!WebViewConfig.isNetworkAvailable()) { mainHandler.postDelayed(this, 10000); return; }
                if (!cachedAuthToken.isEmpty()) {
                    syncTokenToWebViews(cachedAuthToken, cachedCsrfToken);
                }
                mainHandler.postDelayed(this, 10000);
            }
        };
        mainHandler.postDelayed(loginWatchdogRunnable, 5000);
    }
    private void stopLoginWatchdog() { if (loginWatchdogRunnable != null) { mainHandler.removeCallbacks(loginWatchdogRunnable); loginWatchdogRunnable = null; } }

    private void handleTokenLost() {
        showSessionExpiredDialog();
    }

    // 弹窗提示登录状态已失效，让用户选择是否重新登录
    private void showSessionExpiredDialog() {
        if (!isLoggedIn) return;
        // 断网时不弹窗（可能只是网络错误，不是 session 过期）
        if (!WebViewConfig.isNetworkAvailable()) {
            return;
        }
        isLoggedIn = false;
        loginGuideShown = false;
        stopLoginWatchdog();
        stopFavoritesPeriodicSync();
        if (webView != null) {
            String cur = webView.getUrl();
            if (cur != null && cur.startsWith("file:///android_asset/practice_frontend.html")) {
                pendingReturnUrl = cur;
            }
        }
        cachedAuthToken = "";
        cachedCsrfToken = "";
        clearAuthCache();
        // 清除后台 WebView 中的 token/csrf
        WebView av = getAuthWebView();
        if (av != null) {
            av.evaluateJavascript("javascript:(function(){localStorage.removeItem('daguan_token');localStorage.removeItem('csrf_token');return 'ok';})()", null);
        }
        clearAuthCookies();
        mainHandler.post(() -> {
            Dialog dialog = new Dialog(MainActivity.this);
            dialog.setContentView(R.layout.dialog_session_expired);
            dialog.setCancelable(false);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setDimAmount(0.6f);
            }
            Button btnRelogin = dialog.findViewById(R.id.btnRelogin);
            Button btnCancel = dialog.findViewById(R.id.btnCancel);
            if (btnRelogin != null) {
                btnRelogin.setOnClickListener(v -> {
                    dialog.dismiss();
                    isAutoRedirect = true;
                    loadingStartTime = System.currentTimeMillis();
                    showLoadingOverlay(true);
                    loadLoginFrontend();
                });
            }
            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> {
                    dialog.dismiss();
                    loadAppFrontend();
                });
            }
            dialog.show();
        });
    }

    // ==================== 弹窗 / 微信 ====================
    private void showLoginCodeGuide() {
        if (isLoggedIn || loginGuideShown) return;
        loginGuideShown = true;
        mainHandler.post(() -> {
            Dialog dialog = new Dialog(MainActivity.this);
            dialog.setContentView(R.layout.dialog_login_guide);
            dialog.setCancelable(false);
            if (dialog.getWindow() != null) { dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); dialog.getWindow().setDimAmount(0.6f); }
            Button btnW = dialog.findViewById(R.id.btnWechat);
            Button btnC = dialog.findViewById(R.id.btnCancel);
            if (btnW != null) btnW.setOnClickListener(v -> { dialog.dismiss(); openWeChat(); });
            if (btnC != null) btnC.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
        });
    }
    private void openWeChat() {
        if (weChatOpening) return;
        if (!isPackageInstalled(WECHAT_PACKAGE)) { toast("\u26a0\ufe0f 未检测到微信客户端"); return; }
        weChatOpening = true; mainHandler.postDelayed(() -> weChatOpening = false, 3000);
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(WECHAT_PACKAGE);
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(i); toast("请在微信中搜索「澄潇宇」公众号"); return; }
        } catch (Exception ignored) { }
        weChatOpening = false; toast("\u26a0\ufe0f 无法打开微信");
    }
    private boolean isPackageInstalled(String pkg) { try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; } }

    // ==================== 下拉 / 重试 ====================
    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(getColor(R.color.accent), getColor(R.color.accent_light));
        swipeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.dark_card));
        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> !pullRefreshAllowed || webView == null || webView.getScrollY() > 0);
        swipeRefresh.setOnRefreshListener(() -> {
            if (!pullRefreshAllowed) { swipeRefresh.setRefreshing(false); return; }
            cachedAppFrontendDataJson = null;
            fetchAppFrontendData();
            syncFavoritesInBackground(true);
            mainHandler.postDelayed(() -> { if (swipeRefresh.isRefreshing()) swipeRefresh.setRefreshing(false); }, 1500);
        });
    }

    private void updatePullRefreshForUrl(String url) {
        if (swipeRefresh == null) return;
        pullRefreshAllowed = false;
        swipeRefresh.setEnabled(false);
    }
    private void setupErrorRetry() {
        retryBtn.setOnClickListener(v -> { errorOverlay.setVisibility(View.GONE); loginGuideShown = false; loginCheckInProgress = false; startSilentStartupCheck(); });
    }

    // 优化：动画复用，避免频繁创建对象
    private android.animation.ValueAnimator progressAnimator;
    private int fullProgressWidth = -1;

    // ==================== 进度条 / 加载 / Toast ====================
    private void animateProgressBar(int target) {
        if (target < currentProgress) currentProgress = target;
        if (target >= PROGRESS_MAX) { currentProgress = PROGRESS_MAX; updateProgressWidth(PROGRESS_MAX); return; }
        
        // 优化：使用属性动画替代频繁的 Handler 消息轮询，平滑更新并避免主线程阻塞
        if (progressAnimator != null && progressAnimator.isRunning()) {
            progressAnimator.cancel();
        }
        
        progressAnimator = android.animation.ValueAnimator.ofInt(currentProgress, target);
        progressAnimator.setDuration((target - currentProgress) * 5L);
        progressAnimator.addUpdateListener(animation -> {
            int val = (int) animation.getAnimatedValue();
            currentProgress = val;
            updateProgressWidth(val);
        });
        progressAnimator.start();
    }
    private void updateProgressWidth(int p) {
        if (progressBar == null) return;
        
        // 优化：通过 scaleX 替代修改 LayoutParams 导致的全屏 relayout，极大解决过度绘制和卡顿
        if (fullProgressWidth <= 0) {
            fullProgressWidth = progressBar.getParent() instanceof View ? ((View) progressBar.getParent()).getWidth() : getResources().getDisplayMetrics().widthPixels;
            if (fullProgressWidth <= 0) fullProgressWidth = getResources().getDisplayMetrics().widthPixels;
            
            ViewGroup.LayoutParams lp = progressBar.getLayoutParams();
            if (lp != null) {
                lp.width = fullProgressWidth;
                progressBar.setLayoutParams(lp);
                progressBar.setPivotX(0f);
            }
        }
        
        float scale = (float) p / PROGRESS_MAX;
        progressBar.setScaleX(scale);
    }
    private void showLoadingOverlay(boolean show) {
        if (show) {
            if (loadingOverlay.getVisibility() != View.VISIBLE || loadingOverlay.getAlpha() < 1f) {
                loadingOverlay.setVisibility(View.VISIBLE);
                loadingOverlay.setLayerType(View.LAYER_TYPE_HARDWARE, null); // 优化：动画期间开启硬件加速
                loadingOverlay.animate().alpha(1f).setDuration(200).withEndAction(() -> loadingOverlay.setLayerType(View.LAYER_TYPE_NONE, null)).start();
            }
            View logo = findViewById(R.id.loadingLogo);
            if (logo != null && logo.getAnimation() == null) { Animation anim = AnimationUtils.loadAnimation(this, R.anim.rotate_loader); logo.startAnimation(anim); }
        } else {
            if (loadingOverlay.getVisibility() == View.VISIBLE) {
                loadingOverlay.setLayerType(View.LAYER_TYPE_HARDWARE, null); // 优化：动画期间开启硬件加速
                loadingOverlay.animate().alpha(0f).setDuration(300).withEndAction(() -> { loadingOverlay.setVisibility(View.GONE); loadingOverlay.setLayerType(View.LAYER_TYPE_NONE, null); }).start();
            }
            View logo = findViewById(R.id.loadingLogo); if (logo != null) logo.clearAnimation();
        }
    }
    private void showErrorPage(String info) { 
        if (errorMsg != null && info != null) errorMsg.setText(info); 
        errorOverlay.setVisibility(View.VISIBLE); errorOverlay.setAlpha(0f); 
        errorOverlay.setLayerType(View.LAYER_TYPE_HARDWARE, null); // 优化：动画期间开启硬件加速
        errorOverlay.animate().alpha(1f).setDuration(300).withEndAction(() -> errorOverlay.setLayerType(View.LAYER_TYPE_NONE, null)).start(); 
    }
    private void toast(String msg) { cxyonly.fans.util.DarkToast.show(this, msg); }

    // ==================== 返回拦截 ====================
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { if (keyCode == KeyEvent.KEYCODE_BACK) return handleBackPress(); return super.onKeyDown(keyCode, event); }
    private boolean handleBackPress() {
        if (errorOverlay.getVisibility() == View.VISIBLE) { errorOverlay.setVisibility(View.GONE); loginGuideShown = false; loginCheckInProgress = false; startSilentStartupCheck(); return true; }
        if (webView != null && webView.canGoBack()) { webView.goBack(); return true; }
        long now = System.currentTimeMillis();
        if (now - lastBackPressTime > 2000) { lastBackPressTime = now; toast("再按一次退出"); return true; }
        finishAffinity(); return true;
    }

    // ==================== 收藏 ====================
    private void startFavoritesSync() { if (favoritesManager != null && getAuthWebView() != null) favoritesManager.startSync(getAuthWebView()); }
    private void startFavoritesPeriodicSync() { stopFavoritesPeriodicSync(); favoritesSyncRunnable = () -> { syncFavoritesInBackground(false); mainHandler.postDelayed(favoritesSyncRunnable, FAV_SYNC_INTERVAL_MS); }; mainHandler.post(favoritesSyncRunnable); }
    private void stopFavoritesPeriodicSync() { if (favoritesSyncRunnable != null) { mainHandler.removeCallbacks(favoritesSyncRunnable); favoritesSyncRunnable = null; } }

    private void openFavoritesViewer() {
        if (webView == null || favoritesManager == null) return;
        refreshCsrfViaApi(null);
        String cur = webView.getUrl();
        if (cur != null && !cur.startsWith("file:///android_asset/favorites_viewer")) {
            pageHistory.push(cur);
        }
        loadFavoritesViewer();
        syncFavoritesInBackground(true);
    }

    private void loadFavoritesViewer() {
        if (webView == null || favoritesManager == null) return;
        webView.loadUrl("file:///android_asset/favorites_viewer.html");
    }

    private void syncFavoritesInBackground(boolean refreshIfVisible) {
        if (!isLoggedIn || !WebViewConfig.isNetworkAvailable() || favoritesManager == null || getAuthWebView() == null || favoritesManager.isSyncing()) return;
        if (System.currentTimeMillis() - lastPracticeActionTime < ACTION_SYNC_GRACE_MS) return;
        favoritesManager.setListener(new FavoritesManager.SyncListener() {
            @Override public void onProgress(String message) { }
            @Override public void onComplete(int count) {
                favoritesManager.setListener(originalFavListener);
                if (refreshIfVisible && isFavoritesPageVisible()) { webView.evaluateJavascript("javascript:(function(){if(window.updateFavoritesData){window.updateFavoritesData();}})()", null); }
                else updateTopBarVisibility();
            }
            @Override public void onError(String error) {
                favoritesManager.setListener(originalFavListener);
            }
        });
        favoritesManager.startSync(getAuthWebView());
    }

    private boolean isFavoritesPageVisible() {
        if (webView == null || webView.getUrl() == null) return false;
        String url = webView.getUrl();
        // 合并后收藏功能内联在 app_frontend.html 中，不再有独立的 favorites_viewer.html
        return url.startsWith("file:///android_asset/app_frontend.html")
            || url.startsWith("file:///android_asset/favorites_viewer.html");
    }

    // ==================== 生命周期 ====================
    @Override
    protected void onResume() {
        super.onResume();
        syncPendingActions();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
        if (backgroundWebView != null) {
            backgroundWebView.onResume();
            backgroundWebView.resumeTimers();
        }
        if (!isLoggedIn && loginHelper != null) {
            mainHandler.postDelayed(() -> loginHelper.checkAndFillLoginCode(), 500);
        }
        if (isLoggedIn) {
            startLoginWatchdog();
            startFavoritesPeriodicSync();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        if (backgroundWebView != null) {
            backgroundWebView.onPause();
            backgroundWebView.pauseTimers();
        }
        stopLoginWatchdog();
        stopFavoritesPeriodicSync();
    }

    @Override
    protected void onDestroy() {
        stopLoginWatchdog();
        stopNetworkRetry();
        stopFavoritesPeriodicSync();
        if (favoritesManager != null) favoritesManager.shutdown();
        if (webView != null) {
            webView.stopLoading();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        if (backgroundWebView != null) {
            backgroundWebView.stopLoading();
            backgroundWebView.removeAllViews();
            backgroundWebView.destroy();
            backgroundWebView = null;
        }
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (webView != null) webView.freeMemory();
        if (backgroundWebView != null) backgroundWebView.freeMemory();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (webView != null) {
            webView.requestLayout();
            if (isPageLoaded) {
                webView.evaluateJavascript("javascript:(function(){window.dispatchEvent(new Event('orientationchange'));})()", null);
            }
        }
        if (currentProgress > 0) updateProgressWidth(currentProgress);
    }
}
