package br.appdaqui.capsula;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

public final class Reminder extends BroadcastReceiver {
    static final String ACTION = "br.appdaqui.capsula.REMINDER";
    static void schedule(Context c) {
        int hours = c.getSharedPreferences("settings", 0).getInt("hours", 0);
        PendingIntent reminder = PendingIntent.getBroadcast(c, 1, new Intent(c, Reminder.class).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        alarms.cancel(reminder);
        if (hours > 0) {
            long delay = hours * 3_600_000L;
            alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, delay, reminder);
        }
    }
    @Override public void onReceive(Context c, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) { schedule(c); return; }
        if (!ACTION.equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("registro", "Lembretes de registro", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent launch = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class).putExtra("record", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(7, new Notification.Builder(c,"registro")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Um momento para guardar")
            .setContentText("Registre algo de hoje para abrir no futuro.").setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(launch).setAutoCancel(true).build());
    }
}
