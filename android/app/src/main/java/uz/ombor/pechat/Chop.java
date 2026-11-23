package uz.ombor.pechat;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;

/** Qaysi printer ulangan bo'lsa — o'shanga yuborish. */
public final class Chop {
    private Chop() {}

    public static final byte[] SINOV_TSPL = ("SIZE 58 mm,40 mm\r\nGAP 2 mm,0 mm\r\nDIRECTION 1,0\r\nREFERENCE 0,0\r\nCLS\r\n"
            + "BOX 2,2,461,317,2\r\n"
            + "QRCODE 16,16,M,5,A,0,\"SINOV\"\r\n"
            + "TEXT 180,20,\"3\",0,2,2,\"SINOV\"\r\n"
            + "TEXT 180,80,\"3\",0,1,1,\"Telefon ishlayapti\"\r\n"
            + "BARCODE 80,220,\"128\",56,1,0,2,2,\"R00001\"\r\n"
            + "PRINT 1,1\r\n").getBytes(StandardCharsets.US_ASCII);

    public static final String SINOV_A4 = "{\"v\":1,\"nom\":\"A4 sinov\",\"page\":{\"w\":297,\"h\":210,\"landscape\":true},\"pages\":[["
            + "[\"t\",8,8,289,20,\"A4 SINOV — telefon Canon'ga chop etyapti\",18,\"b\"],"
            + "[\"l\",8,24,289,24,0.6],[\"f\",8,30,100,45,0.85],[\"l\",8,30,100,30,0.2],[\"l\",8,45,100,45,0.2],"
            + "[\"t\",10,30,98,45,\"M1607 А (340) — Кириллица ham chiqadi\",11,\"bcw\"],"
            + "[\"t\",8,190,289,202,\"Agar bu varaq YOTIQ (albom) holatda chiqqan bo'lsa — hammasi joyida.\",10,\"l\"]]]}";

    /** Xprinter'ga TSPL. Printer ulanmagan bo'lsa IOException. */
    public static String yorliq(Context c, byte[] tspl) throws IOException {
        UsbDevice d = UsbPrinter.yorliqPrinter(c);
        if (d == null) throw new IOException("Xprinter ulanmagan");
        UsbPrinter.yubor(c, d, tspl);
        return UsbPrinter.nom(d);
    }

    /** A4 hujjat Canon'ga: avval USB (drayversiz bo'lsa), keyin Wi-Fi. */
    public static String a4(Context c, JSONObject job, int nusxa) throws Exception {
        saqla(c, job);
        String ip = Jurnal.canonIp(c);
        UsbDevice d = UsbPrinter.canon(c);
        Exception usbXato = null;
        if (d != null) {
            if (UsbPrinter.ippUsbInterfeys(d) != null) {
                try {
                    return "Canon USB, " + Ipp.chop(new UsbPrinter.IppUsb(c, d), job, nusxa);
                } catch (Exception e) {
                    usbXato = e;
                    Jurnal.yoz("Canon USB: " + e.getMessage());
                }
            } else {
                usbXato = new IOException("Canon USB orqali drayversiz chop etishni qo'llamaydi");
            }
        }
        if (!ip.isEmpty()) {
            return "Canon Wi-Fi, " + Ipp.chop(new Ipp.Http(ip, Jurnal.canonPort(c), Jurnal.canonYol(c)), job, nusxa);
        }
        if (usbXato != null) {
            throw new IOException(usbXato.getMessage() + ". Canon'ni Wi-Fi'ga ulang va ilovada «Canon'ni qidirish» ni bosing");
        }
        throw new IOException("Canon topilmadi (USB ham, Wi-Fi ham sozlanmagan)");
    }

    public static File oxirgiA4(Context c) {
        return new File(c.getFilesDir(), "oxirgi_a4.json");
    }

    private static void saqla(Context c, JSONObject job) {
        try (FileOutputStream o = new FileOutputStream(oxirgiA4(c))) {
            o.write(job.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    public static JSONObject oxirgiA4Oqi(Context c) throws Exception {
        File f = oxirgiA4(c);
        if (!f.exists()) return null;
        return new JSONObject(new String(Ipp.oqi(new FileInputStream(f)), StandardCharsets.UTF_8));
    }
}
