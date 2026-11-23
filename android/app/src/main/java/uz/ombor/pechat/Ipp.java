package uz.ombor.pechat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Drayversiz chop etish (IPP): Canon'ga Wi-Fi yoki IPP-over-USB orqali PWG/URF/PDF yuborish. */
public final class Ipp {
    public interface Transport {
        String uri();
        byte[] post(byte[] body) throws IOException;
    }

    /** Wi-Fi (bir tarmoqda): http://IP:631/ipp/print */
    public static class Http implements Transport {
        final String host, yol;
        final int port;

        public Http(String host, int port, String yol) {
            this.host = host; this.port = port; this.yol = yol.startsWith("/") ? yol : "/" + yol;
        }

        @Override
        public String uri() { return "ipp://" + host + ":" + port + yol; }

        @Override
        public byte[] post(byte[] body) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + yol).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(90000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/ipp");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            byte[] r = oqi(in);
            c.disconnect();
            if (code != 200) throw new IOException("Canon HTTP " + code);
            return r;
        }
    }

    static byte[] oqi(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    private static int reqId = 1;

    private static void attr(DataOutputStream o, int tag, String name, byte[] val) throws IOException {
        o.writeByte(tag);
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        o.writeShort(nb.length);
        o.write(nb);
        o.writeShort(val.length);
        o.write(val);
    }

    private static void attr(DataOutputStream o, int tag, String name, String val) throws IOException {
        attr(o, tag, name, val.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sorov(int op, String uri, String format, String jobName) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeShort(0x0101);
        o.writeShort(op);
        o.writeInt(reqId++);
        o.writeByte(0x01);
        attr(o, 0x47, "attributes-charset", "utf-8");
        attr(o, 0x48, "attributes-natural-language", "en");
        attr(o, 0x45, "printer-uri", uri);
        attr(o, 0x42, "requesting-user-name", "ombor");
        if (op == 0x000B) {
            attr(o, 0x44, "requested-attributes", "document-format-supported");
            attr(o, 0x44, "", "pwg-raster-document-resolution-supported");
            attr(o, 0x44, "", "pwg-raster-document-type-supported");
            attr(o, 0x44, "", "urf-supported");
            attr(o, 0x44, "", "printer-make-and-model");
            attr(o, 0x44, "", "printer-state");
            attr(o, 0x44, "", "printer-state-reasons");
        } else {
            attr(o, 0x42, "job-name", jobName);
            attr(o, 0x49, "document-format", format);
        }
        o.writeByte(0x03);
        o.flush();
        return b.toByteArray();
    }

    public static class Javob {
        int status;
        final Map<String, List<Object>> a = new HashMap<>();

        List<Object> l(String k) {
            List<Object> v = a.get(k);
            return v == null ? new ArrayList<>() : v;
        }

        boolean bor(String k, String qiymat) {
            for (Object x : l(k)) if (qiymat.equalsIgnoreCase(String.valueOf(x))) return true;
            return false;
        }

        String s(String k) {
            List<Object> v = l(k);
            return v.isEmpty() ? "" : String.valueOf(v.get(0));
        }
    }

    static Javob parse(byte[] r) throws IOException {
        if (r.length < 8) throw new IOException("Canon noto'g'ri javob berdi");
        ByteBuffer b = ByteBuffer.wrap(r);
        Javob j = new Javob();
        b.getShort();
        j.status = b.getShort() & 0xffff;
        b.getInt();
        String oxirgi = null;
        while (b.hasRemaining()) {
            int tag = b.get() & 0xff;
            if (tag == 0x03) break;
            if (tag < 0x10) continue;
            if (b.remaining() < 2) break;
            int nl = b.getShort() & 0xffff;
            byte[] nb = new byte[nl];
            b.get(nb);
            int vl = b.getShort() & 0xffff;
            byte[] vb = new byte[vl];
            b.get(vb);
            String nom = nl == 0 ? oxirgi : new String(nb, StandardCharsets.UTF_8);
            if (nom == null) continue;
            oxirgi = nom;
            Object v;
            ByteBuffer vv = ByteBuffer.wrap(vb);
            if ((tag == 0x21 || tag == 0x23) && vl == 4) v = vv.getInt();
            else if (tag == 0x32 && vl == 9) v = new int[]{vv.getInt(), vv.getInt(), vv.get()};
            else if (tag == 0x22 && vl == 1) v = vb[0] != 0;
            else v = new String(vb, StandardCharsets.UTF_8);
            List<Object> l = j.a.get(nom);
            if (l == null) { l = new ArrayList<>(); j.a.put(nom, l); }
            l.add(v);
        }
        return j;
    }

    /** Hujjatni printerga chop etadi. Qaytaradi: qisqa izoh (format). */
    public static String chop(Transport t, org.json.JSONObject job, int nusxa) throws Exception {
        Javob at = parse(t.post(sorov(0x000B, t.uri(), null, null)));
        if (at.status >= 0x0400) throw new IOException("Canon so'rovni qabul qilmadi (IPP 0x" + Integer.toHexString(at.status) + ")");
        String format;
        int dpi = 300;
        byte[] data;
        String nom = job.optString("nom", "Hujjat");
        if (at.bor("document-format-supported", "image/pwg-raster")) {
            format = "image/pwg-raster";
            dpi = tanlaDpi(at.l("pwg-raster-document-resolution-supported"));
            data = Raster.pwg(job, dpi, nusxa);
        } else if (at.bor("document-format-supported", "image/urf")) {
            format = "image/urf";
            dpi = 0;
            for (Object x : at.l("urf-supported")) {
                String s = String.valueOf(x);
                if (s.startsWith("RS")) {
                    for (String p : s.substring(2).split("-")) {
                        try {
                            int d = Integer.parseInt(p.trim());
                            if (dpi == 0 || Math.abs(d - 300) < Math.abs(dpi - 300)) dpi = d;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            if (dpi == 0) dpi = 300;
            data = Raster.urf(job, dpi, nusxa);
        } else if (at.bor("document-format-supported", "application/pdf")) {
            format = "application/pdf";
            data = Raster.pdf(job, nusxa);
        } else {
            throw new IOException("Canon formatlari mos kelmadi: " + at.l("document-format-supported"));
        }
        byte[] h = sorov(0x0002, t.uri(), format, nom);
        byte[] body = new byte[h.length + data.length];
        System.arraycopy(h, 0, body, 0, h.length);
        System.arraycopy(data, 0, body, h.length, data.length);
        Javob r = parse(t.post(body));
        if (r.status >= 0x0100) {
            throw new IOException("Canon ishni qabul qilmadi (IPP 0x" + Integer.toHexString(r.status) + ")");
        }
        return format.substring(format.indexOf('/') + 1) + " " + (dpi > 0 && !format.endsWith("pdf") ? dpi + "dpi" : "");
    }

    private static int tanlaDpi(List<Object> l) {
        int best = 0;
        for (Object x : l) {
            if (x instanceof int[]) {
                int d = ((int[]) x)[0];
                if (((int[]) x)[2] == 4) d = Math.round(d * 2.54f); // nuqta/sm
                if (best == 0 || Math.abs(d - 300) < Math.abs(best - 300)) best = d;
            }
        }
        return best == 0 ? 300 : best;
    }

    /** Printer haqida qisqa ma'lumot (sinov uchun). */
    public static String malumot(Transport t) throws Exception {
        Javob at = parse(t.post(sorov(0x000B, t.uri(), null, null)));
        return at.s("printer-make-and-model") + " — formatlar: " + at.l("document-format-supported");
    }
}
