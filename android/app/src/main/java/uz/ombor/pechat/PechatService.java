package uz.ombor.pechat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Fonda ishlaydi: pechat kanalini o'qiydi va ishni ulangan printerga yuboradi. */
public class PechatService extends Service {
    private static final int NOTIF = 1;
    private static final long ESKI_SEK = 15 * 60; // 15 daqiqadan eski ishlar chop etilmaydi

    public static volatile boolean ishlayapti = false;

    private static class Yorliq {
        final String id;
        final long chat;
        final byte[] tspl;
        Yorliq(String id, long chat, byte[] tspl) { this.id = id; this.chat = chat; this.tspl = tspl; }
    }

    private final ArrayDeque<Yorliq> navbat = new ArrayDeque<>();
    private final Set<String> bajarilgan = new HashSet<>();
    private volatile boolean toxta = false;
    private long oxirgiTekshiruv = 0;
    private long oxirgiElon = 0;
    private Boolean oxirgiHolat = null;

    /** Xprinter ulandi/uzildi — kanalga xabar (bot yorliqni shunga qarab telefonga yoki noutbukka yuboradi). */
    private void holatXabar() {
        boolean bor = UsbPrinter.yorliqPrinter(this) != null;
        if (oxirgiHolat != null && oxirgiHolat == bor) return;
        new Thread(this::elon).start();
    }

    /** Kanalga «shu printer ishlayapti» deb yozadi — boshqa telefonlar ro'yxatiga o'zi qo'shiladi. */
    private void elon() {
        long k = Printerlar.kanal(this);
        if (k == 0 || tg == null) return;
        oxirgiElon = System.currentTimeMillis();
        boolean bor = UsbPrinter.yorliqPrinter(this) != null;
        oxirgiHolat = bor;
        tg.yoz(k, "#printer " + Printerlar.ozim(this) + (bor ? " ulandi — 📱 Xprinter telefonga ulangan"
                : " uzildi — 📱 Xprinter telefonga ulanmagan (yorliqlar noutbukka ketadi)"));
    }
    private Thread oqim;
    private Tg tg;
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wifi;

