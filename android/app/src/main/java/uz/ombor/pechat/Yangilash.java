package uz.ombor.pechat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Ilovaning o'zi yangilanishi: GitHub'dagi eng oxirgi versiyani tekshiradi, yuklab oladi va o'rnatadi.
 * version.json: {"versionCode":3,"versionName":"1.2","apk":"https://…/ombor-pechat.apk","izoh":"…"}
 */
public final class Yangilash {
    public static final String MANBA = "https://github.com/asissch/ombor-skan/releases/latest/download/version.json";
    private static final String NATIJA = "uz.ombor.pechat.YANGILASH_NATIJA";
    private static volatile boolean ketyapti = false;

    private Yangilash() {}

    public static int joriyKod(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String joriyNom(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static HttpURLConnection ochish(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "ombor-pechat");
        // github.com -> objects.githubusercontent.com (https -> https) qayta yo'naltirish
        for (int i = 0; i < 5; i++) {
            int code = c.getResponseCode();
            if (code / 100 != 3) break;
            String loc = c.getHeaderField("Location");
            c.disconnect();
            c = (HttpURLConnection) new URL(new URL(url), loc).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "ombor-pechat");
        }
        return c;
    }

    /** Yangi versiya bo'lsa uning ma'lumotini, bo'lmasa null qaytaradi. Fon oqimida chaqiring. */
    public static JSONObject tekshir(Context c) throws Exception {
        HttpURLConnection h = ochish(MANBA + "?t=" + System.currentTimeMillis());
        if (h.getResponseCode() != 200) throw new Exception("server javobi " + h.getResponseCode());
        JSONObject j = new JSONObject(new String(Ipp.oqi(h.getInputStream()), StandardCharsets.UTF_8));
        h.disconnect();
        return j.optInt("versionCode", 0) > joriyKod(c) ? j : null;
    }

    /** Fonda (xizmatdan) tekshirib, yangi versiya bo'lsa bildirishnoma chiqaradi. */
    public static void fondaTekshir(Context c) {
        try {
            JSONObject j = tekshir(c);
            if (j == null) return;
            Jurnal.yoz("⬆️ Yangi versiya bor: " + j.optString("versionName"));
            Intent i = new Intent(c, MainActivity.class).putExtra("yangilash", true);
            PendingIntent pi = PendingIntent.getActivity(c, 7, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "pechat") : new Notification.Builder(c);
            Notification n = b.setContentTitle("Ombor Pechat: yangi versiya " + j.optString("versionName"))
                    .setContentText("Yangilash uchun bosing").setSmallIcon(R.drawable.ic_printer)
                    .setContentIntent(pi).setAutoCancel(true).build();
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(2, n);
        } catch (Exception e) {
            Jurnal.yoz("Yangilanishni tekshirib bo'lmadi: " + e.getMessage());
        }
    }

    /** Ekranda: tekshiradi va yangi versiya bo'lsa «Yangilash» oynasini ko'rsatadi. */
    public static void soradi(Activity a, boolean jim) {
        new Thread(() -> {
            try {
                JSONObject j = tekshir(a);
                a.runOnUiThread(() -> {
                    if (a.isFinishing()) return;
                    if (j == null) {
                        if (!jim) new AlertDialog.Builder(a).setMessage("Sizda eng oxirgi versiya: " + joriyNom(a) + " ✓")
                                .setPositiveButton("OK", null).show();
                        return;
                    }
                    String izoh = j.optString("izoh", "");
                    new AlertDialog.Builder(a)
                            .setTitle("⬆️ Yangi versiya: " + j.optString("versionName"))
                            .setMessage("Hozirgi: " + joriyNom(a) + (izoh.isEmpty() ? "" : "\n\nNima yangi:\n" + izoh))
                            .setPositiveButton("YANGILASH", (d, w) -> ornat(a, j.optString("apk")))
                            .setNegativeButton("Keyinroq", null)
                            .show();
                });
            } catch (Exception e) {
                Jurnal.yoz("Yangilanishni tekshirib bo'lmadi: " + e.getMessage());
                if (!jim) a.runOnUiThread(() -> new AlertDialog.Builder(a)
                        .setMessage("Tekshirib bo'lmadi. Internetni tekshiring.").setPositiveButton("OK", null).show());
            }
        }).start();
    }

    /** APK ni yuklab, telefonning o'rnatish oynasini ochadi. */
    public static void ornat(Activity a, String url) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setMessage("Bir marta ruxsat kerak: keyingi oynada «Ombor Pechat» uchun «Ruxsat berish» ni yoqing, "
                            + "keyin orqaga qaytib yana «YANGILASH» ni bosing.")
                    .setPositiveButton("OK", (d, w) -> a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + a.getPackageName()))))
                    .show();
            return;
        }
        if (ketyapti) return;
        ketyapti = true;
        Jurnal.yoz("⬇️ Yangi versiya yuklanmoqda…");
        android.widget.Toast.makeText(a, "Yuklanmoqda… biroz kuting", android.widget.Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                HttpURLConnection h = ochish(url);
                if (h.getResponseCode() != 200) throw new Exception("yuklab bo'lmadi (" + h.getResponseCode() + ")");
                PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(a.getPackageName());
                int id = pi.createSession(sp);
                PackageInstaller.Session s = pi.openSession(id);
                try (InputStream in = h.getInputStream(); OutputStream o = s.openWrite("ombor-pechat.apk", 0, -1)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                    s.fsync(o);
                }
                h.disconnect();
                Intent i = new Intent(NATIJA).setPackage(a.getPackageName()).setClass(a, Natija.class);
                int fl = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                PendingIntent p = PendingIntent.getBroadcast(a, id, i, fl | PendingIntent.FLAG_UPDATE_CURRENT);
                s.commit(p.getIntentSender());
                s.close();
                Jurnal.yoz("O'rnatish oynasi ochilmoqda — «O'rnatish» / «Обновить» ni bosing");
            } catch (Exception e) {
                Jurnal.yoz("⚠️ Yangilash: " + e.getMessage());
                a.runOnUiThread(() -> android.widget.Toast.makeText(a, "Yangilash: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            } finally {
                ketyapti = false;
            }
        }).start();
    }

    /** O'rnatish natijasi: telefon tasdiq so'rasa — oynani ochamiz. */
    public static class Natija extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent tasdiq = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (tasdiq != null) {
                    tasdiq.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(tasdiq);
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                Jurnal.yoz("✅ Yangilandi");
            } else {
                Jurnal.yoz("⚠️ O'rnatilmadi: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }
}
