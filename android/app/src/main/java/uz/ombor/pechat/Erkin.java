package uz.ombor.pechat;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** ✍ Erkin yorliq: istalgan yozuv yorliqni to'ldiradigan eng katta harflar bilan (QR'siz). */
public final class Erkin {
    private Erkin() {}

    public static final String[] OLCHAMLAR = {"30x20", "40x25", "40x30", "50x25", "50x30", "50x40", "58x30", "58x40",
            "58x50", "58x60", "60x40", "70x40", "70x50", "75x50", "80x40", "80x50", "80x60", "80x80"};
    private static final double D = 203 / 25.4;   // nuqta / mm

    /** Sozlama: qog'oz o'lchami, yo'nalish, siljitish (telefonda saqlanadi). */
    public static final class Soz {
        public float w = 58, h = 40, xOff = 0, yOff = 0;
        public boolean vertikal = false;

        public static Soz oqi(Context c) {
            SharedPreferences sp = Jurnal.sozlama(c);
            Soz s = new Soz();
            s.w = sp.getFloat("e_w", 58);
            s.h = sp.getFloat("e_h", 40);
            s.xOff = sp.getFloat("e_x", 0);
            s.yOff = sp.getFloat("e_y", 0);
            s.vertikal = sp.getBoolean("e_v", false);
            return s;
        }

        public void saqla(Context c) {
            Jurnal.sozlama(c).edit().putFloat("e_w", w).putFloat("e_h", h).putFloat("e_x", xOff)
                    .putFloat("e_y", yOff).putBoolean("e_v", vertikal).apply();
        }

        public String olcham() {
            return fmt(w) + "x" + fmt(h);
        }

        int W() { return (int) Math.round(w * D) / 8 * 8; }
        int H() { return (int) Math.round(h * D); }
    }

    static String fmt(float f) {
        return f == Math.floor(f) ? String.valueOf((int) f) : String.format(Locale.US, "%.1f", f);
    }

    /** "60x40" -> {60,40}; noto'g'ri bo'lsa null. */
    public static float[] parse(String t) {
        if (t == null) return null;
        String[] p = t.toLowerCase(Locale.ROOT).replace("mm", "").replace('х', 'x').replace('*', 'x')
                .replace(',', '.').replace(" ", "").split("x");
        if (p.length != 2) return null;
        try {
            float w = Float.parseFloat(p[0]), h = Float.parseFloat(p[1]);
            if (w < 15 || w > 110 || h < 10 || h > 300) return null;
            return new float[]{w, h};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static StaticLayout joylash(String s, float size, int eni) {
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextSize(size);
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, eni)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0, 0.92f)
                .setIncludePad(false)
                .build();
    }

    private static boolean sigadimi(String s, float size, int bw, int bh) {
        StaticLayout l = joylash(s, size, bw);
        if (l.getHeight() > bh) return false;
        TextPaint p = l.getPaint();
        for (String w : s.split("\\s+")) {   // so'z o'rtasidan bo'linmasin
            if (p.measureText(w) > bw) return false;
        }
        return true;
    }

    /** Yorliq rasmi printer yo'nalishida (W x H). ramka=true — chegara sinovi. */
    public static Bitmap rasm(String matn, Soz z, boolean ramka) {
        String s = matn == null ? "" : matn.trim();
        int W = z.W(), H = z.H();
        int m = (int) Math.round(3 * D);
        int xo = (int) Math.round(z.xOff * D), yo = (int) Math.round(z.yOff * D);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        if (ramka) {
            Paint p = new Paint();
            p.setColor(Color.BLACK);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            c.drawRect(4 + xo, 4 + yo, W - 4 + xo, H - 4 + yo, p);
        }
        if (s.isEmpty()) return b;
        // vertikal: matn 90° burilgan, joy eni/bo'yi almashadi
        int bw = (z.vertikal ? H : W) - 2 * m, bh = (z.vertikal ? W : H) - 2 * m;
        if (bw < 10 || bh < 10) return b;
        float lo = 6, hi = 600;
        if (!sigadimi(s, lo, bw, bh)) hi = lo;
        while (hi - lo > 0.5f) {
            float mid = (lo + hi) / 2;
            if (sigadimi(s, mid, bw, bh)) lo = mid; else hi = mid;
        }
        StaticLayout l = joylash(s, lo, bw);
        c.save();
        c.translate(xo, yo);
        if (z.vertikal) {
            c.translate(W, 0);
            c.rotate(90);   // endi x — yorliq bo'yi bo'ylab, y — enidan chapga
        }
        c.translate(m, m + Math.max(0, (bh - l.getHeight()) / 2f));
        l.draw(c);
        c.restore();
        return b;
    }

    /** Ekranda ko'rsatish uchun: vertikal bo'lsa o'qiladigan qilib burib beradi. */
    public static Bitmap korinish(String matn, Soz z) {
        return rasm(matn, z, true);
    }

    /** TSPL: BITMAP buyrug'i bilan (kirill va har qanday belgi chiqadi). */
    public static byte[] tspl(String matn, int nusxa, Soz z, boolean ramka) {
        Bitmap b = rasm(matn, z, ramka);
        int W = b.getWidth(), H = b.getHeight();
        int wb = W / 8;
        byte[] d = new byte[wb * H];
        int[] row = new int[W];
        for (int y = 0; y < H; y++) {
            b.getPixels(row, 0, W, 0, y, W, 1);
            for (int x = 0; x < W; x++) {
                int px = row[x];
                int lum = (Color.red(px) * 30 + Color.green(px) * 59 + Color.blue(px) * 11) / 100;
                if (lum >= 140) d[y * wb + x / 8] |= (byte) (0x80 >> (x % 8));   // 1 = oq, 0 = qora
            }
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        String bosh = "SIZE " + fmt(z.w) + " mm," + fmt(z.h) + " mm\r\nGAP 2 mm,0 mm\r\nDIRECTION 1,0\r\nREFERENCE 0,0\r\n"
                + "OFFSET 0 mm\r\nDENSITY 8\r\nSPEED 4\r\nSET TEAR ON\r\nCLS\r\n"
                + "BITMAP 0,0," + wb + "," + H + ",0,";
        o.write(bosh.getBytes(StandardCharsets.US_ASCII), 0, bosh.length());
        o.write(d, 0, d.length);
        byte[] oxir = ("\r\nPRINT 1," + Math.max(1, Math.min(999, nusxa)) + "\r\n").getBytes(StandardCharsets.US_ASCII);
        o.write(oxir, 0, oxir.length);
        return o.toByteArray();
    }

    /** Printer yorliq o'lchamini (bo'shliqni) o'zi o'lchaydi. */
    public static final byte[] KALIBR = "GAPDETECT\r\n".getBytes(StandardCharsets.US_ASCII);
}
