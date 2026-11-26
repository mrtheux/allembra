package br.appdaqui.designpreview;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

public final class PreviewJobs extends JobService {
    private static final int BACKUP = 81, REMINDER = 82;
    private static final long HOUR = 3600000L;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("allembra.jobs", Context.MODE_PRIVATE);
    }

    static void configure(Context context, String frequency, boolean reminders, int hours) {
        SharedPreferences prefs = prefs(context);
        prefs.edit().putString("frequency", frequency).putBoolean("reminders", reminders).putInt("hours", hours).apply();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        ComponentName service = new ComponentName(context, PreviewJobs.class);
        scheduler.cancel(BACKUP);
        if (prefs.contains("destination") && ("daily".equals(frequency) || "weekly".equals(frequency))) {
            long interval = ("daily".equals(frequency) ? 24 : 168) * HOUR;
            scheduler.schedule(new JobInfo.Builder(BACKUP, service).setPeriodic(interval).setPersisted(true).build());
        }
        scheduler.cancel(REMINDER);
        if (reminders && hours >= 1 && hours <= 168)
            scheduler.schedule(new JobInfo.Builder(REMINDER, service).setPeriodic(hours * HOUR).setPersisted(true).build());
    }

    @Override public boolean onStartJob(JobParameters job) {
        if (job.getJobId() == REMINDER) {
            notifyReminder();
            return false;
        }
        String destination = prefs(this).getString("destination", null);
        if (destination == null) return false;
        new Thread(() -> {
            boolean success;
            try {
                new VaultFiles(this).exportBackup(Uri.parse(destination));
                success = true;
            } catch (Exception error) { success = false; }
            prefs(this).edit().putBoolean("lastBackupSucceeded", success).apply();
            jobFinished(job, false);
        }, "allembra-backup").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters job) { return false; }

    private void notifyReminder() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return;
        String channel = "allembra.moments";
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(channel, "Lembretes de registro", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, DesignActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, channel)
            .setSmallIcon(br.appdaqui.designreview.r3.R.drawable.icon_notification).setColor(0xFF005A85).setContentTitle("Guarde o agora.")
            .setContentText("Há um momento de hoje que você quer reencontrar?")
            .setAutoCancel(true).setContentIntent(open).build();
        manager.notify(REMINDER, notification);
    }
}
