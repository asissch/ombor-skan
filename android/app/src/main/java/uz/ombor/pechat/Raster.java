package uz.ombor.pechat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Botdagi A4 hujjat (chizish buyruqlari, mm): ["t",x1,y1,x2,y2,matn,pt,belgilar], ["l",x1,y1,x2,y2,qalinlik],
 * ["f",x1,y1,x2,y2,kulrang]. Belgilar: b qalin, c o'rtada, r o'ngda, w ko'p qatorli.
 * Bu yerda ular rasmga (PWG/URF) yoki PDF'ga chiziladi.
 */
public final class Raster {
    private Raster() {}

    static float[] sahifa(JSONObject job) {
        JSONObject pg = job.optJSONObject("page");
        float w = pg == null ? 210 : (float) pg.optDouble("w", 210);
        float h = pg == null ? 297 : (float) pg.optDouble("h", 297);
        boolean land = pg != null && pg.optBoolean("landscape", w > h);
        return new float[]{w, h, land ? 1 : 0};
    }

    /** Bir sahifani kanvasga chizadi: k = 1 mm dagi birlik. */
    static void chiz(Canvas cv, JSONArray ops, float k) {
        Paint fill = new Paint();
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < ops.length(); i++) {
            JSONArray op = ops.optJSONArray(i);
            if (op == null || !"f".equals(op.optString(0))) continue;
            int g = Math.max(0, Math.min(255, (int) Math.round(op.optDouble(5, 0.9) * 255)));
            fill.setColor(Color.rgb(g, g, g));
            cv.drawRect(mm(op, 1, k), mm(op, 2, k), mm(op, 3, k), mm(op, 4, k), fill);
        }
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(Color.BLACK);
        line.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < ops.length(); i++) {
            JSONArray op = ops.optJSONArray(i);
            if (op == null) continue;
            String t = op.optString(0);
            if ("l".equals(t)) {
                line.setStrokeWidth(Math.max(1f, (float) op.optDouble(5, 0.3) * k));
                cv.drawLine(mm(op, 1, k), mm(op, 2, k), mm(op, 3, k), mm(op, 4, k), line);
            } else if ("t".equals(t)) {
                matn(cv, op, k);
            }
        }
    }

    private static float mm(JSONArray op, int i, float k) {
        return (float) op.optDouble(i, 0) * k;
    }

    private static void matn(Canvas cv, JSONArray op, float k) {
        RectF r = new RectF(mm(op, 1, k), mm(op, 2, k), mm(op, 3, k), mm(op, 4, k));
        String s = op.optString(5, "");
        float pt = (float) op.optDouble(6, 10);
        String fl = op.optString(7, "");
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(pt * 25.4f / 72f * k);
        p.setTypeface(fl.contains("b") ? Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) : Typeface.SANS_SERIF);
        Layout.Alignment al = fl.contains("c") ? Layout.Alignment.ALIGN_CENTER
                : fl.contains("r") ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        int w = Math.max(1, (int) r.width());
        cv.save();
        cv.clipRect(r);
        if (fl.contains("w")) {
            StaticLayout sl = StaticLayout.Builder.obtain(s, 0, s.length(), p, w).setAlignment(al).build();
            float top = r.top + Math.max(0, (r.height() - sl.getHeight()) / 2f);
            cv.translate(r.left, top);
            sl.draw(cv);
        } else {
            CharSequence e = TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END);
            float tw = p.measureText(e, 0, e.length());
            float x = al == Layout.Alignment.ALIGN_CENTER ? r.left + (r.width() - tw) / 2f
                    : al == Layout.Alignment.ALIGN_OPPOSITE ? r.right - tw : r.left;
            Paint.FontMetrics fm = p.getFontMetrics();
            float y = r.centerY() - (fm.ascent + fm.descent) / 2f;
            cv.drawText(e, 0, e.length(), x, y, p);
        }
        cv.restore();
    }

    /** A4 tik (portret) rasm: yotiq hujjat 90° buriladi. */
    static Bitmap rasm(JSONObject job, JSONArray ops, int dpi) {
        float[] pg = sahifa(job);
        int W = Math.round(210f / 25.4f * dpi), H = Math.round(297f / 25.4f * dpi);
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        cv.drawColor(Color.WHITE);
        float k = dpi / 25.4f;
        boolean land = pg[2] > 0;
        float pw = land ? Math.max(pg[0], pg[1]) : pg[0], ph = land ? Math.min(pg[0], pg[1]) : pg[1];
        float s = Math.min(1f, Math.min((land ? 297f : 210f) / pw, (land ? 210f : 297f) / ph));
        if (land) {
            cv.translate(W, 0);
            cv.rotate(90);
        }
        cv.scale(s, s);
        chiz(cv, ops, k);
        return bm;
    }

    private static byte[] kulrang(Bitmap bm, int y, int[] px, byte[] out) {
        int w = bm.getWidth();
        bm.getPixels(px, 0, w, 0, y, w, 1);
        for (int x = 0; x < w; x++) {
            int c = px[x];
            out[x] = (byte) (((c >> 16 & 0xff) * 30 + (c >> 8 & 0xff) * 59 + (c & 0xff) * 11) / 100);
        }
        return out;
    }

    /** PWG/URF uchun umumiy siqish (qator takrori + PackBits'ga o'xshash). 128 kodi ishlatilmaydi. */
    private static void satrlar(Bitmap bm, DataOutputStream o) throws IOException {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w];
        byte[] cur = new byte[w], prev = new byte[w];
        int y = 0;
        kulrang(bm, 0, px, cur);
        while (y < h) {
            byte[] line = cur.clone();
            int rep = 0;
            int ny = y + 1;
            while (ny < h && rep < 255) {
                kulrang(bm, ny, px, prev);
                if (!java.util.Arrays.equals(prev, line)) break;
                rep++;
                ny++;
            }
            o.writeByte(rep);
            kodla(line, o);
            y = ny;
            if (y < h) kulrang(bm, y, px, cur);
        }
    }

    private static void kodla(byte[] l, DataOutputStream o) throws IOException {
        int n = l.length, i = 0;
        while (i < n) {
            int r = 1;
            while (i + r < n && r < 128 && l[i + r] == l[i]) r++;
            if (r >= 2) {
                o.writeByte(r - 1);
                o.writeByte(l[i]);
                i += r;
                continue;
            }
            int j = i;
            while (j < n && j - i < 128 && (j + 1 >= n || l[j] != l[j + 1])) j++;
            int len = Math.max(1, j - i);
            if (len == 1) {
                o.writeByte(0);
                o.writeByte(l[i]);
            } else {
                o.writeByte(257 - len);
                o.write(l, i, len);
            }
            i += len;
        }
    }

    private static int sahifalar(JSONObject job) {
        JSONArray p = job.optJSONArray("pages");
        return p == null ? 0 : p.length();
    }

    public static byte[] pwg(JSONObject job, int dpi, int nusxa) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.write("RaS2".getBytes(StandardCharsets.US_ASCII));
        JSONArray pages = job.optJSONArray("pages");
        int jami = sahifalar(job) * Math.max(1, nusxa);
        for (int c = 0; c < Math.max(1, nusxa); c++) {
            for (int i = 0; i < pages.length(); i++) {
                Bitmap bm = rasm(job, pages.optJSONArray(i), dpi);
                o.write(pwgSarlavha(bm.getWidth(), bm.getHeight(), dpi, jami));
                satrlar(bm, o);
                bm.recycle();
            }
        }
        o.flush();
        return b.toByteArray();
    }

    private static byte[] pwgSarlavha(int w, int h, int dpi, int jami) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1796);
        str(b, 0, "PwgRaster");
        b.putInt(276, dpi);
        b.putInt(280, dpi);
        b.putInt(340, 1);         // NumCopies
        b.putInt(352, 595);       // PageSize (pt)
        b.putInt(356, 842);
        b.putInt(372, w);
        b.putInt(376, h);
        b.putInt(384, 8);         // BitsPerColor
        b.putInt(388, 8);         // BitsPerPixel
        b.putInt(392, w);         // BytesPerLine
        b.putInt(396, 0);         // chunky
        b.putInt(400, 18);        // sGray
        b.putInt(420, 1);         // NumColors
        b.putInt(452, jami);      // TotalPageCount
        b.putInt(456, 1);
        b.putInt(460, 1);
        b.putInt(472, w);         // ImageBox
        b.putInt(476, h);
        str(b, 1732, "iso_a4_210x297mm");
        return b.array();
    }

    private static void str(java.nio.ByteBuffer b, int off, String s) {
        byte[] x = s.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < x.length && i < 63; i++) b.put(off + i, x[i]);
    }

    public static byte[] urf(JSONObject job, int dpi, int nusxa) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        JSONArray pages = job.optJSONArray("pages");
        o.write("UNIRAST\0".getBytes(StandardCharsets.US_ASCII));
        o.writeInt(sahifalar(job) * Math.max(1, nusxa));
        for (int c = 0; c < Math.max(1, nusxa); c++) {
            for (int i = 0; i < pages.length(); i++) {
                Bitmap bm = rasm(job, pages.optJSONArray(i), dpi);
                o.writeByte(8);   // bpp
                o.writeByte(0);   // sGray
                o.writeByte(1);   // bir tomonlama
                o.writeByte(4);   // sifat: normal
                o.writeInt(0);
                o.writeInt(0);
                o.writeInt(bm.getWidth());
                o.writeInt(bm.getHeight());
                o.writeInt(dpi);
                o.writeInt(0);
                o.writeInt(0);
                satrlar(bm, o);
                bm.recycle();
            }
        }
        o.flush();
        return b.toByteArray();
    }

    /** PDF (A4, yotiq bo'lsa yotiq sahifa). Telefonning o'z chop etish oynasi uchun ham. */
    public static byte[] pdf(JSONObject job, int nusxa) throws IOException {
        float[] pg = sahifa(job);
        boolean land = pg[2] > 0;
        int pw = Math.round((land ? 297 : 210) * 72 / 25.4f), ph = Math.round((land ? 210 : 297) * 72 / 25.4f);
        PdfDocument d = new PdfDocument();
        JSONArray pages = job.optJSONArray("pages");
        int n = 1;
        for (int c = 0; c < Math.max(1, nusxa); c++) {
            for (int i = 0; i < pages.length(); i++) {
                PdfDocument.Page p = d.startPage(new PdfDocument.PageInfo.Builder(pw, ph, n++).create());
                Canvas cv = p.getCanvas();
                float s = Math.min(1f, Math.min((land ? 297f : 210f) / Math.max(pg[0], 1), (land ? 210f : 297f) / Math.max(pg[1], 1)));
                cv.scale(s, s);
                chiz(cv, pages.optJSONArray(i), 72 / 25.4f);
                d.finishPage(p);
            }
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        d.writeTo(b);
        d.close();
        return b.toByteArray();
    }
}
