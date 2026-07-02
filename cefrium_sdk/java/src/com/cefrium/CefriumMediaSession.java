// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;

import org.chromium.content_public.browser.MediaSession;
import org.chromium.content_public.browser.MediaSessionObserver;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.WebContentsObserver;
import org.chromium.services.media_session.MediaMetadata;

/**
 * Bridges the page's {@code navigator.mediaSession} (Chromium MediaSession) to an
 * Android {@link MediaSessionCompat} and a lock-screen / shade media notification.
 * Created per browser; observes playback state + metadata and
 * forwards transport controls back to the page.
 *
 * <p>The notification is hosted by {@link CefriumMediaService} (foreground).
 */
public class CefriumMediaSession {

    private static final String CHANNEL_ID = "cefrium_media";

    // The active session whose transport buttons CefriumMediaService routes to.
    private static volatile CefriumMediaSession sCurrent;

    static CefriumMediaSession current() {
        return sCurrent;
    }

    private final Context mAppContext;
    private final MediaSession mChromiumSession;
    private final MediaSessionCompat mCompat;
    private final MediaSessionObserver mObserver;
    // Reliable "media is playing" signal (independent of the racy is_controllable).
    // Drives the foreground service so background audio survives (Android kills an
    // unprotected background process in ~1 min).
    private final WebContentsObserver mWebContentsObserver;
    private final java.util.Set<Integer> mPlayingIds = new java.util.HashSet<>();

    private String mTitle = "";
    private String mArtist = "";
    private boolean mControllable;
    private boolean mPaused = true;

    CefriumMediaSession(Context context, WebContents webContents) {
        mAppContext = context.getApplicationContext();
        mChromiumSession = MediaSession.fromWebContents(webContents);
        ensureChannel();

        mCompat = new MediaSessionCompat(mAppContext, "Cefrium");
        mCompat.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { onTransportPlay(); }
            @Override public void onPause() { onTransportPause(); }
            @Override public void onStop() { onTransportStop(); }
            @Override public void onSeekTo(long pos) {
                if (mChromiumSession != null) mChromiumSession.seekTo(pos);
            }
        });

        // MediaSession.fromWebContents() returns a live session even before any
        // media plays: the Android CEF build patches the JNI to use
        // MediaSessionImpl::Get (create-or-return) instead of GetIfExists (which
        // was null until playback started, so an observer attached here at page
        // load never bound and the notification never appeared). See patch
        // android/media_session_create_for_java_observer. The null-guard stays
        // defensive -- MediaSessionObserver's ctor calls session.addObserver()
        // and would NPE on null; mObserver is null-checked at every use.
        mObserver = mChromiumSession == null ? null : new MediaSessionObserver(mChromiumSession) {
            @Override
            public void mediaSessionStateChanged(boolean isControllable, boolean isSuspended) {
                mControllable = isControllable;
                mPaused = isSuspended;
                if (isControllable) {
                    sCurrent = CefriumMediaSession.this;
                    mCompat.setActive(true);
                    pushState();
                    CefriumMediaService.show(mAppContext, buildNotification());
                } else if (mPlayingIds.isEmpty()) {
                    // Only tear down when nothing is actually playing. is_controllable
                    // flickers false for media documents mid-playback; the foreground
                    // service must stay up (background audio) while media is audible.
                    hide();
                }
            }

            @Override
            public void mediaSessionMetadataChanged(MediaMetadata metadata) {
                mTitle = metadata != null && metadata.getTitle() != null
                        ? metadata.getTitle() : "";
                mArtist = metadata != null && metadata.getArtist() != null
                        ? metadata.getArtist() : "";
                pushMetadata();
                if (mControllable) {
                    CefriumMediaService.show(mAppContext, buildNotification());
                }
            }

            @Override
            public void mediaSessionDestroyed() {
                if (mPlayingIds.isEmpty()) hide();
            }
        };

        // Foreground-service lifecycle driven by ACTUAL playback (the tab-audio
        // signal), which is reliable where is_controllable is not. Started while
        // the app is foreground (media begins on-screen), so it keeps the process
        // alive after the user backgrounds it -- background audio like Brave.
        mWebContentsObserver = new WebContentsObserver(webContents) {
            @Override
            public void mediaStartedPlaying(int id, boolean hasAudio, boolean hasVideo) {
                if (!hasAudio) return;  // muted/silent media needs no background protection
                mPlayingIds.add(id);
                sCurrent = CefriumMediaSession.this;
                mCompat.setActive(true);
                mPaused = false;
                pushState();
                CefriumMediaService.show(mAppContext, buildNotification());
            }

            @Override
            public void mediaStoppedPlaying(int id) {
                mPlayingIds.remove(id);
                if (mPlayingIds.isEmpty() && !mControllable) hide();
            }

            @Override
            public void webContentsDestroyed() {
                mPlayingIds.clear();
                hide();
            }
        };
    }

    // ── Transport (notification / lock screen → page) ──

    void onTransportPlay() {
        if (mChromiumSession != null) mChromiumSession.resume();
    }

    void onTransportPause() {
        if (mChromiumSession != null) mChromiumSession.suspend();
    }

    void onTransportStop() {
        if (mChromiumSession != null) mChromiumSession.stop();
        hide();
    }

    void destroy() {
        if (mObserver != null) mObserver.stopObserving();
        mWebContentsObserver.observe(null);
        mPlayingIds.clear();
        hide();
        mCompat.release();
    }

    private void hide() {
        mCompat.setActive(false);
        if (sCurrent == this) sCurrent = null;
        CefriumMediaService.stop(mAppContext);
    }

    /**
     * Host-initiated: dismiss the media notification. Used by
     * {@link CefriumBrowser#stopMediaSession()} when a host wants the tray
     * notification gone (e.g. after navigating away from a video screen).
     */
    void hideNotification() {
        hide();
    }

    // ── MediaSessionCompat state ──

    private void pushMetadata() {
        mCompat.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, mTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, mArtist)
                .build());
    }

    private void pushState() {
        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_STOP
                | PlaybackStateCompat.ACTION_SEEK_TO;
        int state = mPaused ? PlaybackStateCompat.STATE_PAUSED
                            : PlaybackStateCompat.STATE_PLAYING;
        mCompat.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build());
    }

    // ── Notification ──

    Notification buildNotification() {
        pushState();
        PendingIntent playPause = service(mPaused
                ? CefriumMediaService.ACTION_PLAY : CefriumMediaService.ACTION_PAUSE);
        NotificationCompat.Builder b =
                new NotificationCompat.Builder(mAppContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle(mTitle.isEmpty() ? "Media" : mTitle)
                        .setContentText(mArtist)
                        .setOngoing(!mPaused)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .addAction(mPaused ? android.R.drawable.ic_media_play
                                           : android.R.drawable.ic_media_pause,
                                mPaused ? "Play" : "Pause", playPause)
                        .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                                "Stop", service(CefriumMediaService.ACTION_STOP))
                        .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                                .setMediaSession(mCompat.getSessionToken())
                                .setShowActionsInCompactView(0, 1));
        return b.build();
    }

    private PendingIntent service(String action) {
        Intent i = new Intent(mAppContext, CefriumMediaService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(mAppContext, action.hashCode(), i, flags);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    mAppContext.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "Media playback", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }
}
