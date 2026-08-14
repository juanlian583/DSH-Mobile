package com.dsh.mobile;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.net.URL;

/**
 * DSH 手机端 — 把 DeepSeek Harness 装进手机：
 * 首次运行下载并解压 Ubuntu(rootfs) 运行时 → 写入 API Key →
 * 用 proot 在手机内启动 dsh web(127.0.0.1:3080) → 全屏 WebView 嵌入界面。
 *
 * 注意：本应用 targetSdk=28 —— Android 10+ 的 SELinux W^X 限制禁止
 * targetSdk>=29 的应用执行自身数据目录里的文件，必须用旧域语义。
 */
public class MainActivity extends Activity {

    private static final int DEFAULT_PORT = 3091; // 默认端口：与本机 3080 的环境隔离，可在设置中修改
    private static final long BOOT_TIMEOUT_MS = 300_000;
    /** 桌面版 Chrome UA —— 让网页端按"电脑"标识渲染 */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String PREF = "dsh_settings";
    private static final int DESKTOP_VIEWPORT = 1280;
    private static final long LOG_CAP = 8L * 1024 * 1024;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private File filesDir, rootfsDir, binDir, runtimeTar, readyFlag, prootBin, prootLog;

    private WebView webView;
    private TextView console;
    private ProgressBar progressBar;
    private EditText urlInput, keyInput;
    private Button actionButton;

    private Process prootProcess;
    private boolean booting = false;
    private boolean killed = false;

    // ---------------- lifecycle ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        filesDir = getFilesDir();
        rootfsDir = new File(filesDir, "rootfs");
        binDir = new File(filesDir, "bin");
        runtimeTar = new File(filesDir, "runtime.tar.gz");
        readyFlag = new File(filesDir, "ready.flag");
        prootBin = new File(binDir, "proot");
        prootLog = new File(filesDir, "dsh-proot.log");

