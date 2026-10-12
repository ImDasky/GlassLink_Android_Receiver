package com.glasslink.receiver

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Playback runs in a foreground media-session service only while appropriate. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var player: ExoPlayer? = null

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)
        val source = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, http),
            ResolvingDataSource.Resolver { spec -> ReceiverHttpContext.apply(spec) }
        )
        val decodedSource = MediaSegmentDataSource.Factory(source)
        LiveDvr.install(cacheDir, decodedSource)
        val mediaSources = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(LiveDvrDataSource.Factory(decodedSource))
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSources).build()
        exo.setAudioAttributes(AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA).build(), true)
        player = exo
        session = MediaSession.Builder(this, exo).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        LiveDvr.stop()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
