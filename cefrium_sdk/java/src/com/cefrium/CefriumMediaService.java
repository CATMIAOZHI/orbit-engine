// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service that hosts the media-playback notification for
 * {@link CefriumMediaSession}. Keeps the notification on the
 * lock screen and the process at media-playback priority. Notification action
 * buttons post intents here (PLAY/PAUSE/STOP), which are forwarded to the active
 * {@link CefriumMediaSession}.
 *
 * <p>Consumers must declare this service in their manifest (the SDK AAR manifest
 * declares it) with `android:foregroundServiceType="mediaPlayback"`, plus the
 * FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK and POST_NOTIFICATIONS
 * permissions.
 */
public class CefriumMediaService extends Service {

    static final String ACTION_SHOW = "com.cefrium.media.SHOW";
    static final String ACTION_PLAY = "com.cefrium.media.PLAY";
    static final String ACTION_PAUSE = "com.cefrium.media.PAUSE";
    static final String ACTION_STOP = "com.cefrium.media.STOP";
    static final int NOTIFICATION_ID = 0xCEF1;

    static void show(Context context, Notification notification) {
        Intent i = new Intent(context, CefriumMediaService.class)
                .setAction(ACTION_SHOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
        // The notification itself is fetched from the active session in
        // onStartCommand to avoid passing a large Parcelable through the intent.
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, CefriumMediaService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        CefriumMediaSession session = CefriumMediaSession.current();
        String action = intent != null ? intent.getAction() : null;
        if (session == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(action)) {
            session.onTransportPlay();
        } else if (ACTION_PAUSE.equals(action)) {
            session.onTransportPause();
        } else if (ACTION_STOP.equals(action)) {
            session.onTransportStop();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = session.buildNotification();
        if (n != null) {
            startForeground(NOTIFICATION_ID, n);
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
