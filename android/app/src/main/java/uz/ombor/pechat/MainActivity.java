package uz.ombor.pechat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class MainActivity extends Activity {
    private TextView holat, jurnal;
    private EditText token, yorliq, a4, canonIp, erkinMatn, erkinSon;
    private ImageView erkinKor;
    private TextView erkinSozMatn;
    private Erkin.Soz soz;
    private final Handler h = new Handler(Looper.getMainLooper());
    private NsdManager.DiscoveryListener qidiruv;

    private final Runnable yangila = new Runnable() {
        @Override
        public void run() {
            holatniYoz();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(14);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(pad, pad, pad, pad);

        holat = new TextView(this);
        holat.setTextSize(17);
        holat.setTypeface(Typeface.DEFAULT_BOLD);
        l.addView(holat);

        SharedPreferences sp = Jurnal.sozlama(this);

        // ✍ ERKIN YORLIQ — istalgan yozuvni Xprinter'ga (QR'siz)
        soz = Erkin.Soz.oqi(this);
        l.addView(sarlavha("✍ ERKIN YORLIQ — istalgan yozuv"));
        erkinMatn = maydon(sp.getString("erkin", ""), "Masalan: 1607 (340)");
        erkinMatn.setSingleLine(false);
        erkinMatn.setMinLines(2);
        erkinMatn.setTextSize(20);
        l.addView(erkinMatn);
        l.addView(sarlavha("Nechta chiqsin?"));
        erkinSon = maydon("1", "1");
        erkinSon.setInputType(InputType.TYPE_CLASS_NUMBER);
        erkinSon.setTextSize(20);
        l.addView(erkinSon);
        erkinKor = new ImageView(this);
        erkinKor.setAdjustViewBounds(true);
        erkinKor.setBackgroundColor(0xFF888888);
        erkinKor.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(8);
        erkinKor.setLayoutParams(ilp);
        l.addView(erkinKor);
        erkinSozMatn = new TextView(this);
        erkinSozMatn.setTextSize(15);
        erkinSozMatn.setPadding(0, dp(6), 0, 0);
        l.addView(erkinSozMatn);
        LinearLayout q1 = qator();
        q1.addView(kichik("📏 Qog'oz o'lchami", v -> olchamTanla()));
        q1.addView(kichik("↔️ / ↕️ Yo'nalish", v -> { soz.vertikal = !soz.vertikal; sozYangila(); }));
        l.addView(q1);
        LinearLayout q2 = qator();
        q2.addView(kichik("↔️ −1mm", v -> { soz.xOff = Math.max(-15, soz.xOff - 1); sozYangila(); }));
        q2.addView(kichik("↔️ +1mm", v -> { soz.xOff = Math.min(15, soz.xOff + 1); sozYangila(); }));
        q2.addView(kichik("↕️ −1mm", v -> { soz.yOff = Math.max(-15, soz.yOff - 1); sozYangila(); }));
        q2.addView(kichik("↕️ +1mm", v -> { soz.yOff = Math.min(15, soz.yOff + 1); sozYangila(); }));
        l.addView(q2);
        LinearLayout q3 = qator();
        q3.addView(kichik("🎯 Kalibrlash", v -> fonda(() -> {
            try {
                Chop.yorliq(this, Erkin.KALIBR);
                Jurnal.yoz("🎯 Kalibrlash yuborildi (printer 1–3 bo'sh yorliq o'tkazadi)");
            } catch (Exception e) {
                Jurnal.yoz("Kalibrlash: " + e.getMessage());
            }
        })));
        l.addView(q3);
        sozYangila();
        erkinMatn.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence a, int b1, int c, int d) {}
            @Override public void onTextChanged(CharSequence a, int b1, int c, int d) {}
            @Override public void afterTextChanged(Editable e) { erkinKor.setImageBitmap(Erkin.korinish(e.toString(), soz)); }
        });
        l.addView(tugma("🖨 YORLIQNI CHOP ETISH", v -> erkinChop()));
        l.addView(tugma("🪪 Shu printerning pasportini chop etish (QR)", v -> pasportChop()));
        l.addView(tugma("🖨 Printerlar ro'yxati / skanerlab qo'shish", v -> royxat()));
        l.addView(tugma("🔗 Botdagi yorliqlarim shu telefondan chiqsin", v -> botgaUlash()));
        l.addView(sarlavha(" "));
        l.addView(sarlavha("1. Pechat bot tokeni (@ombor_pechat_bot)"));
        token = maydon(sp.getString("token", ""), "123456:ABC…");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        l.addView(token);
        l.addView(sarlavha("2. Shu telefondagi printer nomi (har telefonda boshqa: kirim, ombor2 …)"));
        yorliq = maydon(sp.getString("yorliq", "kirim"), "kirim");
        l.addView(yorliq);
        l.addView(sarlavha("A4 printer nomi (botdagi; odatda a4)"));
        a4 = maydon(sp.getString("a4", "a4"), "a4");
        l.addView(a4);
        l.addView(sarlavha("Canon IP manzili (Wi-Fi; «Canon'ni qidirish» o'zi topadi)"));
        canonIp = maydon(sp.getString("canon_ip", ""), "192.168.1.50");
        l.addView(canonIp);

        l.addView(tugma("▶️ SAQLASH VA ISHGA TUSHIRISH", v -> {
            saqla();
            sp.edit().putBoolean("yoqilgan", true).apply();
            ruxsatlar();
            PechatService.boshla(this);
            Jurnal.yoz("Xizmat ishga tushirildi");
        }));
        l.addView(tugma("⏹ To'xtatish", v -> {
            sp.edit().putBoolean("yoqilgan", false).apply();
            PechatService.toxtat(this);
        }));
        l.addView(tugma("🏷 Xprinter sinov yorlig'i", v -> fonda(() -> {
            try {
                Jurnal.yoz("Sinov yorlig'i yuborildi: " + Chop.yorliq(this, Chop.SINOV_TSPL));
            } catch (Exception e) {
                Jurnal.yoz("Xprinter sinov: " + e.getMessage());
            }
        })));
        l.addView(tugma("📄 Canon sinov varag'i (A4)", v -> {
            saqla();
            fonda(() -> {
                try {
                    Jurnal.yoz("A4 sinov yuborildi: " + Chop.a4(this, new JSONObject(Chop.SINOV_A4), 1));
                } catch (Exception e) {
                    Jurnal.yoz("Canon sinov: " + e.getMessage());
                }
            });
        }));
        l.addView(tugma("🔎 Canon'ni Wi-Fi'dan qidirish", v -> canonQidir()));
        l.addView(tugma("🖨 Oxirgi A4 ni qo'lda chop etish (telefon oynasi)", v -> qolda()));
        l.addView(tugma("🔋 Batareya cheklovini o'chirish (fonda uxlamasin)", v -> batareya()));
        l.addView(tugma("⬆️ Yangilanishni tekshirish (hozir: " + Yangilash.joriyNom(this) + ")", v -> Yangilash.soradi(this, false)));

        l.addView(sarlavha("Jurnal"));
        jurnal = new TextView(this);
        jurnal.setTextSize(12);
        jurnal.setTypeface(Typeface.MONOSPACE);
        l.addView(jurnal);

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);
        usbIntent(getIntent());
        Yangilash.soradi(this, !getIntent().getBooleanExtra("yangilash", false));
        if (sp.getBoolean("yoqilgan", false) && !Jurnal.token(this).isEmpty() && !PechatService.ishlayapti) {
            PechatService.boshla(this);
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        usbIntent(i);
    }

    private void usbIntent(Intent i) {
        if (i != null && i.getBooleanExtra("yangilash", false) && i != getIntent()) Yangilash.soradi(this, false);
        if (i != null && "android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(i.getAction())) {
            Jurnal.yoz("USB printer ulandi");
            if (Jurnal.sozlama(this).getBoolean("yoqilgan", false) && !Jurnal.token(this).isEmpty()) {
                PechatService.boshla(this);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(yangila);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(yangila);
    }

    private void holatniYoz() {
        StringBuilder s = new StringBuilder();
        s.append(PechatService.ishlayapti ? "🟢 " : "🔴 ").append(Jurnal.holat).append('\n');
        int n = 0;
        for (Map.Entry<String, UsbDevice> e : UsbPrinter.um(this).getDeviceList().entrySet()) {
            UsbDevice d = e.getValue();
            boolean c = UsbPrinter.canonmi(d);
            s.append("🔌 ").append(UsbPrinter.nom(d)).append(c ? " (Canon, A4)" : " (yorliq)");
            if (!UsbPrinter.um(this).hasPermission(d)) s.append(" — ruxsat yo'q");
            if (c && UsbPrinter.ippUsbInterfeys(d) == null) s.append(" — USB'da drayversiz emas, Wi-Fi kerak");
            s.append('\n');
            n++;
        }
        if (n == 0) s.append("🔌 USB printer ulanmagan\n");
        if (!Jurnal.canonIp(this).isEmpty()) s.append("📶 Canon Wi-Fi: ").append(Jurnal.canonIp(this)).append('\n');
        holat.setText(s.toString().trim());
        jurnal.setText(Jurnal.matn());
    }

    private void saqla() {
        Jurnal.sozlama(this).edit()
                .putString("token", token.getText().toString().trim())
                .putString("yorliq", yorliq.getText().toString().trim())
                .putString("a4", a4.getText().toString().trim().isEmpty() ? "a4" : a4.getText().toString().trim())
                .putString("canon_ip", canonIp.getText().toString().trim())
                .apply();
        // Ulangan USB printerlarga ruxsat so'raymiz
        for (UsbDevice d : UsbPrinter.um(this).getDeviceList().values()) UsbPrinter.ruxsat(this, d);
        Toast.makeText(this, "Saqlandi", Toast.LENGTH_SHORT).show();
    }

    private void ruxsatlar() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void batareya() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Allaqachon o'chirilgan ✓", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void canonQidir() {
        NsdManager nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (qidiruv != null) {
            try { nsd.stopServiceDiscovery(qidiruv); } catch (Exception ignored) {}
        }
        Jurnal.yoz("Wi-Fi'da printer qidirilmoqda… (telefon va Canon bitta Wi-Fi'da bo'lsin)");
        final boolean[] topildi = {false};
        qidiruv = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { Jurnal.yoz("Qidiruv boshlanmadi: " + e); }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceLost(NsdServiceInfo s) {}

            @Override
            public void onServiceFound(NsdServiceInfo s) {
                Jurnal.yoz("Topildi: " + s.getServiceName());
                synchronized (topildi) {
                    if (topildi[0]) return;
                    if (!s.getServiceName().toLowerCase().contains("canon")) return;
                    topildi[0] = true;
                }
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo si, int e) {
                        Jurnal.yoz("Manzil aniqlanmadi: " + e);
                        synchronized (topildi) { topildi[0] = false; }
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo si) {
                        String ip = si.getHost().getHostAddress();
                        String rp = "ipp/print";
                        byte[] r = si.getAttributes().get("rp");
                        if (r != null && r.length > 0) rp = new String(r, StandardCharsets.UTF_8);
                        Jurnal.sozlama(MainActivity.this).edit().putString("canon_ip", ip)
                                .putInt("canon_port", si.getPort()).putString("canon_yol", "/" + rp).apply();
                        Jurnal.yoz("✅ Canon: " + ip + ":" + si.getPort() + "/" + rp + " — saqlandi");
                        h.post(() -> canonIp.setText(ip));
                        try { nsd.stopServiceDiscovery(qidiruv); } catch (Exception ignored) {}
                    }
                });
            }
        };
        nsd.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, qidiruv);
        h.postDelayed(() -> {
            synchronized (topildi) {
                if (!topildi[0]) Jurnal.yoz("Canon topilmadi. Canon Wi-Fi'ga ulanganmi? IP'ni qo'lda yozsa ham bo'ladi.");
            }
        }, 15000);
    }

    /** Telefonning o'z chop etish oynasi (masalan Canon PRINT / Mopria plagini orqali). */
    private void qolda() {
        JSONObject job;
        try { job = Chop.oxirgiA4Oqi(this); } catch (Exception e) { job = null; }
        if (job == null) {
            try { job = new JSONObject(Chop.SINOV_A4); } catch (Exception e) { return; }
            Toast.makeText(this, "Hali A4 hujjat kelmagan — sinov varag'i ochiladi", Toast.LENGTH_LONG).show();
        }
        final byte[] pdf;
        try { pdf = Raster.pdf(job, Math.max(1, job.optInt("nusxa", 1))); } catch (Exception e) {
            Jurnal.yoz("PDF tayyorlanmadi: " + e.getMessage());
            return;
        }
        boolean land = Raster.sahifa(job)[2] > 0;
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        PrintAttributes at = new PrintAttributes.Builder()
                .setMediaSize(land ? PrintAttributes.MediaSize.ISO_A4.asLandscape() : PrintAttributes.MediaSize.ISO_A4)
                .build();
        pm.print(job.optString("nom", "Hujjat"), new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes o, PrintAttributes n, CancellationSignal cs, LayoutResultCallback cb, Bundle ex) {
                cb.onLayoutFinished(new PrintDocumentInfo.Builder("hujjat.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true);
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor fd, CancellationSignal cs, WriteResultCallback cb) {
                try (FileOutputStream o = new FileOutputStream(fd.getFileDescriptor())) {
                    o.write(pdf);
                    cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (Exception e) {
                    cb.onWriteFailed(e.getMessage());
                }
            }
        }, at);
    }

    private void erkinChop() {
        String m = erkinMatn.getText().toString().trim();
        if (m.isEmpty()) {
            Toast.makeText(this, "Avval yozuvni yozing", Toast.LENGTH_SHORT).show();
            return;
        }
        int n;
        try { n = Integer.parseInt(erkinSon.getText().toString().trim()); } catch (Exception e) { n = 1; }
        if (n < 1) n = 1;
        if (n > 500) n = 500;
        final int son = n;
        Jurnal.sozlama(this).edit().putString("erkin", m).apply();
        erkinYubor(m, son, false);
    }

    private void erkinYubor(String m, int son, boolean ramka) {
        final Erkin.Soz z = soz;
        tanla(nom -> fonda(() -> {
            try {
                String nat = Printerlar.yubor(this, nom, Erkin.tspl(m, son, z, false));
                Jurnal.yoz("✍ Erkin yorliq: «" + m.replace('\n', ' ') + "» × " + son + " (" + z.olcham() + ") → " + nat);
                h.post(() -> Toast.makeText(this, "✅ " + son + " ta → " + nom, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Jurnal.yoz("Erkin yorliq: " + e.getMessage());
                h.post(() -> Toast.makeText(this, "❌ " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }));
    }

    /** Printer tanlash: bittadan ko'p bo'lsa ro'yxat + «📷 Printerni skanerlash». */
    interface Tanlandi { void ok(String nom); }

    private void tanla(Tanlandi t) {
        java.util.List<String> l = Printerlar.hammasi(this);
        if (l.size() <= 1) { t.ok(Printerlar.ozim(this)); return; }
        String[] r = new String[l.size() + 1];
        for (int i = 0; i < l.size(); i++) r[i] = "🖨 " + l.get(i) + (i == 0 ? "  (shu telefon)" : "");
        r[l.size()] = "📷 Printerni skanerlang (pasportdagi QR)";
        new AlertDialog.Builder(this).setTitle("Qaysi printerga?").setItems(r, (d, i) -> {
            if (i < l.size()) t.ok(l.get(i));
            else skanerla(nom -> { Printerlar.qosh(this, nom); t.ok(nom); });
        }).setNegativeButton("Bekor", null).show();
    }

    private void skanerla(Tanlandi t) {
        try {
            com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions o =
                    new com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build();
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, o).startScan()
                    .addOnSuccessListener(bc -> {
                        String nom = Printerlar.pasportdan(bc.getRawValue());
                        if (nom == null) {
                            Toast.makeText(this, "Bu printer pasporti emas", Toast.LENGTH_LONG).show();
                            return;
                        }
                        Jurnal.yoz("📷 Printer skanerlandi: " + nom);
                        t.ok(nom);
                    })
                    .addOnFailureListener(e -> Toast.makeText(this, "Skaner ochilmadi: " + e.getMessage()
                            + ". Ro'yxatdan tanlang.", Toast.LENGTH_LONG).show());
        } catch (Throwable e) {
            Toast.makeText(this, "Bu telefonda skaner yo'q — ro'yxatdan tanlang", Toast.LENGTH_LONG).show();
        }
    }

    private void pasportChop() {
        saqla();
        final String nom = Printerlar.ozim(this);
        final Erkin.Soz z = soz;
        fonda(() -> {
            try {
                Chop.yorliq(this, Printerlar.pasport(this, nom, z));
                Jurnal.yoz("🪪 Pasport chop etildi: " + nom + " · QR: " + Printerlar.pasportQr(this, nom));
            } catch (Exception e) {
                Jurnal.yoz("Pasport: " + e.getMessage());
                h.post(() -> Toast.makeText(this, "❌ " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Botni «start=prn_<nom>» bilan ochadi: shu telefondagi xodimning yorliqlari shu printerga bog'lanadi. */
    private void botgaUlash() {
        saqla();
        String b = Printerlar.botUser(this);
        if (b.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("Bot nomi hali noma'lum. Botdan bitta yorliq chiqaring yoki "
                    + "«🪪 pasport» ni chop etib, botdagi 📷 Skanerlash bilan skanerlang.").setPositiveButton("OK", null).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/" + b + "?start=prn_" + Printerlar.ozim(this))));
        } catch (Exception e) {
            Toast.makeText(this, "Telegram ochilmadi", Toast.LENGTH_LONG).show();
        }
    }

    private void royxat() {
        java.util.List<String> l = Printerlar.hammasi(this);
        String[] r = new String[l.size() + 1];
        for (int i = 0; i < l.size(); i++) r[i] = "🖨 " + l.get(i) + (i == 0 ? "  (shu telefon)" : "  — o'chirish uchun bosing");
        r[l.size()] = "📷 Skanerlab qo'shish";
        new AlertDialog.Builder(this).setTitle("Printerlar").setItems(r, (d, i) -> {
            if (i == l.size()) skanerla(nom -> { Printerlar.qosh(this, nom); Toast.makeText(this, "✅ " + nom, Toast.LENGTH_SHORT).show(); });
            else if (i > 0) new AlertDialog.Builder(this).setMessage("«" + l.get(i) + "» ro'yxatdan o'chirilsinmi?")
                    .setPositiveButton("O'chirish", (d2, w) -> Printerlar.ochir(this, l.get(i)))
                    .setNegativeButton("Yo'q", null).show();
        }).setNegativeButton("Yopish", null).show();
    }

    private void sozYangila() {
        soz.saqla(this);
        erkinSozMatn.setText("📏 Qog'oz: " + soz.olcham() + " mm · " + (soz.vertikal ? "↕️ Vertikal" : "↔️ Gorizontal")
                + " · siljish " + Erkin.fmt(soz.xOff) + "/" + Erkin.fmt(soz.yOff) + " mm");
        erkinKor.setImageBitmap(Erkin.korinish(erkinMatn.getText().toString(), soz));
    }

    private void olchamTanla() {
        String[] r = new String[Erkin.OLCHAMLAR.length + 1];
        for (int i = 0; i < Erkin.OLCHAMLAR.length; i++) {
            r[i] = (Erkin.OLCHAMLAR[i].equals(soz.olcham()) ? "✅ " : "") + Erkin.OLCHAMLAR[i] + " mm";
        }
        r[r.length - 1] = "✏️ Boshqa o'lcham (yozaman)";
        new AlertDialog.Builder(this).setTitle("Yorliq o'lchami (eni x bo'yi)").setItems(r, (d, i) -> {
            if (i < Erkin.OLCHAMLAR.length) {
                float[] wh = Erkin.parse(Erkin.OLCHAMLAR[i]);
                soz.w = wh[0];
                soz.h = wh[1];
                sozYangila();
                return;
            }
            EditText e = maydon("", "60x40");
            new AlertDialog.Builder(this).setTitle("O'lcham: ENI x BO'YI (mm)").setView(e)
                    .setPositiveButton("OK", (d2, w) -> {
                        float[] wh = Erkin.parse(e.getText().toString());
                        if (wh == null) {
                            Toast.makeText(this, "Masalan: 60x40", Toast.LENGTH_LONG).show();
                            return;
                        }
                        soz.w = wh[0];
                        soz.h = wh[1];
                        sozYangila();
                    }).setNegativeButton("Bekor", null).show();
        }).show();
    }

    private LinearLayout qator() {
        LinearLayout q = new LinearLayout(this);
        q.setOrientation(LinearLayout.HORIZONTAL);
        return q;
    }

    private Button kichik(String s, View.OnClickListener cl) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(cl);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void fonda(Runnable r) {
        new Thread(r).start();
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private TextView sarlavha(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(12), 0, dp(2));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText maydon(String v, String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(v);
        e.setHint(hint);
        return e;
    }

    private Button tugma(String s, View.OnClickListener cl) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(cl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }
}
