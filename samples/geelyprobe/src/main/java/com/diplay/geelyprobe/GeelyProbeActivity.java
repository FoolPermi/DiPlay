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