        if (isReady()) {
            showBootConsole();
            startServer();
        } else {
            showSetupUi();
        }
    }

    @Override
    protected void onDestroy() {
        killed = true;
        killServer();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    // ---------------- helpers ----------------

    private boolean isReady() {
        return readyFlag.exists() && new File(rootfsDir, "bin/bash").exists()
                && new File(rootfsDir, "root/boot.sh").exists();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /** 追加一行到控制台（主线程）。 */
    private void log(final String msg) {
        ui.post(() -> {
            if (console != null) {
                console.append(msg + "\n");
                final ScrollView sv = (ScrollView) console.getParent();
                if (sv != null) sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void setBusy(final boolean busy) {
        ui.post(() -> {
            if (actionButton != null) actionButton.setEnabled(!busy);
            if (progressBar != null) progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        });
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView mkLabel(String text, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(202, 215, 240));
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        return t;
    }

    private EditText mkEdit(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(118, 138, 175));
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(dp(16), dp(10), dp(16), dp(10));
        e.setMinHeight(dp(52));
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.setMargins(0, dp(4), 0, dp(16));
        e.setLayoutParams(elp);
        return e;
    }

    /** style: 0=主按钮(渐变) 1=幽灵按钮(描边) 2=小胶囊(悬浮) */
    private Button mkButton(String text, int style) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        if (style == 0) {
            b.setBackgroundResource(R.drawable.bg_button_primary);
            b.setMinHeight(dp(54));
            b.setPadding(dp(20), 0, dp(20), 0);
        } else if (style == 1) {
            b.setBackgroundResource(R.drawable.bg_button_ghost);
            b.setMinHeight(dp(46));
            b.setPadding(dp(24), 0, dp(24), 0);
        } else {
            b.setBackgroundResource(R.drawable.bg_pill);
            b.setMinHeight(dp(44));
            b.setMinWidth(dp(44));
            b.setPadding(dp(14), 0, dp(14), 0);
        }
        return b;
    }

    private String readLogTail() {
        StringBuilder sb = new StringBuilder();
        for (File f : new File[]{prootLog, new File(rootfsDir, "root/dsh.log")}) {
            if (f.exists() && f.length() > 0) {
                sb.append("===== ").append(f.getName()).append(" =====\n");
                long len = f.length();
                long start = Math.max(0, len - 32 * 1024);
                try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                    raf.seek(start);
                    byte[] buf = new byte[(int) (len - start)];
                    raf.readFully(buf);
                    // UTF-8 解码（readLine 会把中文变成乱码）
                    String text = new String(buf, StandardCharsets.UTF_8);
                    // 开头若被截断成半行，丢掉它
                    int nl = text.indexOf('\n');
                    if (nl >= 0 && start > 0) text = text.substring(nl + 1);
                    sb.append(text);
                    if (!text.endsWith("\n")) sb.append('\n');
                } catch (IOException ignored) {}
            }
        }
        return sb.length() == 0 ? "(暂无日志)" : sb.toString();
    }

    private void copyLogToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("dsh-log", readLogTail()));
        toast("日志已复制，可直接粘贴发送");
    }

    // ---------------- boot console UI ----------------

    private void showBootConsole() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(72), dp(28), dp(28));
        root.setBackgroundResource(R.drawable.bg_screen);

        root.addView(mkLabel("DSH 智能体", 24, true));
        root.addView(mkLabel("正在启动 dsh 服务…", 13, false));

        console = new TextView(this);
        console.setTextColor(Color.rgb(158, 224, 196));
        console.setTextSize(11);
        console.setTypeface(Typeface.MONOSPACE);
        console.setPadding(dp(16), dp(16), dp(16), dp(16));
        console.setBackgroundResource(R.drawable.bg_card);
        console.setText("准备启动…\n");
        ScrollView sv = new ScrollView(this);
        sv.addView(console);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.setMargins(0, dp(16), 0, dp(16));
        root.addView(sv, slp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button retry = mkButton("重试", 1);
        retry.setOnClickListener(v -> { if (!booting) startServer(); });
        btns.addView(retry);

        Button copyLog = mkButton("复制日志", 1);
        copyLog.setOnClickListener(v -> copyLogToClipboard());
        btns.addView(copyLog);

        Button settings = mkButton("设置", 1);
        settings.setOnClickListener(v -> showSettingsDialog());
        btns.addView(settings);

        root.addView(btns);
        setContentView(root);

        // 启动期间每秒刷新日志尾巴
        startLogTail();
    }

    private void startLogTail() {
        ui.postDelayed(new Runnable() {
            private long pos1 = -1;
            private long pos2 = -1;
            @Override
            public void run() {
                if (killed || webView != null) return;
                pos1 = appendDelta(prootLog, pos1);
                pos2 = appendDelta(new File(rootfsDir, "root/dsh.log"), pos2);
                ui.postDelayed(this, 1500);
            }

            /** 只追加自 pos 以来的新内容；返回新位置（文件被清空/缩短则重置）。 */
            private long appendDelta(File f, long pos) {
                long len = f.exists() ? f.length() : 0;
                if (len == 0) return 0;
                if (pos < 0 || len < pos) pos = Math.max(0, len - 4096); // 首次只显示最近 4KB
                if (len > pos) {
                    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                        raf.seek(pos);
                        byte[] buf = new byte[(int) (len - pos)];
                        raf.readFully(buf);
                        log(new String(buf, StandardCharsets.UTF_8));
                    } catch (IOException ignored) {}
                    return len;
                }
                return pos;
            }
        }, 1500);
    }

    // ---------------- setup UI ----------------

    private void showSetupUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(72), dp(28), dp(28));
        root.setBackgroundResource(R.drawable.bg_screen);

        root.addView(mkLabel("DSH 智能体", 26, true));
        root.addView(mkLabel("只需粘贴 DeepSeek API Key。运行时将从内置地址自动下载"
                + "（约 300~600MB，仅一次）。", 13, false));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(20), dp(16), dp(20), dp(20));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(20), 0, dp(16));
        root.addView(card, clp);

        card.addView(mkLabel("DeepSeek API Key", 13, true));
        keyInput = mkEdit("sk-...", true);
        card.addView(keyInput);

        // 高级选项：下载地址（默认已内置，正常无需修改）
        Button advToggle = mkButton("高级选项（下载地址） ▾", 1);
        advToggle.setTextSize(13);
        final LinearLayout advBox = new LinearLayout(this);
        advBox.setOrientation(LinearLayout.VERTICAL);
        advBox.setVisibility(View.GONE);
        advBox.addView(mkLabel("运行时包下载地址（已内置默认值，一般不用改）", 12, false));
        urlInput = mkEdit(getString(R.string.runtime_url_default), false);
        advBox.addView(urlInput);
        advToggle.setOnClickListener(v -> {
            boolean show = advBox.getVisibility() != View.VISIBLE;
            advBox.setVisibility(show ? View.VISIBLE : View.GONE);
            advToggle.setText("高级选项（下载地址） " + (show ? "▴" : "▾"));
        });
        card.addView(advToggle);
        card.addView(advBox);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        progressBar.setProgressTintList(ColorStateList.valueOf(Color.rgb(34, 211, 238)));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(35, 50, 82)));
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(Color.rgb(34, 211, 238)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        plp.setMargins(0, dp(4), 0, dp(8));
        root.addView(progressBar, plp);

        console = new TextView(this);
        console.setTextColor(Color.rgb(158, 224, 196));
        console.setTextSize(11);
        console.setTypeface(Typeface.MONOSPACE);
        console.setPadding(dp(16), dp(16), dp(16), dp(16));
        console.setBackgroundResource(R.drawable.bg_card);
        ScrollView sv = new ScrollView(this);
        sv.addView(console);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.setMargins(0, 0, 0, dp(16));
        root.addView(sv, slp);

        actionButton = mkButton("开始安装并启动", 0);
        actionButton.setOnClickListener(v -> startSetup());
        root.addView(actionButton);

        setContentView(root);
    }

    private void startSetup() {
        final String url = urlInput.getText().toString().trim();
        final String key = keyInput.getText().toString().trim();
        if (url.isEmpty()) { toast("请填写运行时包下载地址"); return; }
        if (key.isEmpty()) { toast("请粘贴 DeepSeek API Key"); return; }
        setBusy(true);
        log("→ 开始安装运行时…");
        new Thread(() -> {
            try {
                log("1/5 准备 proot 二进制…");
                installProotBinary();
                log("2/5 下载运行时包…");
                download(url, runtimeTar);
                log("3/5 解压 rootfs…");
                TarExtractor.extract(runtimeTar, rootfsDir, (bytes, entry) -> {
                    if ((bytes % (256L << 20)) < (1 << 16)) {
                        log("   …已解压 " + (bytes >> 20) + " MB");
                    }
                });
                runtimeTar.delete(); // 释放 267MB 临时包
                log("4/5 写入 API Key…");
                writeApiKey(key);
                log("5/5 安装完成 ✓");
                new File(filesDir, "ready.flag").createNewFile();
                ui.post(() -> { setBusy(false); startServer(); });
            } catch (final Throwable t) {
                ui.post(() -> {
                    setBusy(false);
                    log("✗ 安装失败：" + t);
                    log("  提示：若是下载失败，可展开「高级选项」检查下载地址。");
                    toast("安装失败，见日志");
                });
            }
        }).start();
    }

    // ---------------- install steps ----------------

    private void installProotBinary() throws IOException {
        binDir.mkdirs();
        AssetManager am = getAssets();
        try (InputStream in = am.open("proot");
             OutputStream out = new FileOutputStream(prootBin)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        makeExecutable(prootBin);
    }

    /**
     * Android 10+ 对 targetSdk>=29 的应用有 SELinux W^X 限制：
     * 禁止执行应用数据目录里可写的文件（error=13 EACCES）。
     * 本应用 targetSdk 28 保持旧域可执行；这里仍确保权限位正确（去写+可执行）并校验。
     */
    private void makeExecutable(File f) throws IOException {
        f.setReadable(true, false);
        f.setWritable(false, false);   // W^X: 去掉写权限
        f.setExecutable(true, false);
        if (!f.canExecute()) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"chmod", "0555", f.getAbsolutePath()});
                p.waitFor();
            } catch (Exception ignored) {}
        }
        if (!f.canExecute()) {
            throw new IOException("无法设置执行权限: " + f);
        }
    }

    private void download(String urlStr, File target) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(60_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " (下载地址无效?)");
        long total = conn.getContentLengthLong();
        File tmp = new File(target.getAbsolutePath() + ".part");
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int r;
            long last = 0;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                done += r;
                if (total > 0 && done - last > (8L << 20)) {
                    last = done;
                    final long d = done, t = total;
                    log("   下载 " + (d >> 20) + " / " + (t >> 20) + " MB");
                }
            }
        } finally {
            conn.disconnect();
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            tmp.renameTo(target);
        }
    }

    private void writeApiKey(String key) throws IOException {
        File creds = new File(rootfsDir, "root/.dsh/.credentials.yaml");
        creds.getParentFile().mkdirs();
        try (BufferedWriter w = new BufferedWriter(new FileWriter(creds))) {
            w.write("DEEPSEEK_API_KEY: " + key.trim() + "\n");
        }
    }

    // ---------------- server boot ----------------

    private void startServer() {
        if (booting || prootProcess != null) return;
        booting = true;
        setBusy(true);
        log("→ 正在启动 dsh 服务（端口 " + port() + "，首次较慢）…");
        new Thread(() -> {
            try {
                makeExecutable(prootBin);
                ProcessBuilder pb = new ProcessBuilder(
                        prootBin.getAbsolutePath(),
                        "-r", rootfsDir.getAbsolutePath(),
                        "-b", "/proc",
                        "-b", "/sys",
                        "-b", "/dev",
                        "-b", "/dev/pts",
                        "-b", "/proc/self/fd:/dev/fd",
                        "-0",
                        "-w", "/root",
                        "/bin/bash", "-c",
                        "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                                + "export HOME=/root; export TERM=xterm; "
                                + "export DSH_HOME=/root/.dsh; export DSH_TELEMETRY_DISABLED=1; "
                                + "mkdir -p $DSH_HOME; "
                                + "if [ -f /root/dsh.pid ]; then kill $(cat /root/dsh.pid) 2>/dev/null || true; fi; "
                                + "dsh web --port " + port() + " >> /root/dsh.log 2>&1 & "
                                + "DPID=$!; echo $DPID > /root/dsh.pid; wait $DPID"
                );
                pb.redirectErrorStream(true);
                prootProcess = pb.start();
                log("   proot 已启动 (pid=" + pidOf(prootProcess) + ")，等待 dsh 就绪…");
                pumpLog(prootProcess.getInputStream(), prootLog);
                boolean ok = waitForServer(BOOT_TIMEOUT_MS);
                if (ok) {
                    ui.post(() -> { setBusy(false); loadWebView(); });
                } else {
                    ui.post(() -> {
                        setBusy(false);
                        log("✗ dsh 启动超时（" + (BOOT_TIMEOUT_MS / 1000) + " 秒）。");
                        log("  请点「复制日志」把日志发给我，或点「重试」。");
                        toast("启动超时，见日志");
                    });
                }
            } catch (final Throwable t) {
                ui.post(() -> {
                    setBusy(false);
                    log("✗ 启动异常：" + t);
                    log("  请点「复制日志」把日志发给我，或点「重试」。");
                    toast("启动异常：" + t);
                });
            } finally {
                booting = false;
            }
        }).start();
    }

    private boolean waitForServer(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (killed) return false;
            if (prootProcess == null || !prootProcess.isAlive()) return false;
            if (httpOk("http://127.0.0.1:" + port() + "/")) return true;
            try { Thread.sleep(1000); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    private boolean httpOk(String urlStr) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read proot output into a capped log file. */
    private void pumpLog(final InputStream in, final File log) {
        new Thread(() -> {
            try {
                FileOutputStream out = new FileOutputStream(log, true);
                byte[] buf = new byte[8192];
                int r;
                long written = log.length();
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    written += r;
                    if (written > LOG_CAP) {
                        out.close();
                        RandomAccessFile raf = new RandomAccessFile(log, "rw");
                        long skip = Math.max(0, log.length() - LOG_CAP);
                        raf.seek(skip);
                        byte[] tail = new byte[(int) (log.length() - skip)];
                        raf.readFully(tail);
                        raf.setLength(0);
                        raf.write(tail);
                        raf.close();
                        out = new FileOutputStream(log, true);
                        written = log.length();
                    }
                }
                out.close();
            } catch (IOException ignored) {}
        }).start();
    }

    // ---------------- WebView UI ----------------

    /** 当前服务端口（可在设置中修改，默认 3091）。 */
    private int port() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getInt("port", DEFAULT_PORT);
    }

    /** 桌面模式：默认开启。开启时用桌面 UA + 固定宽视口(1280px)，网页端按电脑布局渲染。 */
    private boolean isDesktopMode() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getBoolean("desktop_mode", true);
    }

    private void toggleDesktopMode() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean("desktop_mode", !isDesktopMode()).apply();
        toast("桌面模式：" + (isDesktopMode() ? "开" : "关") + "，正在刷新…");
        if (webView != null) webView.reload();
    }

    private void loadWebView() {
        ui.post(() -> {
            FrameLayout frame = new FrameLayout(this);
            frame.setBackgroundColor(Color.BLACK);

            webView = new WebView(this);
            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            // 浏览器式缩放：双指捏合 + 双击（隐藏系统 +/－ 按钮）
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            if (isDesktopMode()) s.setUserAgentString(DESKTOP_UA);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    return true; // keep everything inside the app
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                    // 桌面模式：把主页的 viewport 从 device-width 重写为固定宽，
                    // 让 window.innerWidth >= 1024，前端按桌面三栏布局渲染。
                    if (isDesktopMode() && (url.equals("http://127.0.0.1:" + port() + "/")
                            || url.equals("http://127.0.0.1:" + port() + "/index.html"))) {
                        try {
                            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                            c.setConnectTimeout(5000);
                            c.setReadTimeout(5000);
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            try (InputStream in = c.getInputStream()) {
                                byte[] buf = new byte[8192];
                                int r;
                                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                            } finally {
                                c.disconnect();
                            }
                            String html = bos.toString("UTF-8");
                            html = html.replace("width=device-width", "width=" + DESKTOP_VIEWPORT);
                            return new WebResourceResponse("text/html", "utf-8",
                                    new ByteArrayInputStream(html.getBytes("UTF-8")));
                        } catch (Exception e) {
                            return null; // 失败则走默认加载
                        }
                    }
                    return null;
                }
            });
            webView.setWebChromeClient(new WebChromeClient());
            webView.loadUrl("http://127.0.0.1:" + port());
            frame.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 右上角：刷新 + 设置
            LinearLayout topBox = new LinearLayout(this);
            topBox.setOrientation(LinearLayout.HORIZONTAL);
            Button refresh = mkButton("刷新", 2);
            refresh.setTextSize(13);
            refresh.setOnClickListener(v -> {
                if (webView != null) webView.reload();
            });
            Button gear = mkButton("⚙", 2);
            gear.setTextSize(18);
            gear.setOnClickListener(v -> showSettingsDialog());
            topBox.addView(refresh);
            topBox.addView(gear);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.gravity = Gravity.TOP | Gravity.END;
            tlp.setMargins(0, 60, 24, 0);
            frame.addView(topBox, tlp);

            // 右下角浏览器式缩放入口（＋/－）
            LinearLayout zoomBox = new LinearLayout(this);
            zoomBox.setOrientation(LinearLayout.VERTICAL);
            Button zin = mkButton("＋", 2);
            zin.setTextSize(18);
            zin.setOnClickListener(v -> { if (webView != null) webView.zoomIn(); });
            Button zout = mkButton("－", 2);
            zout.setTextSize(18);
            zout.setOnClickListener(v -> { if (webView != null) webView.zoomOut(); });
            zoomBox.addView(zin);
            zoomBox.addView(zout);
            FrameLayout.LayoutParams zlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            zlp.gravity = Gravity.BOTTOM | Gravity.END;
            zlp.setMargins(0, 0, 24, 64);
            frame.addView(zoomBox, zlp);

            setContentView(frame);
        });
    }

    // ---------------- 自定义深色弹窗 ----------------

    private static final int DIALOG_ROOT_ID = 0x50501;

    private Dialog buildDialog(String title) {
        Dialog d = new Dialog(this);
        if (d.getWindow() != null) d.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setId(DIALOG_ROOT_ID);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog);
        root.setPadding(dp(20), dp(18), dp(20), dp(14));

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(18);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleTv.setPadding(dp(4), 0, dp(4), dp(12));
        root.addView(titleTv);

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(d.getWindow().getAttributes());
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            d.getWindow().setAttributes(lp);
        }
        return d;
    }

    private LinearLayout dialogRoot(Dialog d) {
        return (LinearLayout) d.findViewById(DIALOG_ROOT_ID);
    }

    private View mkRow(String label, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_item);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setMinimumHeight(dp(52));
        row.setOnClickListener(v -> action.run());
        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextColor(Color.WHITE);
        lv.setTextSize(15);
        lv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lv);
        if (value != null) {
            TextView vv = new TextView(this);
            vv.setText(value);
            vv.setTextColor(Color.rgb(140, 162, 198));
            vv.setTextSize(14);
            row.addView(vv);
        }
        return row;
    }

    private View mkDivider() {
        View v = new View(this);
        v.setBackgroundColor(Color.rgb(36, 51, 84));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private void showSettingsDialog() {
        final Dialog d = buildDialog("设置");
        LinearLayout root = dialogRoot(d);
        root.addView(mkRow("修改 API Key 并重启", null, () -> { d.dismiss(); editKeyDialog(); }));
        root.addView(mkDivider());
        root.addView(mkRow("修改服务端口", "当前 " + port(), () -> { d.dismiss(); editPortDialog(); }));
        root.addView(mkDivider());
        root.addView(mkRow("桌面模式", isDesktopMode() ? "开" : "关", () -> { d.dismiss(); toggleDesktopMode(); }));
        root.addView(mkDivider());
        root.addView(mkRow("查看运行日志", null, () -> { d.dismiss(); showLogDialog(); }));
        root.addView(mkDivider());
        root.addView(mkRow("复制日志", null, () -> { d.dismiss(); copyLogToClipboard(); }));
        root.addView(mkDivider());
        root.addView(mkRow("重启服务", null, () -> {
            d.dismiss();
            killServer();
            startServer();
            toast("正在重启…");
        }));
        root.addView(mkDivider());
        root.addView(mkRow("退出应用", null, () -> { d.dismiss(); finish(); }));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        Button cancel = mkButton("取消", 1);
        cancel.setOnClickListener(v -> d.dismiss());
        btns.addView(cancel);
        root.addView(btns);
        d.show();
    }

    private void editPortDialog() {
        final Dialog d = buildDialog("修改服务端口");
        LinearLayout root = dialogRoot(d);
        root.addView(mkLabel("端口范围 1024-65535。改完自动重启服务并刷新界面。", 12, false));
        final EditText input = mkEdit("端口号", false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(port()));
        root.addView(input);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        Button cancel = mkButton("取消", 1);
        cancel.setOnClickListener(v -> d.dismiss());
        btns.addView(cancel);
        Button save = mkButton("保存并重启", 0);
        save.setOnClickListener(v -> {
            int p;
            try {
                p = Integer.parseInt(input.getText().toString().trim());
            } catch (Exception e) {
                toast("端口必须是数字");
                return;
            }
            if (p < 1024 || p > 65535) {
                toast("端口范围：1024-65535");
                return;
            }
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt("port", p).apply();
            toast("端口已改为 " + p + "，正在重启…");
            killServer();
            startServer();
            d.dismiss();
        });
        btns.addView(save);
        root.addView(btns);
        d.show();
    }

    private void editKeyDialog() {
        final Dialog d = buildDialog("修改 DeepSeek API Key");
        LinearLayout root = dialogRoot(d);
        final EditText input = mkEdit("sk-...", true);
        String cur = readApiKey();
        if (cur != null) input.setText(cur);
        root.addView(input);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        Button cancel = mkButton("取消", 1);
        cancel.setOnClickListener(v -> d.dismiss());
        btns.addView(cancel);
        Button save = mkButton("保存并重启", 0);
        save.setOnClickListener(v -> {
            String key = input.getText().toString().trim();
            if (key.isEmpty()) { toast("Key 不能为空"); return; }
            try {
                writeApiKey(key);
                killServer();
                startServer();
                toast("已保存，正在重启…");
                if (webView != null) {
                    webView.postDelayed(() -> webView.reload(), 15000);
                }
                d.dismiss();
            } catch (IOException e) {
                toast("保存失败：" + e);
            }
        });
        btns.addView(save);
        root.addView(btns);
        d.show();
    }

    private String readApiKey() {
        try (BufferedReader r = new BufferedReader(new FileReader(
                new File(rootfsDir, "root/.dsh/.credentials.yaml")))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("DEEPSEEK_API_KEY:")) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private void showLogDialog() {
        final Dialog d = buildDialog("运行日志");
        LinearLayout root = dialogRoot(d);
        String text = readLogTail();
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Color.rgb(158, 224, 196));
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setBackgroundResource(R.drawable.bg_card);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320));
        slp.setMargins(0, dp(4), 0, dp(12));
        root.addView(sv, slp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        Button copy = mkButton("复制", 1);
        copy.setOnClickListener(v -> copyLogToClipboard());
        btns.addView(copy);
        Button close = mkButton("关闭", 0);
        close.setOnClickListener(v -> d.dismiss());
        btns.addView(close);
        root.addView(btns);
        d.show();
    }

    // ---------------- shutdown ----------------

    private void killServer() {
        // kill the dsh child first (its pid is written by boot.sh)
        File pidFile = new File(rootfsDir, "root/dsh.pid");
        try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
            String line = r.readLine();
            if (line != null) {
                try { android.os.Process.killProcess(Integer.parseInt(line.trim())); } catch (Throwable ignored) {}
            }
        } catch (IOException ignored) {}

        if (prootProcess != null) {
            int pid = pidOf(prootProcess);
            if (pid > 0) {
                try { android.os.Process.killProcess(pid); } catch (Throwable ignored) {}
            }
            try { prootProcess.destroy(); } catch (Throwable ignored) {}
            prootProcess = null;
        }
    }

    private static int pidOf(Process p) {
        try {
            Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Throwable t) {
            return -1;
        }
    }
}
