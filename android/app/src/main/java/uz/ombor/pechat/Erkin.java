package uz.ombor.pechat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** ✍ Erkin yorliq: istalgan yozuv 58x40 yorliqni to'ldiradigan eng katta harflar bilan (QR'siz). */
public final class Erkin {
    private Erkin() {}

    public static final int W = 464, H = 320;   // 58x40 mm, 203 dpi
    private static final int CHET = 12;          // chetdan bo'sh joy (nuqta)

    private static StaticLayout joylash(String s, float size) {
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextSize(size);
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, W - 2 * CHET)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0, 0.92f)
                .setIncludePad(false)
                .build();
    }

    private static boolean sigadimi(String s, float size) {
        StaticLayout l = joylash(s, size);
        if (l.getHeight() > H - 2 * CHET) return false;
        TextPaint p = l.getPaint();
        for (String w : s.split("\\s+")) {   // so'z o'rtasidan bo'linmasin
            if (p.measureText(w) > W - 2 * CHET) return false;
        }
        return true;
    }

    /** Yozuvning oq-qora rasmi (oldindan ko'rish va chop etish uchun). */
    public static Bitmap rasm(String matn) {
        String s = matn == null ? "" : matn.trim();
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        if (s.isEmpty()) return b;
        float lo = 8, hi = 400;
        if (!sigadimi(s, lo)) hi = lo;
        while (hi - lo > 0.5f) {
            float mid = (lo + hi) / 2;
            if (sigadimi(s, mid)) lo = mid; else hi = mid;
        }
        StaticLayout l = joylash(s, lo);
        c.save();
        c.translate(CHET, Math.max(CHET, (H - l.getHeight()) / 2f));
        l.draw(c);
        c.restore();
        return b;
    }

    /** TSPL: BITMAP buyrug'i bilan (kirill va har qanday belgi chiqadi). */
    public static byte[] tspl(String matn, int nusxa) {
        Bitmap b = rasm(matn);
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
        String bosh = "SIZE 58 mm,40 mm\r\nGAP 2 mm,0 mm\r\nDIRECTION 1,0\r\nREFERENCE 0,0\r\nOFFSET 0 mm\r\n"
                + "DENSITY 8\r\nSPEED 4\r\nSET TEAR ON\r\nCLS\r\n"
                + "BITMAP 0,0," + wb + "," + H + ",0,";
        o.write(bosh.getBytes(StandardCharsets.US_ASCII), 0, bosh.length());
        o.write(d, 0, d.length);
        byte[] oxir = ("\r\nPRINT 1," + Math.max(1, Math.min(999, nusxa)) + "\r\n").getBytes(StandardCharsets.US_ASCII);
        o.write(oxir, 0, oxir.length);
        return o.toByteArray();
    }
}
