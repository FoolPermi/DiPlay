package com.diplay.settingsprobe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Tries to open every settings screen a stripped head-unit firmware may still keep. */
public class SettingsProbeActivity extends Activity {

    private final List<Row> rows = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private TextView usbList;
    private TextView usbLog;
    private BroadcastReceiver usbReceiver;
    private BroadcastReceiver usbPermissionReceiver;
    private UsbDevice pendingUsbDevice;
    private Runnable pendingUsbAction;
    private TextView cableStatus;
    private volatile boolean cableTestRunning;
    private final AtomicInteger cableSuccess = new AtomicInteger();
    private final AtomicInteger cableFail = new AtomicInteger();
    private final AtomicInteger cableReenum = new AtomicInteger();

    private static final class Row {
        final String label;
        final Intent intent;
        TextView status;

        Row(String label, Intent intent) {
            this.label = label;
            this.intent = intent;
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
        title.setText("Settings Probe\n点每一项，尝试打开对应设置页");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        list.addView(title);

        TextView hint = new TextView(this);
        hint.setText("✓ = 可打开 · ✗ = 无应用可处理 / 启动失败");
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(0, dp(4), 0, dp(4));
        list.addView(hint);

        // ---- 设备信息（自动显示，无需点击）----
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackgroundColor(Color.parseColor("#F0F4F8"));
        info.setPadding(dp(12), dp(10), dp(12), dp(10));
        addInfoLine(info, "Android 版本", Build.VERSION.RELEASE);
        addInfoLine(info, "API 级别", String.valueOf(Build.VERSION.SDK_INT));
        addInfoLine(info, "厂商 / 型号", Build.MANUFACTURER + " " + Build.MODEL);
        addInfoLine(info, "构建 ID", Build.DISPLAY);
        addInfoLine(info, "硬件平台", Build.HARDWARE);
        list.addView(info);

        // ---- USB 设备检测 ----
        TextView usbTitle = new TextView(this);
        usbTitle.setText("USB 设备检测");
        usbTitle.setTextSize(16);
        usbTitle.setTypeface(Typeface.DEFAULT_BOLD);
        usbTitle.setPadding(0, dp(14), 0, dp(4));
        list.addView(usbTitle);

        TextView usbHint = new TextView(this);
        usbHint.setText("插上 iPhone 后点\"刷新\"。若出现 05AC（苹果）设备，说明 USB 数据到了 App；否则车机没把 USB 暴露给第三方 App。");
        usbHint.setTextSize(12);
        usbHint.setPadding(0, 0, 0, dp(4));
        list.addView(usbHint);

        Button refreshUsb = new Button(this);
        refreshUsb.setText("刷新 USB 设备列表");
        refreshUsb.setAllCaps(false);
        refreshUsb.setOnClickListener(v -> refreshUsb());
        list.addView(refreshUsb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button openUsb = new Button(this);
        openUsb.setText("尝试打开并读取 iPhone USB");
        openUsb.setAllCaps(false);
        openUsb.setOnClickListener(v -> tryOpenAndReadUsb());
        list.addView(openUsb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        usbList = new TextView(this);
        usbList.setTextSize(13);
        usbList.setPadding(0, dp(6), 0, dp(6));
        list.addView(usbList);

        usbLog = new TextView(this);
        usbLog.setTextSize(12);
        usbLog.setPadding(0, dp(2), 0, dp(6));
        list.addView(usbLog);

        // ---- USB 线材检测 ----
        TextView cableTitle = new TextView(this);
        cableTitle.setText("USB 线材检测");
        cableTitle.setTextSize(16);
        cableTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cableTitle.setPadding(0, dp(14), 0, dp(4));
        list.addView(cableTitle);

        TextView cableHint = new TextView(this);
        cableHint.setText("插好 iPhone 后点下面按钮，测 20 秒，期间不要动线。\n"
                + "判定：0 失败 0 重连 = 线好；有失败/重连 = 线或接口不稳。");
        cableHint.setTextSize(12);
        cableHint.setPadding(0, 0, 0, dp(4));
        list.addView(cableHint);

        Button cableBtn = new Button(this);
        cableBtn.setText("开始 20 秒线材稳定性测试");
        cableBtn.setAllCaps(false);
        cableBtn.setOnClickListener(v -> startCableTest());
        list.addView(cableBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        cableStatus = new TextView(this);
        cableStatus.setTextSize(13);
        cableStatus.setPadding(0, dp(6), 0, dp(6));
        list.addView(cableStatus);

        Button copy = new Button(this);
        copy.setText("复制全部结果到剪贴板");
        copy.setAllCaps(false);
        copy.setOnClickListener(v -> copyResults());
        list.addView(copy, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- 通用设置 ----
        add(list, "设置主页 Settings", new Intent(Settings.ACTION_SETTINGS));
        add(list, "无线和网络 Wireless", new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        add(list, "Wi-Fi", new Intent(Settings.ACTION_WIFI_SETTINGS));
        add(list, "蓝牙 Bluetooth", new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        add(list, "位置 Location", new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        add(list, "应用列表 Apps", new Intent(Settings.ACTION_APPLICATION_SETTINGS));

        // ---- 核心目标 ----
        add(list, "无障碍 Accessibility", new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        add(list, "开发者选项 Developer options", new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        add(list, "关于本机 About device", new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        add(list, "应用详情 DiPlay", new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:com.shihab.diplay")));
        add(list, "网络共享/热点 Tethering(action)", new Intent("android.settings.TETHER_SETTINGS"));

        // ---- AOSP 显式组件（绕过被阉割的设置 UI）----
        add(list, "AOSP 无障碍页", component("com.android.settings",
                "com.android.settings.Settings$AccessibilitySettingsActivity"));
        add(list, "AOSP 开发者选项页", component("com.android.settings",
                "com.android.settings.Settings$DevelopmentSettingsActivity"));
        add(list, "AOSP 关于页", component("com.android.settings",
                "com.android.settings.Settings$DeviceInfoSettingsActivity"));
        add(list, "AOSP 热点页 TetherSettings", component("com.android.settings",
                "com.android.settings.TetherSettings"));

        // ---- 其它可能存在的 OEM 组件（仅探测）----
        add(list, "BYD 无障碍页(仅探测)", component("com.byd.systemsettings",
                "com.byd.systemsettings.accessibility.AccessibilityMainActivity"));

        scroll.addView(list);
        setContentView(scroll);
    }

    private void addInfoLine(LinearLayout parent, String label, String value) {
        TextView t = new TextView(this);
        t.setText(label + ": " + value);
        t.setTextSize(14);
        t.setPadding(0, dp(2), 0, dp(2));
        parent.addView(t);
    }

    private void add(LinearLayout list, String label, Intent intent) {
        Row row = new Row(label, intent);
        rows.add(row);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(6));

        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> probe(row));
        box.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(this);
        status.setTextSize(12);
        status.setPadding(dp(4), dp(2), 0, 0);
        row.status = status;
        box.addView(status);

        list.addView(box);
    }

    private void probe(Row row) {
        Intent intent = row.intent;
        boolean resolved;
        try {
            resolved = intent.resolveActivity(getPackageManager()) != null;
        } catch (Throwable t) {
            setResult(row, "✗ resolve 异常: " + t.getClass().getSimpleName(), Color.GRAY);
            return;
        }
        if (!resolved) {
            setResult(row, "✗ 无应用可处理此 Intent", Color.GRAY);
            return;
        }
        try {
            startActivity(intent);
            setResult(row, "✓ 已尝试打开", Color.parseColor("#2E7D32"));
        } catch (Throwable t) {
            setResult(row, "✗ 启动失败: " + t.getClass().getSimpleName(), Color.parseColor("#C62828"));
        }
    }

    private void setResult(Row row, String text, int color) {
        row.status.setText(text);
        row.status.setTextColor(color);
    }

    private void copyResults() {
        StringBuilder sb = new StringBuilder();
        for (Row row : rows) {
            CharSequence status = row.status.getText();
            sb.append(row.label).append("  =>  ")
                    .append(status == null ? "未尝试" : status).append('\n');
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("settings-probe", sb.toString()));
        }
        Toast.makeText(this, "结果已复制", Toast.LENGTH_SHORT).show();
    }

    private void refreshUsb() {
        UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
        if (usb == null) {
            usbList.setText("UsbManager 不可用");
            return;
        }
        Map<String, UsbDevice> devices = usb.getDeviceList();
        if (devices.isEmpty()) {
            usbList.setText("当前无 USB 设备\n（若 iPhone 已插上，说明车机没有把 USB 设备暴露给 App）");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : devices.values()) {
            boolean apple = d.getVendorId() == 0x05AC;
            sb.append(String.format(java.util.Locale.US, "%04X:%04X  %s%s\n",
                    d.getVendorId(), d.getProductId(),
                    d.getDeviceName() == null ? "(无名称)" : d.getDeviceName(),
                    apple ? "  ← 苹果设备 (05AC)" : ""));
        }
        usbList.setText(sb.toString().trim());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUsb();
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                String label = UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) ? "插入" : "拔出";
                String info = d == null ? "未知设备" :
                        String.format(java.util.Locale.US, "%04X:%04X %s",
                                d.getVendorId(), d.getProductId(),
                                d.getDeviceName() == null ? "" : d.getDeviceName());
                usbLog.append(label + ": " + info + "\n");
                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    if (cableTestRunning) cableReenum.incrementAndGet();
                    refreshUsb();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        usbPermissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice device = pendingUsbDevice;
                pendingUsbDevice = null;
                if (!granted) {
                    usbLog.append("USB 权限被拒绝\n");
                    return;
                }
                UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
                Runnable action = pendingUsbAction;
                pendingUsbAction = null;
                if (device != null && usb != null) {
                    if (action != null) action.run();
                    else openAndRead(usb, device);
                }
            }
        };
        IntentFilter permFilter = new IntentFilter("com.diplay.settingsprobe.USB_PERMISSION");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, permFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, permFilter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (usbReceiver != null) {
            unregisterReceiver(usbReceiver);
            usbReceiver = null;
        }
        if (usbPermissionReceiver != null) {
            unregisterReceiver(usbPermissionReceiver);
            usbPermissionReceiver = null;
        }
    }

    private void tryOpenAndReadUsb() {
        UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
        if (usb == null) {
            usbLog.append("UsbManager 不可用\n");
            return;
        }
        UsbDevice apple = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == 0x05AC) {
                apple = d;
                break;
            }
        }
        if (apple == null) {
            usbLog.append("未找到苹果 USB 设备，先插上 iPhone 再点刷新\n");
            return;
        }
        final UsbDevice target = apple;
        if (!usb.hasPermission(target)) {
            pendingUsbDevice = target;
            pendingUsbAction = () -> openAndRead(usb, target);
            usbLog.append("请求 USB 权限（请在弹窗点允许）…\n");
            Intent permIntent = new Intent("com.diplay.settingsprobe.USB_PERMISSION");
            PendingIntent pending = PendingIntent.getBroadcast(this, 0, permIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            usb.requestPermission(target, pending);
            return;
        }
        openAndRead(usb, target);
    }

    private void openAndRead(UsbManager usb, UsbDevice device) {
        UsbDeviceConnection conn = usb.openDevice(device);
        if (conn == null) {
            usbLog.append("openDevice 失败（设备被占用或无权限）\n");
            return;
        }
        try {
            usbLog.append("已打开: " + device.getDeviceName() + "\n");
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                usbLog.append(String.format(java.util.Locale.US,
                        "接口%d: class=%d sub=%d proto=%d, endpoints=%d\n",
                        i, iface.getInterfaceClass(), iface.getInterfaceSubclass(),
                        iface.getInterfaceProtocol(), iface.getEndpointCount()));
            }
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                if (!conn.claimInterface(iface, true)) {
                    usbLog.append("认领接口 " + i + " 失败（可能被车机自身占用）\n");
                    continue;
                }
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                            && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        byte[] buf = new byte[Math.max(ep.getMaxPacketSize(), 512)];
                        int read = conn.bulkTransfer(ep, buf, buf.length, 500);
                        if (read >= 0) {
                            usbLog.append(String.format("接口%d bulk IN 读到 %d 字节:\n%s\n",
                                    i, read, hex(buf, read)));
                        } else {
                            usbLog.append(String.format("接口%d bulk IN 读取返回 %d（-1=超时）\n", i, read));
                        }
                    }
                }
            }
        } finally {
            conn.close();
        }
    }

