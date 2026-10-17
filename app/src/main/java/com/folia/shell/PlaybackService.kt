package com.folia.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import java.net.URL

/**
 * 锁屏 / 通知栏播放控制服务
 *
 * 原理：
 *  - WebView 里的 <audio> 无法直接被系统感知，所以由 MediaBridge 定时把
 *    「歌名 / 歌手 / 封面 / 播放状态 / 进度」推送到这里
 *  - 这里维护一个 MediaSessionCompat，系统锁屏、通知栏、耳机线控都读它
 *  - 用户点播放/暂停/上一首/下一首时，通过回调把指令发回 WebView 执行
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "folia_playback"
        const val NOTIF_ID = 1001
        const val ACTION_PLAY = "com.folia.shell.PLAY"
        const val ACTION_PAUSE = "com.folia.shell.PAUSE"
        const val ACTION_NEXT = "com.folia.shell.NEXT"
        const val ACTION_PREV = "com.folia.shell.PREV"
        const val ACTION_STOP = "com.folia.shell.STOP"

        /** WebView 侧注册的指令回调 */
        @Volatile
        var commandSink: ((String) -> Unit)? = null

        /** 单例引用，便于 MainActivity 直接推送状态 */
        @Volatile
        var instance: PlaybackService? = null
    }

    private var session: MediaSessionCompat? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 当前状态
    private var title: String = ""
    private var artist: String = ""
    private var album: String = ""
    private var coverUrl: String = ""
    private var durationMs: Long = 0
    private var positionMs: Long = 0
    private var isPlaying: Boolean = false
    private var coverBitmap: Bitmap? = null
    private var lastCoverFetched: String = ""

    /** 锁屏歌词悬浮窗 */
    private var lyricOverlay: LyricOverlay? = null
    private var lastLyricLine: String = ""
    private var lastLyricDurationGuess: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        setupSession()
        lyricOverlay = LyricOverlay(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> { commandSink?.invoke("play"); isPlaying = true; pushState() }
            ACTION_PAUSE -> { commandSink?.invoke("pause"); isPlaying = false; pushState() }
            ACTION_NEXT -> { commandSink?.invoke("next") }
            ACTION_PREV -> { commandSink?.invoke("prev") }
            ACTION_STOP -> {
                commandSink?.invoke("stop")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    private fun setupSession() {
        val s = MediaSessionCompat(this, "FoliaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    isPlaying = true
                    commandSink?.invoke("play")
                    pushState()
                }

                override fun onPause() {
                    isPlaying = false
                    commandSink?.invoke("pause")
                    pushState()
                }

                override fun onSkipToNext() {
                    commandSink?.invoke("next")
                }

                override fun onSkipToPrevious() {
                    commandSink?.invoke("prev")
                }

                override fun onStop() {
                    commandSink?.invoke("stop")
                    stopSelf()
                }

                override fun onSeekTo(pos: Long) {
                    positionMs = pos
                    commandSink?.invoke("seek:$pos")
                    pushState()
                }
            })
            isActive = true
        }
        session = s
    }

    /**
     * 由 MainActivity 调用，把 WebView 里的播放状态同步过来
     */
    fun updateState(
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newCoverUrl: String,
        newDurationMs: Long,
        newPositionMs: Long,
        newIsPlaying: Boolean,
        newLyric: String = ""
    ) {
        title = newTitle
        artist = newArtist
        album = newAlbum
        durationMs = newDurationMs
        positionMs = newPositionMs
        isPlaying = newIsPlaying

        // 封面变化时异步拉取
        if (newCoverUrl != lastCoverFetched && newCoverUrl.isNotEmpty()) {
            lastCoverFetched = newCoverUrl
            fetchCover(newCoverUrl)
        }

        // ---- 歌词悬浮窗 ----
        updateLyricOverlay(newLyric, newIsPlaying)

        pushState()
    }

    /**
     * 驱动锁屏歌词动画
     *
     * 行内进度怎么算：网页只给「当前这一句」和整首进度，
     * 没给每句的起止时间，所以这里用「上一句变成当前句的时刻」到「下一次换句的时刻」
     * 之间的时间跨度作为这一句的时长近似。换句瞬间进度归零，随时间线性推进到 1。
     */
    private fun updateLyricOverlay(line: String, playing: Boolean) {
        val ov = lyricOverlay ?: return

        if (line.isBlank()) {
            ov.hide()
            lastLyricLine = ""
            return
        }

        // 有歌词就显示（首次）
        if (!ov.isShowing()) ov.show()

        val now = System.currentTimeMillis()

        if (line != lastLyricLine) {
            // 换句：记录换句时刻，进度归零
            lastLyricLine = line
            lineChangedAt = now
            ov.updateLyric(line, 0f)
            return
        }

        // 同一句：按停留时长推进进度
        if (lineChangedAt == 0L) lineChangedAt = now
        val stay = (now - lineChangedAt).toFloat()
        // 一句歌词大约 3~5 秒唱完，这里用 4 秒作为估计时长
        val progress = (stay / 4000f).coerceIn(0f, 1f)
        ov.updateLyric(line, progress)
    }

    private var lineChangedAt: Long = 0L

    private fun fetchCover(url: String) {
        Thread {
            try {
                val bmp = URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    coverBitmap = bmp
                    mainHandler.post { pushState() }
                }
            } catch (_: Throwable) {
                // 忽略封面拉取失败
            }
        }.start()
    }

    private fun pushState() {
        session?.let { s ->
            val builder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            coverBitmap?.let {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            }
            s.setMetadata(builder.build())

            val state = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    positionMs,
                    if (isPlaying) 1.0f else 0f
                )
                .build()
            s.setPlaybackState(state)
        }

        // 更新通知
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "播放控制",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示当前播放的歌曲"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifEmpty { "Folia 音乐" })
            .setContentText(artist.ifEmpty { "" })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        coverBitmap?.let { builder.setLargeIcon(it) }

        builder.addAction(
            android.R.drawable.ic_media_previous, "上一首",
            servicePending(ACTION_PREV)
        )
        if (isPlaying) {
            builder.addAction(
                android.R.drawable.ic_media_pause, "暂停",
                servicePending(ACTION_PAUSE)
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play, "播放",
                servicePending(ACTION_PLAY)
            )
        }
        builder.addAction(
            android.R.drawable.ic_media_next, "下一首",
            servicePending(ACTION_NEXT)
        )

        return builder.build()
    }

    private fun servicePending(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        lyricOverlay?.hide()
        lyricOverlay = null
        instance = null
        session?.isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }
}