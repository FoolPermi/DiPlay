package com.diplay.geelyprobe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.app.AppOpsManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.lib.oneosapi.input.IInputListener;
import com.geely.lib.oneosapi.input.IInputManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lists Geely/Lynk packages and services on the head unit, checks the vendor actions and explicit
 * components, and tries to bind them. Findings are shown on screen as big coloured lines so the
 * result can be photographed, with the full listing below.
 */
public class GeelyProbeActivity extends Activity {

    private static final String[] BRAND_KEYWORDS = {
            "geely", "lynk", "desaysv", "ecarx", "oneos", "hicar", "galaxy",
    };

    private static final String[] PROBE_ACTIONS = {
            "com.geely.service.ONLINE_MUSIC",
            "com.geely.service.MEDIA_CENTER",
            "com.geely.service.MEDIA",
            "com.geely.service.INPUT",
            "com.geely.service.CLUSTER",
            "com.geely.service.HUD",
            "geely.intent.action.MEDIA_CENTER",
    };

    private static final String[][] PROBE_COMPONENTS = {
            {"com.geely.inputservice", "com.geely.inputservice.InputService"},
            {"com.geely.online.service", "com.geely.online.service.OnlineMusicService"},
            {"com.geely.mediacenter", "com.geely.mediacenter.MediaCenterService"},
            {"com.geely.mediacenter.service", "com.geely.mediacenter.service.MediaCenterService"},
    };

    private static final int OK = 0xFF1B5E20;      // green
    private static final int WARN = 0xFFE65100;    // orange
    private static final int MISSING = 0xFFB71C1C; // red
    private static final int TEXT = 0xFF111111;

    private LinearLayout summaryBox;
    private TextView detailView;
    private TextView keyStatus;
    private TextView keyLog;
    private final StringBuilder keyLogText = new StringBuilder();
    private IInputManager inputManager;
    private IInputListener keyListener;
    private boolean keyBound;
    private TextView broadcastLog;
    private final StringBuilder broadcastLogText = new StringBuilder();
    private BroadcastReceiver keyBroadcastReceiver;
    private boolean broadcastRegistered;
    private TextView clusterStatus;
    private IBinder hyBinder;
    private boolean hyBound;
    private EditText hySsidView;
    private EditText hyPwdView;
    private TextView hyStatus;
    private LinearLayout bootBox;
    private TextView bootStatus;

    private static final class Finding {
        final String label;
        final String value;
        final int state; // OK / WARN / MISSING

        Finding(String label, String value, int state) {
            this.label = label;
            this.value = value;
            this.state = state;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Geely Probe");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        list.addView(title);

        TextView info = new TextView(this);
        info.setTextSize(14);
        info.setTextColor(TEXT);
        info.setPadding(0, dp(6), 0, dp(10));
        info.setText(String.format(Locale.US,
                "Android %s (API %d)  %s\n%s / %s\nBRAND=%s PRODUCT=%s",
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.HARDWARE,
                Build.MANUFACTURER, Build.MODEL, Build.BRAND, Build.PRODUCT));
        list.addView(info);

        Button scan = new Button(this);
        scan.setText("开始扫描");
        scan.setAllCaps(false);
        scan.setTextSize(18);
        scan.setOnClickListener(v -> runScan());
        list.addView(scan, matchWrap());

        Button copy = new Button(this);
        copy.setText("复制结果");
        copy.setAllCaps(false);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && detailView != null) {
                cm.setPrimaryClip(ClipData.newPlainText("geely-probe", detailView.getText()));
            }
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        list.addView(copy, matchWrap());

        // ---- 方向盘按键抓取 ----
        TextView keyTitle = new TextView(this);
        keyTitle.setText("—— 方向盘按键抓取 ——");
        keyTitle.setTextSize(16);
        keyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        keyTitle.setPadding(0, dp(16), 0, dp(4));
        list.addView(keyTitle);

        TextView keyHint = new TextView(this);
        keyHint.setText("点\"开始抓取\"，然后逐个按方向盘按键（上一曲/下一曲/播放/语音…），"
                + "每按一个下面就会记下它的 keyCode。把这段拍给我。");
        keyHint.setTextSize(13);
        keyHint.setPadding(0, 0, 0, dp(6));
        list.addView(keyHint);

