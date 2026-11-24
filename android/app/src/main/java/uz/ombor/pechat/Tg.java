package uz.ombor.pechat;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Telegram Bot API (pechat boti). */
public final class Tg {
    public static class Ruxsatsiz extends IOException { Ruxsatsiz(String m) { super(m); } }
    public static class Konflikt extends IOException { Konflikt(String m) { super(m); } }

    private final String token;

    public Tg(String token) { this.token = token; }

    public Object call(String method, JSONObject params, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.telegram.org/bot" + token + "/" + method).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(timeoutMs);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        byte[] body = params.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String s = new String(Ipp.oqi(in), StandardCharsets.UTF_8);
        c.disconnect();
        if (code == 401 || code == 404) throw new Ruxsatsiz("Pechat bot tokeni noto'g'ri");
        if (code == 409) throw new Konflikt("Bu token bilan boshqa qurilma ham ishlayapti (boshqa telefon yoki noutbuk). Har qurilmaga alohida pechat boti oching");
        JSONObject j = new JSONObject(s.isEmpty() ? "{}" : s);
        if (!j.optBoolean("ok")) throw new IOException(j.optString("description", "Telegram xatosi " + code));
        return j.opt("result");
    }

    public byte[] yukla(String fileId) throws Exception {
        JSONObject f = (JSONObject) call("getFile", new JSONObject().put("file_id", fileId), 30000);
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.telegram.org/file/bot" + token + "/" + f.getString("file_path")).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        byte[] b = Ipp.oqi(c.getInputStream());
        c.disconnect();
        return b;
    }

    /** Kanalga fayl (katta yorliq) yuborish. */
    public void hujjat(long chat, String caption, String fname, byte[] data) throws Exception {
        String bnd = "----ombor" + System.nanoTime();
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.telegram.org/bot" + token + "/sendDocument").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bnd);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        String[][] f = {{"chat_id", String.valueOf(chat)}, {"caption", caption}, {"disable_notification", "true"}};
        for (String[] kv : f) {
            b.write(("--" + bnd + "\r\nContent-Disposition: form-data; name=\"" + kv[0] + "\"\r\n\r\n" + kv[1] + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        b.write(("--" + bnd + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"" + fname
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(data);
        b.write(("\r\n--" + bnd + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] body = b.toByteArray();
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
        int code = c.getResponseCode();
        String s = new String(Ipp.oqi(code < 400 ? c.getInputStream() : c.getErrorStream()), StandardCharsets.UTF_8);
        c.disconnect();
        JSONObject j = new JSONObject(s.isEmpty() ? "{}" : s);
        if (!j.optBoolean("ok")) throw new IOException(j.optString("description", "Telegram xatosi " + code));
    }

    public void yoz(long chat, String text) {
        try {
            call("sendMessage", new JSONObject().put("chat_id", chat).put("text", text).put("disable_notification", true), 20000);
        } catch (Exception e) {
            Jurnal.yoz("Kanalga javob yozilmadi (pechat botini kanalga ADMIN qiling): " + e.getMessage());
        }
    }
}
