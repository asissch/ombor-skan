package uz.ombor.pechat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if ((Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction()))
                && Jurnal.sozlama(c).getBoolean("yoqilgan", false) && !Jurnal.token(c).isEmpty()) {
            PechatService.boshla(c);
        }
    }
}
