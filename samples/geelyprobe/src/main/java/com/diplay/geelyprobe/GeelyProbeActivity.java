package com.diplay.geelyprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lists Geely/Lynk packages and services on the head unit, checks vendor actions and explicit
 * components, and tries to bind the wheel-key, media-center and online-music services.
 */
public class GeelyProbeActivity extends Activity {

    private static final String[] BRAND_KEYWORDS = {
            "geely", "lynk", "desaysv", "ecarx", "oneos", "hicar", "galaxy",
    };

    // Actions the car system uses (or is expected to use).
    private static final String[] PROBE_ACTIONS = {
            "com.geely.service.ONLINE_MUSIC",
            "com.geely.service.MEDIA_CENTER",
            "com.geely.service.MEDIA",
            "com.geely.service.INPUT",
            "com.geely.service.CLUSTER",
            "com.geely.service.HUD",
            "geely.intent.action.MEDIA_CENTER",
    };

    // Explicit service components worth binding.
    private static final String[][] PROBE_COMPONENTS = {
            {"com.geely.inputservice", "com.geely.inputservice.InputService"},
            {"com.geely.online.service", "com.geely.online.service.OnlineMusicService"},
            {"com.geely.mediacenter", "com.geely.mediacenter.MediaCenterService"},
            {"com.geely.mediacenter.service", "com.geely.mediacenter.service.MediaCenterService"},
    };

    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Geely Probe\n扫描车机上的 Geely/Lynk 服务");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(title);

        TextView info = new TextView(this);
        info.setTextSize(13);
        info.setPadding(0, dp(8), 0, dp(8));
        info.setText(String.format(Locale.US,
                "Android %s (API %d)\nBRAND=%s\nPRODUCT=%s\nDEVICE=%s\nMODEL=%s\nMANUFACTURER=%s\nHARDWARE=%s",
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                Build.BRAND, Build.PRODUCT, Build.DEVICE, Build.MODEL, Build.MANUFACTURER, Build.HARDWARE));
        list.addView(info);

        Button scan = new Button(this);
        scan.setText("开始扫描");
        scan.setAllCaps(false);
        scan.setOnClickListener(v -> runScan());
        list.addView(scan, matchWrap());

        Button copy = new Button(this);
        copy.setText("复制结果到剪贴板");
        copy.setAllCaps(false);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("geely-probe", output.getText()));
            }
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        list.addView(copy, matchWrap());

        output = new TextView(this);
        output.setTextSize(11);
        output.setTypeface(Typeface.MONOSPACE);
        output.setPadding(0, dp(8), 0, dp(8));
        list.addView(output);

        scroll.addView(list);
        setContentView(scroll);
    }

    private void runScan() {
        output.setTextColor(Color.DKGRAY);
        output.setText("扫描中…");
        new Thread(this::scanBlocking, "geely-probe").start();
    }

    private void scanBlocking() {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = getPackageManager();

        // ---- 1. Geely/Lynk packages and their services ----
        sb.append("===== Geely/Lynk 相关包与服务 =====\n");
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(PackageManager.GET_SERVICES);
        } catch (Throwable t) {
            all = new ArrayList<>();
            sb.append("getInstalledPackages failed: ").append(t).append('\n');
        }
        List<String> matched = new ArrayList<>();
        for (PackageInfo pi : all) {
            if (matchesBrand(pi.packageName)) {
                matched.add(pi.packageName);
            }
        }
        Collections.sort(matched);
        if (matched.isEmpty()) {
            sb.append("(没有匹配 brand 关键字的包)\n");
        }
        for (String pkg : matched) {
            sb.append("PKG ").append(pkg).append('\n');
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES);
                if (pi.services != null) {
                    for (ServiceInfo si : pi.services) {
                        sb.append("   svc ").append(si.name)
                                .append(si.exported ? "  [exported]" : "  [private]")
                                .append(!si.enabled ? "  [disabled]" : "")
                                .append('\n');
                    }
                }
                if (pi.activities != null) {
                    for (android.content.pm.ActivityInfo ai : pi.activities) {
                        if (matchesBrand(ai.name)) {
                            sb.append("   act ").append(ai.name)
                                    .append(ai.exported ? "  [exported]" : "  [private]").append('\n');
                        }
                    }
                }
            } catch (Throwable t) {
                sb.append("   (info failed: ").append(t.getClass().getSimpleName()).append(")\n");
            }
        }

        // ---- 2. Query known actions ----
        sb.append("\n===== 按 action 查询服务 =====\n");
        for (String action : PROBE_ACTIONS) {
            List<ResolveInfo> rs = pm.queryIntentServices(new Intent(action), 0);
            sb.append("action ").append(action).append(" -> ").append(rs.size()).append('\n');
            for (ResolveInfo r : rs) {
                if (r.serviceInfo != null) {
                    sb.append("    ").append(r.serviceInfo.packageName).append('/')
                            .append(r.serviceInfo.name)
                            .append(r.serviceInfo.exported ? "  [exported]" : "  [private]").append('\n');
                }
            }
        }

        // ---- 3. Explicit components + bind test ----
        sb.append("\n===== 显式服务组件 & bind 测试 =====\n");
        for (String[] pair : PROBE_COMPONENTS) {
            String pkg = pair[0];
            String cls = pair[1];
            ComponentName cn = new ComponentName(pkg, cls);
            try {
                ServiceInfo si = pm.getServiceInfo(cn, 0);
                sb.append("found ").append(pkg).append('/').append(cls)
                        .append(si.exported ? "  [exported]" : "  [private]").append('\n');
            } catch (Throwable t) {
                sb.append("NOT FOUND ").append(pkg).append('/').append(cls).append('\n');
                continue;
            }
            sb.append("   ").append(tryBind(new Intent().setComponent(cn))).append('\n');
        }
        for (String action : PROBE_ACTIONS) {
            List<ResolveInfo> rs = pm.queryIntentServices(new Intent(action), 0);
            if (rs.isEmpty()) {
                continue;
            }
            sb.append("bind action ").append(action).append(": ")
                    .append(tryBind(new Intent(action))).append('\n');
        }

        String text = sb.toString();
        runOnUiThread(() -> {
            output.setTextColor(Color.parseColor("#111111"));
            output.setText(text);
        });
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
            return "bind FAILED (" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage()) + ")";
        }
        if (!bound) {
            return "bind returned false";
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
        return connected[0] ? "bind OK (connected)" : "bind requested but not connected within 2s";
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
