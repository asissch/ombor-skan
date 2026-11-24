package uz.ombor.pechat;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bir nechta printer (har biri o'z telefonida). Har bir printer telefoni o'z nomi va o'z pechat boti bilan
 * bitta «Pechat» kanalini o'qiydi. Boshqa printerga yuborish = kanalga «#pechat <nom> <id>» yozish.
 * Printer pasporti: QR (botga havola: start=prn_<nom>) + printer nomi.
 */
public final class Printerlar {
    private Printerlar() {}

    private static final Pattern PASPORT = Pattern.compile("(?:start=prn_|OMBOR-PRINTER:)([A-Za-z0-9_\\-]{1,32})");
    private static final Pattern BOT = Pattern.compile("t\\.me/([A-Za-z0-9_]{4,40})\\?start=");

    public static String ozim(Context c) {
        String n = Jurnal.yorliqPrinter(c);
        return n.isEmpty() || n.equals("*") ? "kirim" : n;
    }

    public static String toza(String n) {
        return n == null ? "" : n.trim().toLowerCase(Locale.ROOT);
    }

    public static List<String> hammasi(Context c) {
        Set<String> s = new HashSet<>(Jurnal.sozlama(c).getStringSet("printerlar", new HashSet<>()));
        s.add(ozim(c));
        List<String> l = new ArrayList<>(s);
        Collections.sort(l);
        l.remove(ozim(c));
        l.add(0, ozim(c));
        return l;
    }

    public static void qosh(Context c, String nom) {
        nom = toza(nom);
        if (nom.isEmpty() || !nom.matches("[a-z0-9_\\-]{1,32}")) return;
        SharedPreferences sp = Jurnal.sozlama(c);
        Set<String> s = new HashSet<>(sp.getStringSet("printerlar", new HashSet<>()));
        if (s.add(nom)) {
            sp.edit().putStringSet("printerlar", s).apply();
            Jurnal.yoz("🖨 Printer ro'yxatga qo'shildi: " + nom);
        }
    }

    public static void ochir(Context c, String nom) {
        SharedPreferences sp = Jurnal.sozlama(c);
        Set<String> s = new HashSet<>(sp.getStringSet("printerlar", new HashSet<>()));
        s.remove(toza(nom));
        sp.edit().putStringSet("printerlar", s).apply();
    }

    /** QR matnidan printer nomi (pasport bo'lsa), aks holda null. */
    public static String pasportdan(String qr) {
        if (qr == null) return null;
        Matcher m = PASPORT.matcher(qr);
        return m.find() ? toza(m.group(1)) : null;
    }

    public static long kanal(Context c) { return Jurnal.sozlama(c).getLong("kanal", 0); }

    public static void kanalQoy(Context c, long id) {
        if (id != 0 && id != kanal(c)) Jurnal.sozlama(c).edit().putLong("kanal", id).apply();
    }

    public static String botUser(Context c) { return Jurnal.sozlama(c).getString("bot_user", ""); }

    /** Kelgan yorliqdagi QR havoladan asosiy bot nomini o'rganadi (pasport QR'i uchun). */
    public static void botniOrgan(Context c, byte[] tspl) {
        String s = new String(tspl, 0, Math.min(tspl.length, 4000), StandardCharsets.ISO_8859_1);
        Matcher m = BOT.matcher(s);
        if (m.find() && !m.group(1).equals(botUser(c))) Jurnal.sozlama(c).edit().putString("bot_user", m.group(1)).apply();
    }

    public static String pasportQr(Context c, String nom) {
        String b = botUser(c);
        return b.isEmpty() ? "OMBOR-PRINTER:" + nom : "https://t.me/" + b + "?start=prn_" + nom;
    }

    /** Yorliqni tanlangan printerga: o'zimniki bo'lsa — USB, boshqasi bo'lsa — kanal orqali o'sha telefonga. */
    public static String yubor(Context c, String nom, byte[] tspl) throws Exception {
        nom = toza(nom);
        if (nom.isEmpty() || nom.equals(ozim(c))) return Chop.yorliq(c, tspl) + " (shu telefon)";
        String token = Jurnal.token(c);
        long k = kanal(c);
        if (token.isEmpty()) throw new Exception("Avval pechat bot tokenini kiriting va ishga tushiring");
        if (k == 0) throw new Exception("Pechat kanali hali aniqlanmadi: shu telefonning pechat botini kanalga ADMIN qiling "
                + "yoki botdan bitta yorliq chiqaring");
        String id = "T" + Long.toString(System.currentTimeMillis() % 100000000L, 36).toUpperCase(Locale.ROOT);
        Tg tg = new Tg(token);
        String b64 = android.util.Base64.encodeToString(tspl, android.util.Base64.NO_WRAP);
        if (b64.length() < 3500) {
            tg.call("sendMessage", new org.json.JSONObject().put("chat_id", k).put("text", "#pechat " + nom + " " + id + "\n" + b64)
                    .put("disable_notification", true), 30000);
        } else {
            tg.hujjat(k, "#pechat " + nom + " " + id, "yorliq_" + id + ".tspl", tspl);
        }
        return "«" + nom + "» printeriga yuborildi (#" + id + ")";
    }

    /** Pasport yorlig'i: chapda QR (printer o'zi chizadi), o'ngda katta qilib printer nomi. */
    public static byte[] pasport(Context c, String nom, Erkin.Soz z) {
        nom = toza(nom);
        double D = 203 / 25.4;
        int W = z.W(), H = z.H();
        int m = (int) Math.round(3 * D);
        int xo = (int) Math.round(z.xOff * D), yo = (int) Math.round(z.yOff * D);
        String qr = pasportQr(c, nom);
        int modul = qr.length() <= 26 ? 25 : qr.length() <= 42 ? 29 : qr.length() <= 62 ? 33 : 37;  // QR o'lchami (M)
        int qrMax = Math.min(H - 2 * m, W / 2);
        int cell = Math.max(2, Math.min(8, qrMax / (modul + 2)));
        int qrW = cell * modul;
        int qx = m + xo, qy = (H - qrW) / 2 + yo;

        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(Color.WHITE);
        int tx = m + qrW + m, tw = W - tx - m;
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        // tepada kichik «PRINTER», o'rtada eng katta nom, pastda kichik izoh
        float kichik = Math.max(14, H / 11f);
        p.setTextSize(kichik);
        cv.drawText("PRINTER", tx + xo, m + kichik + yo, p);
        float ost = H - 2 * m - 2.4f * kichik;
        float lo = 10, hi = 400;
        String n = nom.toUpperCase(Locale.ROOT);
        while (hi - lo > 0.5f) {
            float mid = (lo + hi) / 2;
            p.setTextSize(mid);
            StaticLayout sl = StaticLayout.Builder.obtain(n, 0, n.length(), p, Math.max(10, tw)).setIncludePad(false).build();
            boolean sig = sl.getHeight() <= ost && p.measureText(n) <= tw * Math.max(1, sl.getLineCount());
            for (String w : n.split("\\s+")) if (p.measureText(w) > tw) sig = false;
            if (sig) lo = mid; else hi = mid;
        }
        p.setTextSize(lo);
        StaticLayout sl = StaticLayout.Builder.obtain(n, 0, n.length(), p, Math.max(10, tw))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
        cv.save();
        cv.translate(tx + xo, m + 1.3f * kichik + (ost - sl.getHeight()) / 2f + yo);
        sl.draw(cv);
        cv.restore();
        p.setTextSize(kichik * 0.8f);
        p.setTypeface(Typeface.SANS_SERIF);
        cv.drawText("Skanerlang: shu printerga", tx + xo, H - m + yo, p);

        int wb = W / 8;
        byte[] d = new byte[wb * H];
        int[] row = new int[W];
        for (int y = 0; y < H; y++) {
            b.getPixels(row, 0, W, 0, y, W, 1);
            for (int x = 0; x < wb * 8; x++) {
                int px = row[x];
                int lum = (Color.red(px) * 30 + Color.green(px) * 59 + Color.blue(px) * 11) / 100;
                if (lum >= 140) d[y * wb + x / 8] |= (byte) (0x80 >> (x % 8));
            }
        }
        b.recycle();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        String bosh = "SIZE " + Erkin.fmt(z.w) + " mm," + Erkin.fmt(z.h) + " mm\r\nGAP 2 mm,0 mm\r\nDIRECTION 1,0\r\nREFERENCE 0,0\r\n"
                + "OFFSET 0 mm\r\nDENSITY 8\r\nSPEED 4\r\nSET TEAR ON\r\nCLS\r\n"
                + "BITMAP 0,0," + wb + "," + H + ",0,";
        byte[] bb = bosh.getBytes(StandardCharsets.US_ASCII);
        o.write(bb, 0, bb.length);
        o.write(d, 0, d.length);
        byte[] oxir = ("\r\nQRCODE " + qx + "," + qy + ",M," + cell + ",A,0,\"" + qr + "\"\r\nPRINT 1,1\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        o.write(oxir, 0, oxir.length);
        return o.toByteArray();
    }
}
