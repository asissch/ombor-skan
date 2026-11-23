package uz.ombor.pechat;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** USB (OTG) orqali ulangan printerlar: Xprinter (TSPL, to'g'ridan-to'g'ri) va Canon (IPP-over-USB bo'lsa). */
public final class UsbPrinter {
    public static final String RUXSAT = "uz.ombor.pechat.USB_RUXSAT";
    public static final int CANON_VID = 0x04A9;

    private UsbPrinter() {}

    public static UsbManager um(Context c) {
        return (UsbManager) c.getSystemService(Context.USB_SERVICE);
    }

    public static boolean canonmi(UsbDevice d) {
        if (d.getVendorId() == CANON_VID) return true;
        String n = nom(d).toLowerCase(Locale.ROOT);
        return n.contains("canon");
    }

    public static String nom(UsbDevice d) {
        String m = null, p = null;
        try { m = d.getManufacturerName(); p = d.getProductName(); } catch (Exception ignored) {}
        String s = ((m == null ? "" : m) + " " + (p == null ? "" : p)).trim();
        if (s.isEmpty()) s = String.format(Locale.US, "USB %04X:%04X", d.getVendorId(), d.getProductId());
        return s;
    }

    private static boolean printerKlass(UsbDevice d) {
        if (d.getDeviceClass() == 7) return true;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (d.getInterface(i).getInterfaceClass() == 7) return true;
        }
        return false;
    }

    private static boolean bulkOutBor(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (bulk(d.getInterface(i), UsbConstants.USB_DIR_OUT) != null) return true;
        }
        return false;
    }

    /** Yorliq printeri (Xprinter): Canon bo'lmagan printer klassidagi qurilma. */
    public static UsbDevice yorliqPrinter(Context c) {
        UsbDevice zaxira = null;
        for (UsbDevice d : um(c).getDeviceList().values()) {
            if (canonmi(d)) continue;
            if (printerKlass(d)) return d;
            String n = nom(d).toLowerCase(Locale.ROOT);
            if ((n.contains("xprinter") || n.contains("print") || n.contains("xp-")) && bulkOutBor(d)) zaxira = d;
        }
        return zaxira;
    }

    public static UsbDevice canon(Context c) {
        for (UsbDevice d : um(c).getDeviceList().values()) {
            if (canonmi(d)) return d;
        }
        return null;
    }

    /** Ruxsat bo'lmasa so'raydi (telefon ekranida oyna chiqadi). true = ruxsat bor. */
    public static boolean ruxsat(Context c, UsbDevice d) {
        UsbManager m = um(c);
        if (m.hasPermission(d)) return true;
        Intent i = new Intent(RUXSAT).setPackage(c.getPackageName());
        int fl = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        m.requestPermission(d, PendingIntent.getBroadcast(c, 0, i, fl | PendingIntent.FLAG_UPDATE_CURRENT));
        return false;
    }

    private static UsbEndpoint bulk(UsbInterface intf, int dir) {
        for (int e = 0; e < intf.getEndpointCount(); e++) {
            UsbEndpoint ep = intf.getEndpoint(e);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection() == dir) return ep;
        }
        return null;
    }

    /** Xprinter'ga TSPL baytlarini to'g'ridan-to'g'ri yuboradi. */
    public static void yubor(Context c, UsbDevice d, byte[] data) throws IOException {
        if (!um(c).hasPermission(d)) {
            ruxsat(c, d);
            throw new IOException("USB ruxsat berilmagan — telefonda «Ruxsat berish» ni bosing");
        }
        UsbInterface tanlangan = null;
        UsbEndpoint out = null;
        for (int i = 0; i < d.getInterfaceCount() && out == null; i++) {
            UsbInterface intf = d.getInterface(i);
            if (intf.getInterfaceClass() == 7 && intf.getInterfaceProtocol() == 4) continue; // IPP-USB emas
            UsbEndpoint ep = bulk(intf, UsbConstants.USB_DIR_OUT);
            if (ep != null) { tanlangan = intf; out = ep; }
        }
        if (out == null) throw new IOException("printerda yozish kanali (bulk OUT) topilmadi");
        UsbDeviceConnection con = um(c).openDevice(d);
        if (con == null) throw new IOException("USB ochilmadi");
        try {
            if (!con.claimInterface(tanlangan, true)) throw new IOException("USB interfeys band");
            int pos = 0;
            while (pos < data.length) {
                int n = Math.min(16384, data.length - pos);
                byte[] buf = new byte[n];
                System.arraycopy(data, pos, buf, 0, n);
                int w = con.bulkTransfer(out, buf, n, 10000);
                if (w <= 0) throw new IOException("printerga yozilmadi (qog'oz/qopqoqni tekshiring)");
                pos += w;
            }
            con.releaseInterface(tanlangan);
        } finally {
            con.close();
        }
    }

    /** Canon USB'da IPP-over-USB (klass 7/1/4) interfeysi bormi. */
    public static UsbInterface ippUsbInterfeys(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (intf.getInterfaceClass() == 7 && intf.getInterfaceSubclass() == 1 && intf.getInterfaceProtocol() == 4
                    && bulk(intf, UsbConstants.USB_DIR_OUT) != null && bulk(intf, UsbConstants.USB_DIR_IN) != null) {
                return intf;
            }
        }
        return null;
    }

    /** IPP-over-USB: HTTP so'rovni USB orqali yuborib, javob tanasini qaytaradi. */
    public static class IppUsb implements Ipp.Transport {
        private final Context c;
        private final UsbDevice d;

        public IppUsb(Context c, UsbDevice d) { this.c = c; this.d = d; }

        @Override
        public String uri() { return "ipp://localhost/ipp/print"; }

        @Override
        public byte[] post(byte[] body) throws IOException {
            if (!um(c).hasPermission(d)) {
                ruxsat(c, d);
                throw new IOException("Canon uchun USB ruxsat berilmagan — telefonda «Ruxsat berish» ni bosing");
            }
            UsbInterface intf = ippUsbInterfeys(d);
            if (intf == null) throw new IOException("Canon USB orqali drayversiz chop etishni qo'llamaydi (IPP-USB yo'q)");
            UsbEndpoint out = bulk(intf, UsbConstants.USB_DIR_OUT), in = bulk(intf, UsbConstants.USB_DIR_IN);
            UsbDeviceConnection con = um(c).openDevice(d);
            if (con == null) throw new IOException("Canon USB ochilmadi");
            try {
                if (!con.claimInterface(intf, true)) throw new IOException("Canon USB interfeys band");
                con.setInterface(intf);
                String h = "POST /ipp/print HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/ipp\r\n"
                        + "Content-Length: " + body.length + "\r\nConnection: keep-alive\r\n\r\n";
                byte[] hb = h.getBytes(StandardCharsets.US_ASCII);
                byte[] all = new byte[hb.length + body.length];
                System.arraycopy(hb, 0, all, 0, hb.length);
                System.arraycopy(body, 0, all, hb.length, body.length);
                int pos = 0;
                while (pos < all.length) {
                    int n = Math.min(16384, all.length - pos);
                    byte[] buf = new byte[n];
                    System.arraycopy(all, pos, buf, 0, n);
                    int w = con.bulkTransfer(out, buf, n, 15000);
                    if (w <= 0) throw new IOException("Canon'ga USB orqali yozilmadi");
                    pos += w;
                }
                return httpJavob(con, in);
            } finally {
                con.releaseInterface(intf);
                con.close();
            }
        }

        private static byte[] httpJavob(UsbDeviceConnection con, UsbEndpoint in) throws IOException {
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            long oxir = System.currentTimeMillis() + 60000;
            int bosh = -1;
            long uzunlik = -1;
            boolean chunked = false;
            while (System.currentTimeMillis() < oxir) {
                int n = con.bulkTransfer(in, buf, buf.length, 3000);
                if (n > 0) acc.write(buf, 0, n);
                byte[] a = acc.toByteArray();
                if (bosh < 0) {
                    bosh = topish(a, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    if (bosh >= 0) {
                        String hdr = new String(a, 0, bosh, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                        if (hdr.startsWith("http/1.1 100")) { // Continue — tashlab yuboramiz
                            acc.reset();
                            acc.write(a, bosh + 4, a.length - bosh - 4);
                            bosh = -1;
                            continue;
                        }
                        for (String q : hdr.split("\r\n")) {
                            if (q.startsWith("content-length:")) uzunlik = Long.parseLong(q.substring(15).trim());
                            if (q.startsWith("transfer-encoding:") && q.contains("chunked")) chunked = true;
                        }
                        bosh += 4;
                    }
                }
                if (bosh >= 0) {
                    if (uzunlik >= 0 && a.length - bosh >= uzunlik) {
                        byte[] r = new byte[(int) uzunlik];
                        System.arraycopy(a, bosh, r, 0, r.length);
                        return r;
                    }
                    if (chunked) {
                        byte[] r = dechunk(a, bosh);
                        if (r != null) return r;
                    }
                    if (uzunlik < 0 && !chunked && n <= 0) {
                        byte[] r = new byte[a.length - bosh];
                        System.arraycopy(a, bosh, r, 0, r.length);
                        return r;
                    }
                }
            }
            throw new IOException("Canon USB orqali javob bermadi");
        }

        private static int topish(byte[] a, byte[] t) {
            outer:
            for (int i = 0; i + t.length <= a.length; i++) {
                for (int j = 0; j < t.length; j++) if (a[i + j] != t[j]) continue outer;
                return i;
            }
            return -1;
        }

        /** To'liq kelgan bo'lsa chunked tanani yig'adi, bo'lmasa null. */
        private static byte[] dechunk(byte[] a, int p) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            while (true) {
                int e = p;
                while (e + 1 < a.length && !(a[e] == '\r' && a[e + 1] == '\n')) e++;
                if (e + 1 >= a.length) return null;
                String s = new String(a, p, e - p, StandardCharsets.US_ASCII).trim();
                int sc = s.indexOf(';');
                if (sc >= 0) s = s.substring(0, sc);
                int len;
                try { len = Integer.parseInt(s, 16); } catch (NumberFormatException ex) { return null; }
                p = e + 2;
                if (len == 0) return o.toByteArray();
                if (p + len + 2 > a.length) return null;
                o.write(a, p, len);
                p += len + 2;
            }
        }
    }
}