    private String hex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < data.length; i++) {
            sb.append(String.format(java.util.Locale.US, "%02X ", data[i] & 0xFF));
            if ((i + 1) % 16 == 0) sb.append('\n');
        }
        return sb.toString().trim();
    }

    private void setCable(String text, int color) {
        cableStatus.setText(text);
        cableStatus.setTextColor(color);
    }

    private void startCableTest() {
        if (cableTestRunning) {
            usbLog.append("线材检测已在运行\n");
            return;
        }
        UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
        if (usb == null) {
            setCable("UsbManager 不可用", Color.GRAY);
            return;
        }
        UsbDevice apple = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == 0x05AC) {
                apple = d;
                break;
            }
        }
        if (apple == null) {
            setCable("✗ 未检测到 iPhone\n线可能是「充电线」或已损坏（压根不传数据）",
                    Color.parseColor("#C62828"));
            return;
        }
        final UsbDevice target = apple;
        if (!usb.hasPermission(target)) {
            pendingUsbDevice = target;
            pendingUsbAction = () -> runCableTest(target);
            setCable("请求 USB 权限…（请在弹窗点允许）", Color.parseColor("#1565C0"));
            Intent permIntent = new Intent("com.diplay.settingsprobe.USB_PERMISSION");
            PendingIntent pending = PendingIntent.getBroadcast(this, 0, permIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            usb.requestPermission(target, pending);
            return;
        }
        runCableTest(target);
    }

    private void runCableTest(UsbDevice device) {
        cableTestRunning = true;
        cableSuccess.set(0);
        cableFail.set(0);
        cableReenum.set(0);
        setCable("测试中…（20 秒，别动线）", Color.parseColor("#1565C0"));
        new Thread(() -> {
            UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
            if (usb == null) {
                cableTestRunning = false;
                runOnUiThread(() -> setCable("UsbManager 不可用", Color.GRAY));
                return;
            }
            UsbDeviceConnection conn = usb.openDevice(device);
            if (conn == null) {
                cableTestRunning = false;
                runOnUiThread(() -> setCable("✗ 无法打开设备（被占用/权限问题）",
                        Color.parseColor("#C62828")));
                return;
            }
            int bulkMax = 0;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        bulkMax = Math.max(bulkMax, ep.getMaxPacketSize());
                    }
                }
            }
            final int speedMax = bulkMax;
            byte[] buf = new byte[255];
            long end = System.currentTimeMillis() + 20000L;
            long lastUi = 0L;
            while (System.currentTimeMillis() < end && cableTestRunning) {
                try {
                    int r = conn.controlTransfer(0x80, 0x06, 0x0100, 0, buf, 18, 1000);
                    if (r >= 18) cableSuccess.incrementAndGet();
                    else cableFail.incrementAndGet();
                    int r2 = conn.controlTransfer(0x80, 0x06, 0x0200, 0, buf, buf.length, 1000);
                    if (r2 >= 1) cableSuccess.incrementAndGet();
                    else cableFail.incrementAndGet();
                } catch (Throwable t) {
                    cableFail.incrementAndGet();
                }
                long now = System.currentTimeMillis();
                if (now - lastUi > 500) {
                    lastUi = now;
                    runOnUiThread(() -> setCable(
                            String.format(java.util.Locale.US,
                                    "测试中… 成功 %d / 失败 %d / 重连 %d",
                                    cableSuccess.get(), cableFail.get(), cableReenum.get()),
                            Color.parseColor("#1565C0")));
                }
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    break;
                }
            }
            conn.close();
            runOnUiThread(() -> finishCableTest(speedMax));
        }, "usb-cable-test").start();
    }

    private void finishCableTest(int bulkMax) {
        cableTestRunning = false;
        int ok = cableSuccess.get();
        int fail = cableFail.get();
        int re = cableReenum.get();
        String speed = bulkMax >= 512 ? "高速(512)"
                : (bulkMax > 0 ? "全速(" + bulkMax + ")" : "未知");
        String verdict;
        int color;
        if (fail == 0 && re == 0) {
            verdict = "✅ 线材良好：数据稳定\n成功 " + ok + " 次 / 0 失败 / 0 重连 · USB " + speed;
            color = Color.parseColor("#2E7D32");
        } else if (re > 0) {
            verdict = "❌ 线材/接口不稳定：重连 " + re + " 次，失败 " + fail + " 次\n建议换线/换口再测 · USB " + speed;
            color = Color.parseColor("#C62828");
        } else {
            verdict = "⚠️ 有失败：失败 " + fail + " 次 / 成功 " + ok + " 次\n建议换线再测 · USB " + speed;
            color = Color.parseColor("#EF6C00");
        }
        setCable(verdict, color);
        usbLog.append("线材检测：成功=" + ok + " 失败=" + fail + " 重连=" + re + " speed=" + speed + "\n");
    }

    private Intent component(String pkg, String cls) {
        return new Intent().setComponent(new ComponentName(pkg, cls));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
