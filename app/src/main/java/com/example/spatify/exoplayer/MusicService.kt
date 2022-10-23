package com.example.spatify.exoplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.MediaBrowserServiceCompat
import com.example.spatify.exoplayer.callbacks.MusicPlaybackPreparer
import com.example.spatify.exoplayer.callbacks.MusicPlayerEventListener
import com.example.spatify.exoplayer.callbacks.MusicPlayerNotificationListener
import com.example.spatify.utils.Constants.Companion.MEDIA_ROOT_ID
import com.example.spatify.utils.Constants.Companion.SERVICE_TAG
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.upstream.DefaultDataSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject


@AndroidEntryPoint
class MusicService : MediaBrowserServiceCompat() {

    @Inject
    lateinit var dataSourceFactory: DefaultDataSource.Factory

    @Inject
    lateinit var exoPLayer: ExoPlayer

    @Inject
    lateinit var fireBaseMusicSource: FireBaseMusicSource

    private lateinit var musicNotificationManager: MusicNotificationManager

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaSessionConnector: MediaSessionConnector

    private lateinit var musicPlayerEventListener: MusicPlayerEventListener

    var isForegroundService = false

    private var isPlayerInitialized = false

    private var curPlayingSong: MediaMetadataCompat? = null

    companion object  {
        var curSongDuration = 0L
        private set
    }

    override fun onCreate() {
        serviceScope.launch {
            fireBaseMusicSource.fetchMediaData()
        }

        super.onCreate()
        val activityIntent = packageManager?.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, 0)
        }

        mediaSession = MediaSessionCompat(this, SERVICE_TAG).apply {
            setSessionActivity(activityIntent)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        musicNotificationManager = MusicNotificationManager(
            this,
            mediaSession.sessionToken,
            MusicPlayerNotificationListener(this),
        ) {
            curSongDuration = exoPLayer.duration
        }

        val musicPlaybackPreparer = MusicPlaybackPreparer(fireBaseMusicSource) {
            curPlayingSong = it
            preparerPlayer(
                fireBaseMusicSource.songs, it, true
            )
        }

        mediaSessionConnector = MediaSessionConnector(mediaSession)
        mediaSessionConnector.setQueueNavigator(MusicQueueNavigator())
        mediaSessionConnector.setPlaybackPreparer(musicPlaybackPreparer)
        mediaSessionConnector.setPlayer(exoPLayer)

        musicPlayerEventListener = MusicPlayerEventListener(this)
        exoPLayer.addListener(musicPlayerEventListener)
        musicNotificationManager.showNotification(exoPLayer)
    }


    private inner class MusicQueueNavigator : TimelineQueueNavigator(mediaSession) {
        override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
            return fireBaseMusicSource.songs[windowIndex].description
        }

    }

    private fun preparerPlayer(
        songs: List<MediaMetadataCompat>, itemToPlay: MediaMetadataCompat?, playNow: Boolean
    ) {
        val curSongIndex = if (curPlayingSong == null) 0 else songs.indexOf(itemToPlay)
        exoPLayer.prepare(fireBaseMusicSource.asMediaSource(dataSourceFactory))
        exoPLayer.seekTo(curSongIndex, 0L)
        exoPLayer.playWhenReady = playNow
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        exoPLayer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()

        exoPLayer.removeListener(musicPlayerEventListener)
        exoPLayer.release()
    }

    override fun onGetRoot(
        clientPackageName: String, clientUid: Int, rootHints: Bundle?
    ): BrowserRoot? {
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        when (parentId) {
            MEDIA_ROOT_ID -> {
                val resultsSent = fireBaseMusicSource.whenReady { isInitialized ->
                    if (isInitialized) {
                        result.sendResult(fireBaseMusicSource.asMediaItems())
                        if (!isPlayerInitialized && fireBaseMusicSource.songs.isNotEmpty()) {
                            preparerPlayer(
                                fireBaseMusicSource.songs, fireBaseMusicSource.songs[0], false
                            )
                            isPlayerInitialized = true
                        }
                    } else {
                        result.sendResult(null)
                    }
                }
                if (!resultsSent) {
                    result.detach()
                }
            }
        }
    }


}