    private final BroadcastReceiver usb = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a) || UsbPrinter.RUXSAT.equals(a)) {
                Jurnal.yoz("USB: printer ulandi");
                new Thread(PechatService.this::navbatniChopEt).start();
                holatXabar();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                Jurnal.yoz("USB: printer uzildi");
                holatXabar();
            }
            yangila();
        }
    };

    public static void boshla(Context c) {
        Intent i = new Intent(c, PechatService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void toxtat(Context c) {
        c.stopService(new Intent(c, PechatService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("pechat", "Pechat", NotificationManager.IMPORTANCE_LOW));
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF, bildirish("Ishga tushmoqda…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF, bildirish("Ishga tushmoqda…"));
        }
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbPrinter.RUXSAT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usb, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usb, f);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ombor:pechat");
        wl.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ombor:pechat");
            wifi.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (oqim == null || !oqim.isAlive()) {
            toxta = false;
            oqim = new Thread(this::ishla, "pechat");
            oqim.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        toxta = true;
        ishlayapti = false;
        Jurnal.holat = "To'xtatilgan";
        if (oqim != null) oqim.interrupt();
        try { unregisterReceiver(usb); } catch (Exception ignored) {}
        if (wl != null && wl.isHeld()) wl.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        Jurnal.yoz("Xizmat to'xtatildi");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    private Notification bildirish(String matn) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "pechat") : new Notification.Builder(this);
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        return b.setContentTitle("Ombor Pechat").setContentText(matn).setSmallIcon(R.drawable.ic_printer)
                .setContentIntent(pi).setOngoing(true).build();
    }

    private void yangila() {
        String x = UsbPrinter.yorliqPrinter(this) != null ? "Xprinter ✓" : "Xprinter yo'q";
        String c = UsbPrinter.canon(this) != null ? "Canon USB ✓" : (Jurnal.canonIp(this).isEmpty() ? "Canon yo'q" : "Canon Wi-Fi");
        int n;
        synchronized (navbat) { n = navbat.size(); }
        String m = Jurnal.holat + " · " + x + " · " + c + (n > 0 ? " · navbatda " + n : "");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF, bildirish(m));
    }

    private void ishla() {
        String token = Jurnal.token(this);
        if (token.isEmpty()) {
            Jurnal.holat = "Token kiritilmagan";
            Jurnal.yoz("Pechat bot tokenini kiriting");
            yangila();
            stopSelf();
            return;
        }
        tg = new Tg(token);
        ishlayapti = true;
        long offset = Jurnal.sozlama(this).getLong("offset", -1);
        int kut = 1;
        boolean ulangan = false;
        while (!toxta) {
            try {
                if (!ulangan) {
                    JSONObject me = (JSONObject) tg.call("getMe", new JSONObject(), 20000);
                    Jurnal.holat = "Ishlayapti (@" + me.optString("username") + ")";
                    Jurnal.yoz("Telegram'ga ulandi: @" + me.optString("username"));
                    ulangan = true;
                    elon();
                    yangila();
                }
                JSONObject p = new JSONObject().put("timeout", 25).put("allowed_updates", new JSONArray().put("channel_post").put("my_chat_member"));
                if (offset >= 0) p.put("offset", offset);
                JSONArray up = (JSONArray) tg.call("getUpdates", p, 40000);
                kut = 1;
                for (int i = 0; i < up.length(); i++) {
                    JSONObject u = up.getJSONObject(i);
                    offset = u.getLong("update_id") + 1;
                    Jurnal.sozlama(this).edit().putLong("offset", offset).apply();
                    JSONObject mcm = u.optJSONObject("my_chat_member");
                    if (mcm != null && mcm.optJSONObject("chat") != null && "channel".equals(mcm.getJSONObject("chat").optString("type"))) {
                        Printerlar.kanalQoy(this, mcm.getJSONObject("chat").getLong("id"));
                        Jurnal.yoz("📡 Pechat kanali aniqlandi: " + mcm.getJSONObject("chat").optString("title"));
                        elon();
                    }
                    JSONObject post = u.optJSONObject("channel_post");
                    if (post != null) {
                        try { ishlov(post); } catch (Exception e) { Jurnal.yoz("Xato: " + e.getMessage()); }
                    }
                }
                navbatniChopEt();
                if (System.currentTimeMillis() - oxirgiElon > 12 * 3600 * 1000L) elon();
                if (System.currentTimeMillis() - oxirgiTekshiruv > 6 * 3600 * 1000L) {
                    oxirgiTekshiruv = System.currentTimeMillis();
                    Yangilash.fondaTekshir(this);
                }
            } catch (Tg.Ruxsatsiz e) {
                Jurnal.holat = "Token noto'g'ri";
                Jurnal.yoz(e.getMessage());
                yangila();
                uxla(60000);
            } catch (Tg.Konflikt e) {
                Jurnal.holat = "Noutbuk agenti ham ishlayapti";
                Jurnal.yoz(e.getMessage());
                yangila();
                uxla(15000);
            } catch (Exception e) {
                if (toxta) break;
                Jurnal.holat = "Internet yo'q, qayta urinilmoqda";
                Jurnal.yoz("Telegram bilan aloqa yo'q: " + e.getMessage());
                ulangan = false;
                yangila();
                uxla(kut * 1000L);
                kut = Math.min(kut * 2, 60);
            }
        }
        ishlayapti = false;
    }

    private void uxla(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void ishlov(JSONObject post) throws Exception {
        long chat = post.getJSONObject("chat").getLong("id");
        long sana = post.optLong("date", 0);
        boolean eski = sana > 0 && System.currentTimeMillis() / 1000 - sana > ESKI_SEK;
        String cap = post.optString("caption", "");
        JSONObject doc = post.optJSONObject("document");
        if (doc != null && cap.startsWith("#hujjat ")) {
            String[] hp = cap.trim().split("\\s+");
            if (hp.length < 3 || !hp[1].equals(Jurnal.a4Printer(this)) || bajarilgan.contains(hp[2])) return;
            if (eski) { Jurnal.yoz("Eski hujjat #" + hp[2] + " o'tkazib yuborildi"); return; }
            byte[] raw = tg.yukla(doc.getString("file_id"));
            try { raw = Ipp.oqi(new GZIPInputStream(new ByteArrayInputStream(raw))); } catch (Exception ignored) {}
            JSONObject job = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            int nusxa = Math.max(1, job.optInt("nusxa", 1));
            try {
                String nat = Chop.a4(this, job, nusxa);
                bajarilgan.add(hp[2]);
                Jurnal.yoz("✅ A4 #" + hp[2] + " chop etildi (" + nat + ")");
                tg.yoz(chat, "✅ #" + hp[2] + " chop etildi (" + nat + ", telefon)");
            } catch (Exception e) {
                Jurnal.yoz("⚠️ A4 #" + hp[2] + ": " + e.getMessage());
                tg.yoz(chat, "⚠️ #" + hp[2] + " chop etilmadi (Canon): " + e.getMessage()
                        + ". Telefonda «Oxirgi A4 ni qo'lda chop etish» tugmasi bor.");
            }
            return;
        }
        String text = post.optString("text", "");
        Printerlar.kanalQoy(this, chat);
        if (text.startsWith("#printer ")) {                 // boshqa telefon printeri e'lon qildi
            String[] e = text.trim().split("\\s+");
            if (e.length >= 2) Printerlar.qosh(this, e[1]);
            return;
        }
        if (text.startsWith("✅ #") && text.contains(" chop etildi (")) {   // boshqa printer javobi
            int a = text.indexOf(" chop etildi (") + 14, b = text.indexOf(',', a);
            if (b < 0) b = text.indexOf(')', a);
            if (b > a) Printerlar.qosh(this, text.substring(a, b));
            if (text.startsWith("✅ #T")) Jurnal.yoz(text);
            return;
        }
        boolean fayl = doc != null && cap.startsWith("#pechat ");
        String bosh;
        if (fayl) bosh = cap.trim();
        else if (text.startsWith("#pechat ") && text.indexOf('\n') > 0) bosh = text.substring(0, text.indexOf('\n')).trim();
        else return;
        String[] parts = bosh.split("\\s+");
        if (parts.length < 3) return;
        Printerlar.qosh(this, parts[1]);
        String yp = Jurnal.yorliqPrinter(this);
        if (!yp.isEmpty() && !yp.equals("*") && !parts[1].equals(yp)) return;
        String jid = parts[2];
        if (bajarilgan.contains(jid)) return;
        if (eski) { Jurnal.yoz("Eski yorliq #" + jid + " o'tkazib yuborildi"); return; }
        byte[] tspl = fayl ? tg.yukla(doc.getString("file_id"))
                : Base64.decode(text.substring(text.indexOf('\n') + 1).trim(), Base64.DEFAULT);
        Printerlar.botniOrgan(this, tspl);
        synchronized (navbat) { navbat.add(new Yorliq(jid, chat, tspl)); }
        navbatniChopEt();
    }

    private final Object chopQulf = new Object();

    void navbatniChopEt() {
        synchronized (chopQulf) {
            while (true) {
                Yorliq y;
                synchronized (navbat) { y = navbat.peek(); }
                if (y == null) break;
                if (UsbPrinter.yorliqPrinter(this) == null) {
                    if (!bajarilgan.contains("kut:" + y.id)) {
                        bajarilgan.add("kut:" + y.id);
                        Jurnal.yoz("⏳ #" + y.id + ": Xprinter ulanmagan — ulasangiz chiqadi");
                        if (tg != null) tg.yoz(y.chat, "⏳ #" + y.id + ": Xprinter telefonga ulanmagan — ulasangiz chiqadi");
                    }
                    break;
                }
                try {
                    String nom = Chop.yorliq(this, y.tspl);
                    synchronized (navbat) { navbat.poll(); }
                    bajarilgan.add(y.id);
                    Jurnal.yoz("✅ Yorliq #" + y.id + " chop etildi (" + nom + ")");
                    if (tg != null) tg.yoz(y.chat, "✅ #" + y.id + " chop etildi (" + Jurnal.yorliqPrinter(this) + ", telefon)");
                } catch (Exception e) {
                    Jurnal.yoz("⚠️ Yorliq #" + y.id + ": " + e.getMessage());
                    break; // ruxsat yoki qog'oz — keyingi safar qayta uriniladi
                }
            }
        }
        yangila();
    }
}