        LinearLayout keyButtons = new LinearLayout(this);
        keyButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button keyStart = new Button(this);
        keyStart.setText("开始抓取");
        keyStart.setAllCaps(false);
        keyStart.setOnClickListener(v -> startKeyCapture());
        keyButtons.addView(keyStart,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button keyStop = new Button(this);
        keyStop.setText("停止");
        keyStop.setAllCaps(false);
        keyStop.setOnClickListener(v -> stopKeyCapture());
        keyButtons.addView(keyStop,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(keyButtons);

        keyStatus = new TextView(this);
        keyStatus.setTextSize(14);
        keyStatus.setTextColor(TEXT);
        keyStatus.setPadding(0, dp(6), 0, 0);
        list.addView(keyStatus);

        keyLog = new TextView(this);
        keyLog.setTextSize(18);
        keyLog.setTypeface(Typeface.MONOSPACE);
        keyLog.setTextColor(OK);
        keyLog.setPadding(0, dp(6), 0, dp(6));
        list.addView(keyLog);

        // ---- 方向盘按键广播抓取 ----
        TextView bcTitle = new TextView(this);
        bcTitle.setText("—— 按键广播抓取 ——");
        bcTitle.setTextSize(16);
        bcTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bcTitle.setPadding(0, dp(16), 0, dp(4));
        list.addView(bcTitle);

        TextView bcHint = new TextView(this);
        bcHint.setText("点\"开始监听\"，然后按方向盘按键。这里会显示收到的广播和它的所有参数。");
        bcHint.setTextSize(13);
        bcHint.setPadding(0, 0, 0, dp(6));
        list.addView(bcHint);

        LinearLayout bcButtons = new LinearLayout(this);
        bcButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button bcStart = new Button(this);
        bcStart.setText("开始监听");
        bcStart.setAllCaps(false);
        bcStart.setOnClickListener(v -> startBroadcastCapture());
        bcButtons.addView(bcStart, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button bcStop = new Button(this);
        bcStop.setText("停止");
        bcStop.setAllCaps(false);
        bcStop.setOnClickListener(v -> stopBroadcastCapture());
        bcButtons.addView(bcStop, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(bcButtons);

        broadcastLog = new TextView(this);
        broadcastLog.setTextSize(12);
        broadcastLog.setTypeface(Typeface.MONOSPACE);
        broadcastLog.setTextColor(TEXT);
        broadcastLog.setPadding(0, dp(6), 0, dp(6));
        list.addView(broadcastLog);

        // ---- 仪表盘投屏测试 ----
        TextView clTitle = new TextView(this);
        clTitle.setText("—— 仪表盘投屏测试 ——");
        clTitle.setTextSize(16);
        clTitle.setTypeface(Typeface.DEFAULT_BOLD);
        clTitle.setPadding(0, dp(16), 0, dp(4));
        list.addView(clTitle);

        Button clTest = new Button(this);
        clTest.setText("推一条假歌到仪表盘");
        clTest.setAllCaps(false);
        clTest.setOnClickListener(v -> testClusterDisplay());
        list.addView(clTest, matchWrap());

        clusterStatus = new TextView(this);
        clusterStatus.setTextSize(13);
        clusterStatus.setTextColor(TEXT);
        clusterStatus.setPadding(0, dp(6), 0, dp(6));
        list.addView(clusterStatus);

        // ---- HY 支持库热点测试 ----
        TextView hyTitle = new TextView(this);
        hyTitle.setText("—— HY 支持库热点测试 ——");
        hyTitle.setTextSize(16);
        hyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        hyTitle.setPadding(0, dp(16), 0, dp(4));
        list.addView(hyTitle);

        TextView hyHint = new TextView(this);
        hyHint.setText("先点\"绑定支持库\"，再填你车机热点的名称/密码，点\"开热点\"。"
                + "（支持库 = com.hyphp.hywebviewlibdcy）");
        hyHint.setTextSize(13);
        hyHint.setPadding(0, 0, 0, dp(6));
        list.addView(hyHint);

        hySsidView = new EditText(this);
        hySsidView.setHint("热点 SSID（车机热点名称）");
        hySsidView.setTextSize(16);
        list.addView(hySsidView, matchWrap());

        hyPwdView = new EditText(this);
        hyPwdView.setHint("热点密码（至少 8 位）");
        hyPwdView.setTextSize(16);
        list.addView(hyPwdView, matchWrap());

        LinearLayout hyButtons = new LinearLayout(this);
        hyButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button hyBind = new Button(this);
        hyBind.setText("绑定支持库");
        hyBind.setAllCaps(false);
        hyBind.setOnClickListener(v -> bindHySupport());
        hyButtons.addView(hyBind, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button hyQuery = new Button(this);
        hyQuery.setText("查询(50)");
        hyQuery.setAllCaps(false);
        hyQuery.setOnClickListener(v -> queryHotspot());
        hyButtons.addView(hyQuery, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button hyOn = new Button(this);
        hyOn.setText("开热点(52)");
        hyOn.setAllCaps(false);
        hyOn.setOnClickListener(v -> setHotspot(true));
        hyButtons.addView(hyOn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button hyOff = new Button(this);
        hyOff.setText("关(51)");
        hyOff.setAllCaps(false);
        hyOff.setOnClickListener(v -> setHotspot(false));
        hyButtons.addView(hyOff, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(hyButtons);

        Button hyDirect = new Button(this);
        hyDirect.setText("直接开热点（平台签名）");
        hyDirect.setAllCaps(false);
        hyDirect.setOnClickListener(v -> directHotspot());
        list.addView(hyDirect, matchWrap());

        hyStatus = new TextView(this);
        hyStatus.setTextSize(13);
        hyStatus.setTextColor(TEXT);
        hyStatus.setPadding(0, dp(6), 0, dp(6));
        list.addView(hyStatus);

        // ---- 开机自启管理 ----
        TextView bootTitle = new TextView(this);
        bootTitle.setText("—— 开机自启管理 ——");
        bootTitle.setTextSize(16);
        bootTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bootTitle.setPadding(0, dp(16), 0, dp(4));
        list.addView(bootTitle);

        TextView bootHint = new TextView(this);
        bootHint.setText("点\"列出自启应用\"→ 列出所有收 BOOT_COMPLETED 的应用；"
                + "点某个应用就能切换它的\"开机自启\"（需要平台签名）。");
        bootHint.setTextSize(13);
        bootHint.setPadding(0, 0, 0, dp(6));
        list.addView(bootHint);

        LinearLayout bootButtons = new LinearLayout(this);
        bootButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button bootList = new Button(this);
        bootList.setText("列出自启应用");
        bootList.setAllCaps(false);
        bootList.setOnClickListener(v -> listBootReceivers());
        bootButtons.addView(bootList, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button bootOps = new Button(this);
        bootOps.setText("AppOps 自启测试");
        bootOps.setAllCaps(false);
        bootOps.setOnClickListener(v -> testAutoStartOp());
        bootButtons.addView(bootOps, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(bootButtons);

        bootStatus = new TextView(this);
        bootStatus.setTextSize(12);
        bootStatus.setTextColor(TEXT);
        bootStatus.setPadding(0, dp(6), 0, dp(4));
        list.addView(bootStatus);

        bootBox = new LinearLayout(this);
        bootBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(bootBox);

        TextView summaryTitle = new TextView(this);
        summaryTitle.setText("—— 结论 ——");
        summaryTitle.setTextSize(16);
        summaryTitle.setTypeface(Typeface.DEFAULT_BOLD);
        summaryTitle.setPadding(0, dp(12), 0, dp(6));
        list.addView(summaryTitle);

        summaryBox = new LinearLayout(this);
        summaryBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(summaryBox);

        TextView detailTitle = new TextView(this);
        detailTitle.setText("—— 详细 ——");
        detailTitle.setTextSize(16);
        detailTitle.setTypeface(Typeface.DEFAULT_BOLD);
        detailTitle.setPadding(0, dp(14), 0, dp(6));
        list.addView(detailTitle);

        detailView = new TextView(this);
        detailView.setTextSize(11);
        detailView.setTypeface(Typeface.MONOSPACE);
        detailView.setTextColor(TEXT);
        list.addView(detailView);

        scroll.addView(list);
        setContentView(scroll);
    }

    private void runScan() {
        summaryBox.removeAllViews();
        detailView.setText("扫描中…");
        new Thread(this::scanBlocking, "geely-probe").start();
    }

    private void scanBlocking() {
        PackageManager pm = getPackageManager();
        StringBuilder detail = new StringBuilder();
        List<Finding> findings = new ArrayList<>();

        // Packages whose own name matches a brand keyword (ourselves excluded).
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(PackageManager.GET_SERVICES);
        } catch (Throwable t) {
            all = new ArrayList<>();
            detail.append("getInstalledPackages failed: ").append(t).append('\n');
        }
        List<String> matched = new ArrayList<>();
        for (PackageInfo pi : all) {
            if (!pi.packageName.equals(getPackageName()) && matchesBrand(pi.packageName)) {
                matched.add(pi.packageName);
            }
        }
        Collections.sort(matched);
        findings.add(new Finding("Geely/Lynk 相关包",
                matched.isEmpty() ? "没有找到" : (matched.size() + " 个：" + String.join(", ", matched)),
                matched.isEmpty() ? MISSING : OK));

        detail.append("===== Geely/Lynk 包与服务 =====\n");
        if (matched.isEmpty()) {
            detail.append("(无)\n");
        }
        for (String pkg : matched) {
            detail.append("PKG ").append(pkg).append('\n');
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES);
                if (pi.services != null) {
                    for (ServiceInfo si : pi.services) {
                        detail.append("   svc ").append(si.name)
                                .append(si.exported ? "  [exported]" : "  [private]")
                                .append(!si.enabled ? "  [disabled]" : "").append('\n');
                    }
                }
            } catch (Throwable t) {
                detail.append("   (info failed ").append(t.getClass().getSimpleName()).append(")\n");
            }
        }

        // Explicit components.
        detail.append("\n===== 显式组件 & bind =====\n");
        for (String[] pair : PROBE_COMPONENTS) {
            String pkg = pair[0];
            String cls = pair[1];
            String line = pkg + "/" + cls + "  ";
            ComponentName cn = new ComponentName(pkg, cls);
            boolean found;
            try {
                ServiceInfo si = pm.getServiceInfo(cn, 0);
                found = true;
                line += si.exported ? "[exported] " : "[private] ";
            } catch (Throwable t) {
                found = false;
                line += "NOT FOUND";
            }
            if (found) {
                String bind = tryBind(new Intent().setComponent(cn));
                line += bind;
                findings.add(new Finding(shortName(cls), bind, bind.contains("OK") ? OK : WARN));
            } else if (cls.contains("Input") || cls.contains("Music")) {
                findings.add(new Finding(shortName(cls), "服务不存在", MISSING));
            }
            detail.append(line).append('\n');
        }

        // Vendor actions.
        detail.append("\n===== action 查询 =====\n");
        for (String action : PROBE_ACTIONS) {
            List<ResolveInfo> rs = pm.queryIntentServices(new Intent(action), 0);
            detail.append("action ").append(action).append(" -> ").append(rs.size()).append('\n');
            for (ResolveInfo r : rs) {
                if (r.serviceInfo != null) {
                    detail.append("    ").append(r.serviceInfo.packageName).append('/')
                            .append(r.serviceInfo.name)
                            .append(r.serviceInfo.exported ? "  [exported]" : "  [private]").append('\n');
                }
            }
            if (action.startsWith("com.geely.service.")
                    && (action.endsWith("ONLINE_MUSIC") || action.endsWith("MEDIA_CENTER"))) {
                findings.add(new Finding("action " + action.substring("com.geely.service.".length()),
                        rs.isEmpty() ? "0 个" : (rs.size() + " 个"),
                        rs.isEmpty() ? MISSING : OK));
            }
        }

        String detailText = detail.toString();
        runOnUiThread(() -> {
            summaryBox.removeAllViews();
            for (Finding f : findings) {
                summaryBox.addView(findingView(f));
            }
            detailView.setText(detailText);
        });
    }

    private void startKeyCapture() {
        if (keyBound) {
            keyStatus.setText("已在抓取中");
            return;
        }
        keyLogText.setLength(0);
        keyLog.setText("");
        Intent intent = new Intent().setComponent(
                new ComponentName("com.geely.inputservice", "com.geely.inputservice.InputService"));
        boolean bound;
        try {
            bound = bindService(intent, keyConnection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            keyStatus.setTextColor(MISSING);
            keyStatus.setText("bind 异常: " + t.getClass().getSimpleName());
            return;
        }
        keyBound = bound;
        keyStatus.setTextColor(bound ? WARN : MISSING);
        keyStatus.setText(bound ? "bind=OK，等待服务连接…" : "bind=false（服务不存在或不可绑定）");
    }

    private void stopKeyCapture() {
        if (keyListener != null && inputManager != null) {
            try {
                inputManager.unregisterListener(keyListener, getPackageName());
            } catch (Throwable ignored) {
                // Best effort.
            }
        }
        if (keyBound) {
            try {
                unbindService(keyConnection);
            } catch (Throwable ignored) {
                // Never bound.
            }
        }
        keyBound = false;
        inputManager = null;
        keyListener = null;
        keyStatus.setTextColor(TEXT);
        keyStatus.setText("已停止");
    }

    private final ServiceConnection keyConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            inputManager = IInputManager.Stub.asInterface(service);
            keyListener = new IInputListener.Stub() {
                @Override
                public void onKeyCodeEvent(int keyCode, int event, int softKeyFunction) {
                    appendKey("onKeyCodeEvent", keyCode, "event=" + event + " soft=" + softKeyFunction);
                }

                @Override
                public void onShortClick(int keyCode, int softKeyFunction) {
                    appendKey("onShortClick", keyCode, "soft=" + softKeyFunction);
                }

                @Override
                public void onHoldingPressStarted(int keyCode, int softKeyFunction) {
                    appendKey("onHoldingPressStarted", keyCode, "soft=" + softKeyFunction);
                }

                @Override
                public void onHoldingPressStopped(int keyCode, int softKeyFunction) {
                    appendKey("onHoldingPressStopped", keyCode, "soft=" + softKeyFunction);
                }

                @Override
                public void onLongPressTriggered(int keyCode, int softKeyFunction) {
                    appendKey("onLongPressTriggered", keyCode, "soft=" + softKeyFunction);
                }

                @Override
                public void onDoubleClick(int keyCode, int softKeyFunction) {
                    appendKey("onDoubleClick", keyCode, "soft=" + softKeyFunction);
                }
            };
            int[] codes = new int[601 + 501];
            int index = 0;
            for (int code = 0; code <= 600; code++) {
                codes[index++] = code;
            }
            for (int code = 200000; code <= 200500; code++) {
                codes[index++] = code;
            }
            try {
                inputManager.registerListener(keyListener, getPackageName(), codes);
                keyStatus.setTextColor(OK);
                keyStatus.setText("服务已连接，注册了 " + codes.length + " 个 keyCode。\n现在逐个按方向盘按键：");
            } catch (Throwable t) {
                keyStatus.setTextColor(MISSING);
                keyStatus.setText("registerListener 失败: " + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : " " + t.getMessage()));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            keyStatus.setTextColor(WARN);
            keyStatus.setText("服务断开");
            inputManager = null;
            keyListener = null;
        }
    };

    private void appendKey(String callback, int keyCode, String extra) {
        String line = callback + "  keyCode=" + keyCode + "  " + extra + "\n";
        runOnUiThread(() -> {
            keyLogText.append(line);
            keyLog.setText(keyLogText.toString());
        });
    }

    private void startBroadcastCapture() {
        if (broadcastRegistered) {
            return;
        }
        keyBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                dumpBroadcast(intent);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.nwd.action.ACTION_KEY_VALUE");
        filter.addAction(Intent.ACTION_MEDIA_BUTTON);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(keyBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(keyBroadcastReceiver, filter);
            }
            broadcastRegistered = true;
            broadcastLogText.append("监听中：com.nwd.action.ACTION_KEY_VALUE / MEDIA_BUTTON\n");
            broadcastLog.setText(broadcastLogText.toString());
        } catch (Throwable t) {
            broadcastLogText.append("注册失败: ").append(t).append('\n');
            broadcastLog.setText(broadcastLogText.toString());
        }
    }

    private void stopBroadcastCapture() {
        if (broadcastRegistered && keyBroadcastReceiver != null) {
            try {
                unregisterReceiver(keyBroadcastReceiver);
            } catch (Throwable ignored) {
                // already gone
            }
        }
        broadcastRegistered = false;
        broadcastLogText.append("已停止\n");
        broadcastLog.setText(broadcastLogText.toString());
    }

    private void dumpBroadcast(Intent intent) {
        StringBuilder sb = new StringBuilder();
        sb.append("==== ").append(intent.getAction()).append(" ====\n");
        try {
            sb.append(intent.toUri(Intent.URI_INTENT_SCHEME)).append('\n');
        } catch (Throwable t) {
            sb.append("(toUri failed: ").append(t.getClass().getSimpleName()).append(")\n");
        }
        android.os.Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value;
                try {
                    value = extras.get(key);
                } catch (Throwable t) {
                    value = "<" + t.getClass().getSimpleName() + ">";
                }
                sb.append("   ").append(key).append(" = ").append(value).append('\n');
            }
        }
        String text = sb.toString();
        runOnUiThread(() -> {
            broadcastLogText.append(text);
            broadcastLog.setText(broadcastLogText.toString());
        });
    }

    // ===== 开机自启管理 =====

    private void listBootReceivers() {
        bootBox.removeAllViews();
        PackageManager pm = getPackageManager();
        List<ResolveInfo> receivers;
        try {
            receivers = pm.queryBroadcastReceivers(new Intent(Intent.ACTION_BOOT_COMPLETED), 0);
        } catch (Throwable t) {
            bootStatus.setTextColor(MISSING);
            bootStatus.setText("查询失败: " + t);
            return;
        }
        List<ActivityInfo> infos = new ArrayList<>();
        for (ResolveInfo r : receivers) {
            if (r.activityInfo != null) {
                infos.add(r.activityInfo);
            }
        }
        Collections.sort(infos, (a, b) -> a.packageName.compareTo(b.packageName));
        bootStatus.setTextColor(OK);
        bootStatus.setText("共 " + infos.size() + " 个开机自启接收器（点一下可切换）");
        for (ActivityInfo ai : infos) {
            addBootRow(pm, ai.packageName, ai.name);
        }
    }

    private void addBootRow(PackageManager pm, String pkg, String cls) {
        ComponentName cn = new ComponentName(pkg, cls);
        int state;
        int appState;
        try {
            state = pm.getComponentEnabledSetting(cn);
            appState = pm.getApplicationEnabledSetting(pkg);
        } catch (Throwable t) {
            state = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            appState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }
        Button row = new Button(this);
        row.setAllCaps(false);
        row.setText(pkg + "\n" + bootStateLabel(state, appState));
        row.setTextSize(13);
        row.setOnClickListener(v -> toggleBoot(cn, row));
        bootBox.addView(row, matchWrap());
    }

    private String bootStateLabel(int state, int appState) {
        if (appState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return "应用已禁用";
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return "自启：已禁止 ✗";
        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return "自启：已允许 ✓";
        return "自启：默认";
    }

    private void toggleBoot(ComponentName cn, Button row) {
        PackageManager pm = getPackageManager();
        int current = pm.getComponentEnabledSetting(cn);
        int next = current == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        try {
            pm.setComponentEnabledSetting(cn, next, PackageManager.DONT_KILL_APP);
            int now = pm.getComponentEnabledSetting(cn);
            String label = now == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ? "自启：已禁止 ✗" : "自启：已允许 ✓";
            row.setText(cn.getPackageName() + "\n" + label);
            bootStatus.setTextColor(OK);
            bootStatus.setText("切换成功：" + cn.getPackageName() + " -> " + label);
        } catch (Throwable t) {
            bootStatus.setTextColor(MISSING);
            bootStatus.setText("切换失败：" + cn.getPackageName() + "\n" + t);
        }
    }

    /** Tries the OEM/hidden AppOps auto-start op (10008) on this app itself. */
    @SuppressWarnings("PrivateApi")
    private void testAutoStartOp() {
        StringBuilder sb = new StringBuilder();
        int uid = android.os.Process.myUid();
        String pkg = getPackageName();
        sb.append("包=").append(pkg).append(" uid=").append(uid).append('\n');
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            Method setMode = AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class);
            Method getMode = AppOpsManager.class.getMethod("getMode", int.class, int.class, String.class);
            Object before = getMode.invoke(aom, 10008, uid, pkg);
            sb.append("getMode(10008) 读之前: ").append(before).append('\n');
            setMode.invoke(aom, 10008, uid, pkg, AppOpsManager.MODE_IGNORED);
            Object ignored = getMode.invoke(aom, 10008, uid, pkg);
            setMode.invoke(aom, 10008, uid, pkg, AppOpsManager.MODE_ALLOWED);
            Object allowed = getMode.invoke(aom, 10008, uid, pkg);
            sb.append("设为 IGNORED 后: ").append(ignored).append('\n');
            sb.append("设为 ALLOWED 后: ").append(allowed).append('\n');
            sb.append("→ AppOps 10008 可读写 ✓");
            bootStatus.setTextColor(OK);
        } catch (Throwable t) {
            sb.append("AppOps 10008 失败: ").append(t).append('\n');
            if (t.getCause() != null) sb.append("  原因: ").append(t.getCause()).append('\n');
            bootStatus.setTextColor(MISSING);
        }
        bootStatus.setText(sb.toString());
    }

    // ===== HY 支持库 AIDL 热点测试 =====

    private static final String HY_PACKAGE = "com.hyphp.hywebviewlibdcy";
    private static final String HY_ACTION = "com.hyphp.hywebviewlibdcy.action";
    private static final String HY_DESCRIPTOR = "com.hyphp.hywebviewlibdcy.HYApi";
    private static final int TX_IS_AP_ENABLED = 50;
    private static final int TX_STOP_SOFTAP = 51;
    private static final int TX_START_SOFTAP = 52;

    private void bindHySupport() {
        if (hyBound) {
            hyStatus.setText("已绑定");
            return;
        }
        Intent intent = new Intent(HY_ACTION);
        intent.setPackage(HY_PACKAGE);
        boolean bound;
        try {
            bound = bindService(intent, hyConnection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            hyStatus.setTextColor(MISSING);
            hyStatus.setText("bind 异常: " + t);
            return;
        }
        hyBound = bound;
        hyStatus.setTextColor(bound ? WARN : MISSING);
        hyStatus.setText(bound ? "bind=OK，等待服务…" : "bind=false（支持库没装？）");
    }

    private final ServiceConnection hyConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            hyBinder = service;
            hyStatus.setTextColor(OK);
            hyStatus.setText("已连接支持库。现在填 SSID/密码 → 点开热点(52)。");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            hyBinder = null;
            hyStatus.setTextColor(WARN);
            hyStatus.setText("支持库已断开");
        }
    };

