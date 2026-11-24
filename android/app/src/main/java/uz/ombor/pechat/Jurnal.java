package uz.ombor.pechat;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Ilova jurnali (oxirgi qatorlar) va sozlamalar. */
public final class Jurnal {
    private static final List<String> qatorlar = new ArrayList<>();
    private static final SimpleDateFormat VAQT = new SimpleDateFormat("HH:mm:ss", Locale.US);
    public static volatile String holat = "To'xtatilgan";

    private Jurnal() {}

    public static synchronized void yoz(String s) {
        qatorlar.add(0, VAQT.format(new Date()) + "  " + s);
        while (qatorlar.size() > 150) qatorlar.remove(qatorlar.size() - 1);
        android.util.Log.i("OmborPechat", s);
    }

    public static synchronized String matn() {
        StringBuilder b = new StringBuilder();
        for (String q : qatorlar) b.append(q).append('\n');
        return b.toString();
    }

    public static SharedPreferences sozlama(Context c) {
        return c.getSharedPreferences("pechat", Context.MODE_PRIVATE);
    }

    public static String token(Context c) { return sozlama(c).getString("token", "").trim(); }
    public static String yorliqPrinter(Context c) { return sozlama(c).getString("yorliq", "kirim").trim().toLowerCase(Locale.ROOT); }
    public static String a4Printer(Context c) { return sozlama(c).getString("a4", "a4").trim().toLowerCase(Locale.ROOT); }
    public static String canonIp(Context c) { return sozlama(c).getString("canon_ip", "").trim(); }
    public static int canonPort(Context c) { return sozlama(c).getInt("canon_port", 631); }
    public static String canonYol(Context c) { return sozlama(c).getString("canon_yol", "/ipp/print"); }
}