    private void queryHotspot() {
        hyStatus.setText(hyTransact(TX_IS_AP_ENABLED, null, null));
    }

    private void setHotspot(boolean on) {
        if (on) {
            String ssid = hySsidView.getText().toString().trim();
            String pwd = hyPwdView.getText().toString();
            if (ssid.isEmpty()) {
                hyStatus.setTextColor(MISSING);
                hyStatus.setText("先填 SSID");
                return;
            }
            hyStatus.setText(hyTransact(TX_START_SOFTAP, ssid, pwd));
        } else {
            hyStatus.setText(hyTransact(TX_STOP_SOFTAP, null, null));
        }
    }

    /** Path B: this app starts the hotspot itself (needs the platform signature). */
    private void directHotspot() {
        StringBuilder sb = new StringBuilder();
        int granted = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS");
        boolean sec = granted == android.content.pm.PackageManager.PERMISSION_GRANTED;
        sb.append("WRITE_SECURE_SETTINGS: ").append(sec ? "已授予 ✓（平台签名生效）" : "未授予 ✗（不是平台签名）").append('\n');
        try {
            android.provider.Settings.Secure.putInt(getContentResolver(), "wifi_operate_save", 102);
            sb.append("wifi_operate_save=102 写入 ✓\n");
        } catch (Throwable t) {
            sb.append("wifi_operate_save 写入失败: ").append(t).append('\n');
        }
        String ssid = hySsidView.getText().toString().trim();
        String pwd = hyPwdView.getText().toString();
        if (ssid.isEmpty()) {
            sb.append("（先填 SSID 再试 startSoftAp）");
            showHy(sb.toString(), MISSING);
            return;
        }
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiConfiguration config = new android.net.wifi.WifiConfiguration();
            config.SSID = ssid;
            if (pwd == null || pwd.isEmpty()) {
                config.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE);
            } else {
                config.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK);
                config.preSharedKey = pwd;
            }
            Method method = wm.getClass().getMethod("startSoftAp", android.net.wifi.WifiConfiguration.class);
            Object result = method.invoke(wm, config);
            sb.append("startSoftAp -> ").append(result).append('\n');
            showHy(sb.toString(), Boolean.TRUE.equals(result) ? OK : WARN);
        } catch (Throwable t) {
            sb.append("startSoftAp 异常: ").append(t).append('\n');
            if (t.getCause() != null) {
                sb.append("  原因: ").append(t.getCause()).append('\n');
            }
            showHy(sb.toString(), MISSING);
        }
    }

    private void showHy(String text, int color) {
        runOnUiThread(() -> {
            hyStatus.setTextColor(color);
            hyStatus.setText(text);
        });
    }

    private String hyTransact(int code, String ssid, String pwd) {
        IBinder binder = hyBinder;
        if (binder == null) {
            hyStatus.setTextColor(WARN);
            return "未绑定支持库（先点\"绑定支持库\"）";
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HY_DESCRIPTOR);
            if (code == TX_START_SOFTAP) {
                data.writeString(ssid);
                data.writeString(pwd);
            }
            boolean transacted = binder.transact(code, data, reply, 0);
            reply.readException();
            boolean ok = reply.readInt() != 0;
            hyStatus.setTextColor(ok ? OK : WARN);
            return "事务 " + code + (code == TX_START_SOFTAP ? "(startSoftAp)"
                    : code == TX_STOP_SOFTAP ? "(stopSoftAp)" : "(isWifiApEnabled)")
                    + " transacted=" + transacted + " 返回=" + ok;
        } catch (Throwable t) {
            hyStatus.setTextColor(MISSING);
            return "事务 " + code + " 异常: " + t;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Pushes a fake now-playing entry to the instrument cluster through the ECARX adaptapi. */
    private void testClusterDisplay() {
        StringBuilder log = new StringBuilder();
        try {
            Class<?> dimClass = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction");
            Object dim = dimClass.getMethod("create", Context.class).invoke(null, this);
            log.append("DimInteraction.create -> ").append(dim == null ? "null" : "ok").append('\n');
            if (dim == null) {
                showCluster(log.toString(), MISSING);
                return;
            }
            try {
                dimClass.getMethod("setPresentationToDimSwitch", int.class, String.class, String.class, boolean.class)
                        .invoke(dim, 1, "", "", true);
                log.append("setPresentationToDimSwitch(1,,,true) ok\n");
            } catch (Throwable t) {
                log.append("setPresentationToDimSwitch 失败: ").append(t).append('\n');
            }
            Object media = dimClass.getMethod("getMediaInteraction").invoke(dim);
            log.append("getMediaInteraction -> ").append(media == null ? "null" : "ok").append('\n');
            if (media == null) {
                showCluster(log.toString(), MISSING);
                return;
            }
            Class<?> iMedia = Class.forName("com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction");
            iMedia.getMethod("updateCurrentSourceType", int.class).invoke(media, 6);
            log.append("updateCurrentSourceType(6) ok\n");

            Class<?> iPlayback = Class.forName(
                    "com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction$IPlaybackInfo");
            Object playback = Proxy.newProxyInstance(iPlayback.getClassLoader(),
                    new Class<?>[]{iPlayback}, new FakePlaybackInfo());
            iMedia.getMethod("updatePlaybackInfo", iPlayback).invoke(media, playback);
            log.append("updatePlaybackInfo(fake) ok\n");
            iMedia.getMethod("updateCurrentProgress", long.class).invoke(media, 42000L);
            log.append("updateCurrentProgress(42s) ok\n");
            showCluster(log + "\n→ 看仪表盘有没有「DiPlay 测试歌曲 / 测试艺人」", OK);
        } catch (Throwable t) {
            log.append("失败: ").append(t).append('\n');
            showCluster(log.toString(), MISSING);
        }
    }

    private void showCluster(String text, int color) {
        runOnUiThread(() -> {
            clusterStatus.setTextColor(color);
            clusterStatus.setText(text);
        });
    }

    /** Dynamic proxy implementing the ECARX IPlaybackInfo interface by name. */
    private static final class FakePlaybackInfo implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getTitle": return "DiPlay 测试歌曲";
                case "getArtist": return "测试艺人";
                case "getAlbum": return "测试专辑";
                case "getDuration": return 180000L;
                case "getPlaybackStatus": return 1;
                case "getSourceType": return 6;
                case "getLoopMode": return 0;
                case "getFavoriteState": return 0;
                case "getPlayingItemPositionInQueue": return 0;
                case "getRadioMode": return 0;
                case "getCurrentLyricSentence": return "";
                case "getLyricContent": return "";
                case "getRadioFrequency": return "";
                case "getRadioStationName": return "";
                case "getUUID": return "";
                case "getArtwork":
                case "getLyric":
                case "getMediaPath":
                case "getNextArtwork":
                case "getPreviousArtwork":
                    return null;
                case "toString": return "DiPlayFakePlaybackInfo";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default:
                    break;
            }
            Class<?> type = method.getReturnType();
            if (type == String.class) return "";
            if (type == Uri.class) return null;
            if (type == long.class) return 0L;
            if (type == int.class) return 0;
            if (type == boolean.class) return false;
            return null;
        }
    }

    private android.view.View findingView(Finding f) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(4));

        TextView label = new TextView(this);
        label.setTextSize(17);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(f.state);
        label.setText(f.label);
        box.addView(label);

        TextView value = new TextView(this);
        value.setTextSize(14);
        value.setTextColor(f.state);
        value.setText("   " + f.value);
        box.addView(value);
        return box;
    }

    private String shortName(String cls) {
        int dot = cls.lastIndexOf('.');
        return dot >= 0 ? cls.substring(dot + 1) : cls;
    }

    private boolean matchesBrand(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        for (String keyword : BRAND_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String tryBind(Intent intent) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] connected = {false};
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                connected[0] = true;
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        boolean bound;
        try {
            bound = bindService(intent, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            return "bind FAILED (" + t.getClass().getSimpleName() + ")";
        }
        if (!bound) {
            return "bind=false";
        }
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            unbindService(conn);
        } catch (Throwable ignored) {
            // Never bound.
        }
        return connected[0] ? "bind OK ✓" : "bind 未连接";
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
