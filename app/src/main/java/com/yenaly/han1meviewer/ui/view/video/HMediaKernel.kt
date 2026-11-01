package com.yenaly.han1meviewer.ui.view.video

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import cn.jzvd.JZMediaInterface
import cn.jzvd.JZMediaSystem
import cn.jzvd.Jzvd
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.logic.network.HProxySelector
import com.yenaly.han1meviewer.util.AnimeShaders
import com.yenaly.han1meviewer.util.AnimeShaders.getCert
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.absoluteValue

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/04/21 021 16:57
 */
sealed interface HMediaKernel {
    enum class Type(val clazz: Class<out JZMediaInterface>) {
        MediaPlayer(SystemMediaKernel::class.java),
        ExoPlayer(ExoMediaKernel::class.java),
        MpvPlayer(MpvMediaKernel::class.java);

        companion object {
            fun fromString(name: String): Type {
                return when (name) {
                    MediaPlayer.name -> MediaPlayer
                    ExoPlayer.name -> ExoPlayer
                    MpvPlayer.name -> MpvPlayer
                    else -> ExoPlayer
                }
            }
        }
    }
}

class ExoMediaKernel(jzvd: Jzvd) : JZMediaInterface(jzvd), Player.Listener, HMediaKernel {
    private var isActuallyPlaying = false
    private var lastBufferedPercent = -1
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            isActuallyPlaying = isPlaying
        }
        override fun onIsLoadingChanged(isLoading: Boolean) {
            val per = _exoPlayer?.bufferedPercentage ?: return
            if (per != lastBufferedPercent) {
                lastBufferedPercent = per
                jzvd.setBufferProgress(per)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            // 这里也可以加上更新，确保播放器状态变更时刷新 UI
            val per = _exoPlayer?.bufferedPercentage ?: return
            jzvd.setBufferProgress(per)
        }
    }

    companion object {
        const val TAG = "ExoMediaKernel"
    }

    private var _exoPlayer: ExoPlayer? = null

    /**
     * 尽量少用，用了之后容易出bug
     */
    private val exoPlayer get() = _exoPlayer!!

    private var callback: Runnable? = null
    private var prevSeek = 0L

    var videoRealWidth: Int = 0
        private set
    var videoRealHeight: Int = 0
        private set
    @OptIn(UnstableApi::class)
    override fun prepare() {
        if (_exoPlayer != null) {
            Log.w(TAG, "prepare called, but player already exists.")
            return // 防止误调用
        }
        Log.i(TAG, "prepare")
        val context = jzvd.context

        release()
        mMediaHandlerThread = HandlerThread("JZVD")
        mMediaHandlerThread.start()
        mMediaHandler = Handler(mMediaHandlerThread.looper)
        handler = Handler(Looper.getMainLooper())
        mMediaHandler?.post {
            val videoTrackSelectionFactory = AdaptiveTrackSelection.Factory()
            val trackSelector = DefaultTrackSelector(context, videoTrackSelectionFactory)

            val loadControl: LoadControl = DefaultLoadControl.Builder()
                // .setBufferDurationsMs(360000, 600000, 1000, 5000)
                // .setPrioritizeTimeOverSizeThresholds(false)
                // .setTargetBufferBytes(C.LENGTH_UNSET)
                .build()


            val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
            // 2. Create the player
            val renderersFactory = DefaultRenderersFactory(context)
            _exoPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
                .build().apply {
                    addListener(playerListener)
                }

            // Produces DataSource instances through which media data is loaded.
            val dataSourceFactory = DefaultDataSource.Factory(
                context,
                DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(jzvd.jzDataSource.headerMap)
            )

            val currUrl = jzvd.jzDataSource.currentUrl.toString()
            val videoSource = if (currUrl.contains(".m3u8")) {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(currUrl))
            } else {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(currUrl))
            }

            Log.i(TAG, "URL Link = $currUrl")

            exoPlayer.addListener(this)

            val isLoop = jzvd.jzDataSource.looping
            if (isLoop) {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            } else {
                exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
            }
            exoPlayer.setMediaSource(videoSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
//            callback = OnBufferingUpdate()

            val surfaceTexture = jzvd.textureView?.surfaceTexture
            if (surfaceTexture == null) {
                Log.e(TAG, "❌ surfaceTexture is null, video surface not set")
            }
            surfaceTexture?.let { exoPlayer.setVideoSurface(Surface(it)) }
        }
    }

    override fun start() {
        mMediaHandler?.post {
            _exoPlayer?.playWhenReady = true
        }
    }

    fun setLooping(looping: Boolean) {
        mMediaHandler?.post {
            _exoPlayer?.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        val realWidth = videoSize.width * videoSize.pixelWidthHeightRatio
        val realHeight = videoSize.height
        videoRealWidth = realWidth.toInt()
        videoRealHeight = realHeight
        Log.i("JZVD-onVideoSizeChanged","realWidth:$realWidth,realHeight:$realHeight")
        handler.post {
            jzvd.onVideoSizeChanged(realWidth.toInt(), realHeight)
        }
        val ratio = realWidth / realHeight // > 1 橫屏， < 1 竖屏
        if (ratio > 1) {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onRenderedFirstFrame() {
        Log.i(TAG, "onRenderedFirstFrame")
    }

    override fun pause() {
        mMediaHandler?.post {
            _exoPlayer?.playWhenReady = false
        }
    }

    override fun isPlaying(): Boolean {
        return runOnPlayerThread{
         //   _exoPlayer?.playWhenReady ?: false
            _exoPlayer?.playWhenReady
        } == true
      //  return isActuallyPlaying
    }

    override fun seekTo(time: Long) {
        mMediaHandler?.post {
            if (time != prevSeek) {
                _exoPlayer?.let { exoPlayer ->
                    if (time >= exoPlayer.bufferedPosition) {
                        jzvd.onStatePreparingPlaying()
                    }
                    exoPlayer.seekTo(time)
                    exoPlayer.playWhenReady = true
                    prevSeek = time
                    jzvd.seekToInAdvance = time
                }
            }
        }
    }

    override fun release() {
        if (mMediaHandler != null && mMediaHandlerThread != null && _exoPlayer != null) { //不知道有没有妖孽
            val tmpHandlerThread = mMediaHandlerThread
            val tmpMediaPlayer = exoPlayer
            SAVED_SURFACE = null
            mMediaHandler?.post {
                tmpMediaPlayer.release() //release就不能放到主线程里，界面会卡顿
                tmpHandlerThread.quit()
                _exoPlayer = null
            }
        }
    }



    // 在类里加一个工具方法，用来在线程里同步访问ExoPlayer

    inline fun <T> runOnPlayerThread(crossinline block: () -> T?): T? {
        if (Looper.myLooper() == mMediaHandler?.looper) {
            return block()
        }

        val result = AtomicReference<T?>()
        val latch = CountDownLatch(1)

        mMediaHandler?.post {
            try {
                result.set(block())
            } finally {
                latch.countDown()
            }
        }

        val finished = latch.await(300, TimeUnit.MILLISECONDS)
        return if (finished) result.get() else null
    }



    override fun getCurrentPosition(): Long {
        return runOnPlayerThread {
            _exoPlayer?.currentPosition
        } ?: 0L
    }


    override fun getDuration(): Long {
        return runOnPlayerThread {
            _exoPlayer?.duration
        } ?: 0L
    }



    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        mMediaHandler?.post {
            _exoPlayer?.volume = (leftVolume + rightVolume) / 2
        }
    }

    override fun setSpeed(speed: Float) {
        mMediaHandler?.post {
            val playbackParams = PlaybackParameters(speed, 1.0F)
            _exoPlayer?.playbackParameters = playbackParams
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        Log.i(TAG, "onTimelineChanged")
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        Log.i(TAG, "onIsLoadingChanged")
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        Log.i(TAG, "onPlayWhenReadyChanged: $playWhenReady, reason: $reason, " +
                "playbackState=${_exoPlayer?.playbackState}")
        if (playWhenReady && _exoPlayer?.playbackState == Player.STATE_READY) {
            handler?.post {
                Log.i(TAG,"onPlayWhenReadyChanged_ready")
                jzvd.onStatePlaying()
            }
        }
        Log.i(TAG,"onPlayWhenReadyChanged")
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Log.i(TAG, "onPlaybackStateChanged: $playbackState")
        handler?.post {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    jzvd.onStatePreparingPlaying()
                    callback?.let(handler::post)
                }

                Player.STATE_READY -> {

                    runOnPlayerThread {
                        Log.i(TAG, "STATE_READY, playWhenReady=${_exoPlayer?.playWhenReady}")
                        if (_exoPlayer?.playWhenReady == true){
                            jzvd.onStatePlaying()
                        }
                    }
                }

                Player.STATE_ENDED -> {
                    jzvd.onCompletion()
                }

                else -> {
                    Log.i(TAG, "onPlaybackStateChanged: $playbackState")
                }
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "onPlayerError: $error")
        handler?.post { jzvd.onError(1000, 1000) }
    }

//    override fun onPositionDiscontinuity(
//        oldPosition: Player.PositionInfo,
//        newPosition: Player.PositionInfo,
//        reason: Int,
//    ) {
//        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
//            handler?.post { jzvd.onSeekComplete() }
//        }
//    }
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            handler?.post {
                jzvd.onSeekComplete()
                runOnPlayerThread {
                    if (_exoPlayer?.playWhenReady==true) {
                        jzvd.onStatePlaying()
                    }
                }

            }
        }
    }

    override fun setSurface(surface: Surface?) {
        Log.e(TAG, "setSurface: $surface")
        _exoPlayer?.setVideoSurface(surface)
    }

//    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
//        if (SAVED_SURFACE == null) {
//            SAVED_SURFACE = surface
//            prepare()
//        } else {
//            jzvd.textureView.setSurfaceTexture(SAVED_SURFACE)
//        }
//    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (SAVED_SURFACE == null) {
            SAVED_SURFACE = surface
            Log.d(TAG, "onSurfaceTextureAvailable: $SAVED_SURFACE $width $height")
            if (_exoPlayer == null) {
                prepare()
            } else {
                _exoPlayer?.setVideoSurface(Surface(surface))
            }
        } else {
            jzvd.textureView.setSurfaceTexture(SAVED_SURFACE)
        }
    }


    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit


//    private inner class OnBufferingUpdate : Runnable {
//        override fun run() {
//            _exoPlayer?.bufferedPercentage?.let { per ->
//                handler.post {
//                    jzvd.setBufferProgress(per)
//                }
//                if (per < 100) {
//                    handler.postDelayed(this, 300)
//                } else {
//                    handler.removeCallbacks(this)
//                }
//                return
//            }
//            handler.removeCallbacks(this)
//        }
//    }
}


class SystemMediaKernel(jzvd: Jzvd) : JZMediaSystem(jzvd), HMediaKernel {
    // #issue-26: 有的手機長按快進會報錯，合理懷疑是不是因爲沒有加 post
    // #issue-28: 有的平板长按快进也会报错，结果是 IllegalArgumentException，很奇怪，两次 try-catch 处理试试。
    val videoRealWidth: Int get() = mediaPlayer?.videoWidth ?: 0
    val videoRealHeight: Int get() = mediaPlayer?.videoHeight ?: 0
    override fun setSpeed(speed: Float) {
        mMediaHandler?.post {
            try {
                val pp = mediaPlayer.playbackParams
                pp.speed = speed.absoluteValue
                mediaPlayer.playbackParams = pp
            } catch (_: IllegalArgumentException) {
                try {
                    val opp = PlaybackParams().setSpeed(speed.absoluteValue)
                    mediaPlayer.playbackParams = opp
                } catch (e: IllegalArgumentException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onVideoSizeChanged(mediaPlayer: MediaPlayer?, width: Int, height: Int) {
        super.onVideoSizeChanged(mediaPlayer, width, height)
        val ratio = width.toFloat() / height // > 1 橫屏， < 1 竖屏
        if (ratio > 1) {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // #issue-139: 部分机型暂停报错，没判空导致的
    override fun pause() {
        mMediaHandler?.post {
            mediaPlayer?.pause()
        }
    }

    // #issue-crashlytics-c8636c4bb0b8516675cbeb9e8776bf0b:
    // 有些机器到这里可能会报空指针异常，所以加了个判断，但是不知道为什么会报空指针异常
    override fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying == true
    }

    fun setLooping(looping: Boolean) {
        mMediaHandler?.post {
            mediaPlayer?.isLooping = looping
        }
    }
}

class MpvMediaKernel(jzvd: Jzvd) : JZMediaInterface(jzvd) {
    companion object {
        const val TAG = "MpvMediaKernel"

        /**
         * v0.1.12 起库改为多实例化：MPV 是实例类，每个 kernel 持有自己的会话，
         * 生命周期 = kernel 生命周期（prepare 创建 / release 销毁）。
         * Application 层不再全局 create/init。
         */
        @Volatile
        private var sharedMpv: MPV? = null

        fun obtainMpv(context: android.content.Context): MPV {
            // 单实例复用：mpv_create+mpv_initialize 开销大且 fork 的
            // native 层对反复 create/destroy 不保证完全干净，首次创建后复用。
            return sharedMpv ?: synchronized(this) {
                sharedMpv ?: MPV().also {
                    it.create(context.applicationContext)
                    sharedMpv = it
                }
            }
        }
    }
    private val mpv: MPV = obtainMpv(jzvd.context)
    val videoRealWidth: Int
        get() = mpv.getPropertyInt("video-params/w") ?: 0

    val videoRealHeight: Int
        get() = mpv.getPropertyInt("video-params/h") ?: 0

    private var mpvTimePos: Double = 0.0
    private var mpvCacheDuration: Double = 0.0
    private var mpvDuration: Double = 0.0
    private var currentPfd: ParcelFileDescriptor? = null
    private var detachFd: Int? = null
    private var pfdFilePath = false
    private val certFile = getCert(jzvd.context)
    val defaultSpeed = Preferences.playerSpeed
    private val mpvOptions: Map<String, String>
        get() = buildMap {
            // 视频输出驱动：gpu-next（libplacebo）对 glsl-shaders/Anime4K 的
            // 支持与画质上限都优于旧 gpu；v0.1.12 内核绑定 libplacebo 新版，
            // gpu-next 默认开启，用户可回退旧渲染器。
            put("vo", if (Preferences.enableGPUNextRenderer) "gpu-next" else "gpu")

            // 预设模式：fast（性能优先，画质略低；可改为 gpu-hq 追求高画质）
            put("profile", when (Preferences.mpvProfile) {
                "gpu-hq" -> "gpu-hq"
                "fast" -> "fast"
                else -> "default"
            })

            // 解码方式：选择合适的解码器（mediacodec/mediacodec-copy）
            // mpv 官方惯例（见 mpv-android MPVActivity.pickDecoder）：
            //   HW+  = mediacodec        直连零拷贝，帧不经过 GL 管线，glsl-shaders/Anime4K 不生效
            //   HW   = mediacodec-copy   解码后回拷内存，帧走 vo GL 管线，滤镜可用
            // Anime4K 档位开启时强制 copy 模式 —— 否则滤镜静默无效，
            // 这是"开了质量/性能档没提升"的直接根因之一。
            val srLevel = Preferences.superResolutionLevel
            put("hwdec", when (Preferences.mpvHwdec) {
                "Auto" -> if (srLevel != 0) "auto-copy" else "auto"
                "HW" -> "mediacodec-copy"
                "HW+" -> if (srLevel != 0) "mediacodec-copy" else "mediacodec"
                "Vulkan" -> "vulkan-copy"
                "vulkan+" -> if (srLevel != 0) "vulkan-copy" else "vulkan"
                "SW" -> "no"
                else -> if (srLevel != 0) "auto-copy" else "auto"
            })

            // 日志等级：fatal → error → warn → info → status → verbose → debug → trace
            put("msg-level", "all=" + if (BuildConfig.DEBUG) "debug" else "warn")

            // 插帧设置
            if (Preferences.mpvInterpolation) {
                put("interpolation", "yes")      // 启用插帧
                put("tscale", "oversample")      // 时间插值算法
                put("video-sync", "display-resample") // 同步刷新率
            }

            // 缓存与性能
            put("cache", "yes")  // 启用解复用缓存
            put("cache-secs", Preferences.mpvCacheSecs.toString())   // 预缓存秒数
            put("vd-lavc-threads", Runtime.getRuntime().availableProcessors().toString())  // 视频解码线程数：设为 CPU 核心数，提升多核利用率

            if (Preferences.mpvFramedrop) {
                put("framedrop", "vo")  // GPU 繁忙时允许丢帧（保持音画同步，避免卡顿）
            } else {
                put("framedrop", "no")
            }

            put("deband", if (Preferences.mpvDeband) "yes" else "no")  // 去色带

            put("cache-pause", "no")  // 缓存时是否暂停播放

            put("network-timeout", Preferences.mpvNetworkTimeout.toString())  // 请求超时

            put("tls-ca-file", certFile)  // 为播放器指定根证书文件，解决 tls-verify 如果为yes播放失败的问题

            put("tls-verify", if (Preferences.mpvTlsVerify) "no" else "yes")  // 是否证书验证 yes、no

            // 单独为MPV播放器配置代理，因为它不走ProxySelector，也不支持socks代理，沟槽的非原生实现
            val proxyIp = Preferences.proxyIp
            val proxyPort = Preferences.proxyPort
            if (proxyIp.isNotBlank() && proxyPort != -1) {
                val proxyUrl = "${proxyIp}:${proxyPort}"
                if (Preferences.proxyType == HProxySelector.TYPE_HTTP) {
                    put("http-proxy", "http://$proxyUrl")
                }
            }
            put("user-agent", USER_AGENT)
        }


    private val observedProperties = listOf(
        "time-pos" to MPV.mpvFormat.MPV_FORMAT_DOUBLE, // 当前播放时间（秒，带小数）
        "duration" to MPV.mpvFormat.MPV_FORMAT_DOUBLE, // 视频总时长（秒）
        "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,      // 是否暂停（true/false）
        "playback-active" to MPV.mpvFormat.MPV_FORMAT_FLAG, // 播放是否处于活动状态
        "video-params/w" to MPV.mpvFormat.MPV_FORMAT_INT64, // 视频宽度（像素）
        "video-params/h" to MPV.mpvFormat.MPV_FORMAT_INT64, // 视频高度（像素）
        "demuxer-cache-duration" to MPV.mpvFormat.MPV_FORMAT_DOUBLE
    )
    fun parseCustomMpvParams(): LinkedHashMap<String, String> {
        val rawInput = Preferences.customMpvParams
        val map = linkedMapOf<String, String>()

        rawInput.split(";").forEach { entry ->
            val trimmedEntry = entry.trim()
            if (trimmedEntry.isEmpty() || trimmedEntry.startsWith("#")) return@forEach

            val parts = trimmedEntry.split(",", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        return map
    }

    fun init() {
        mpvOptions.forEach { (key, value) ->
            Log.i("MPV_config","$key,$value")
            // 运行时用 property 语义写入：mpv 0.21+ 上 mpv_set_option 在初始化后
            // 等价于 mpv_set_property，可正常生效；初始化前的设置则充当预配置。
            if (mpv.setOptionString(key, value) < 0) {
                Log.w(TAG, "mpv option rejected: $key=$value")
            }
        }
        try {
            val customParams = parseCustomMpvParams()
            customParams.forEach { (key, value) ->
                Log.i("MPV_config","custom: $key,$value")
                if (mpv.setOptionString(key, value) < 0) {
                    Log.w(TAG, "custom mpv option rejected: $key=$value")
                }
            }
        } catch (e: Exception){
            GlobalToasts.show(e.message.orEmpty(), level = GlobalToasts.ToastLevel.ERROR)
        }

        observedProperties.forEach { (name, type) ->
            mpv.observeProperty(name, type)
        }
        mpv.addObserver(mpvEventObserver)
    }

    fun prepareUri(context: Context, uri: Uri): String? {
        return when (uri.scheme) {
            "http", "https" -> {
                uri.toString()
            }
            "file", "content" -> {
                try {
                    currentPfd = context.contentResolver.openFileDescriptor(uri, "r")
                    detachFd = currentPfd?.detachFd()
                    Log.d(TAG, "Detached FD = $detachFd")
                    pfdFilePath = true
                    if (detachFd != null) {
                        "fd://$detachFd"
                    } else {
                        // detach 失败时 PFD 还握在手里，立即关闭防句柄泄漏
                        currentPfd?.close()
                        currentPfd = null
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "prepareUri failed for $uri", e)
                    // 异常路径同样兜底关闭，防句柄泄漏
                    try { currentPfd?.close() } catch (_: Exception) {}
                    currentPfd = null
                    detachFd = null
                    null
                }
            }
            else -> {
                null
            }
        }
    }

    fun releaseCurrentPfd(from: String? = null) {
        if (!pfdFilePath) return
        try {
            currentPfd?.let {
                Log.i(TAG, "Closing currentPfd: $it")
                it.close()
            }

            detachFd?.let { fd ->
                Log.i(TAG, "Closing detachFd: $fd")
                try {
                    ParcelFileDescriptor.adoptFd(fd).close()
                } catch (e: Exception) {
                    Log.w(TAG, "detachFd $fd already closed or invalid", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing PFD", e)
        } finally {
            currentPfd = null
            detachFd = null
            handler?.postDelayed({
                Log.i(TAG, "${from ?: "releaseCurrentPfd"} completed. PFD cleared.")
            }, 200)
        }
    }


    override fun prepare() {
        init()
        handler = Handler(Looper.getMainLooper())

        val url = jzvd.jzDataSource.currentUrl.toString()
        if (url.isEmpty()) {
            Log.e(TAG, "视频链接为空")
            return
        }

        Log.e(TAG, "URL Link = $url")
        mpv.setOptionString("force-window", "yes")

        val uri = url.toUri()
        val path = prepareUri(jzvd.context, uri)
        if (path != null) {
            // 先 attachSurface（设置 wid），再 loadfile：
            // wid 在播放开始时读取，若文件先加载而 surface 后挂，
            // 首帧可能输出到无效窗口（黑屏/无画面），之后再补救渲染。
            val surfaceTexture = jzvd.textureView?.surfaceTexture
            surfaceTexture?.let { mpv.attachSurface(Surface(it)) }
            mpv.command("loadfile", path)
        }
    }

    override fun start() {
        mpv.setPropertyBoolean("pause", false)
    }

    fun setLooping(looping: Boolean) {
        mpv.setPropertyString("loop-file", if (looping) "inf" else "no")
    }

    override fun pause() {
        mpv.setPropertyBoolean("pause", true)
    }

    override fun isPlaying(): Boolean {
        val pause = mpv.getPropertyBoolean("pause")
        return pause == false
    }

    override fun seekTo(time: Long) {
        Log.i("seekTo","${ (time / 1000.0)}")
        mpv.command("seek", (time / 1000.0).toString(), "absolute", "exact")
    }

    override fun release() {
        clearSuperResolution()
        try {
            mpv.setPropertyBoolean("pause", true)
            mpv.command("loadfile", "", "replace")
            mpv.setPropertyString("vo", "null")
            mpv.setOptionString("force-window", "no")
            mpv.detachSurface()
        } catch (e: Exception) {
            Log.w(TAG, "mpv release cleanup error", e)
        }
        mpv.removeObserver(mpvEventObserver)
        handler?.postDelayed({
            releaseCurrentPfd("jzvd-release")
        }, 200)
        SAVED_SURFACE = null
    }

    override fun getCurrentPosition(): Long {
        val timePosSeconds = mpv.getPropertyDouble("time-pos")
        return (timePosSeconds ?: 0.0).toLong() * 1000
    }

    override fun getDuration(): Long {
        val durationSeconds = mpv.getPropertyDouble("duration")
        return (durationSeconds ?: 0.0).toLong() * 1000
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        val volume = (leftVolume + rightVolume) / 2 * 100
        mpv.setPropertyDouble("volume", volume.toDouble())
    }

    override fun setSpeed(speed: Float) {
        mpv.setPropertyDouble("speed", speed.toDouble())
    }

    override fun setSurface(surface: Surface?) {
        if (surface != null) {
            mpv.attachSurface(surface)
        } else {
            mpv.detachSurface()
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (SAVED_SURFACE == null) {
            SAVED_SURFACE = surfaceTexture
            prepare()
        } else {
            jzvd.textureView.setSurfaceTexture(SAVED_SURFACE)
        }
    }

    fun updateSurFaceSize(width: Int, height: Int) {
        Log.d(TAG, "updateSurFaceSize ${width}x${height}")
        mpv.setPropertyString("android-surface-size", "${width}x${height}")
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureSizeChanged ${width}x${height}")
        updateSurFaceSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean = false

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}


    private fun clearSuperResolution() {
        mpv.command("change-list", "glsl-shaders", "clr", "")
    }

    /**
     * 应用/清除超分辨率滤镜。
     * index: 0=关闭 1=效率(性能) 2=质量 —— 与 [AnimeShaders.getShader] 的 type 对应。
     *
     * 运行时切档：若当前处于直连硬解（如 HW+/mediacodec），主动把 hwdec 切到
     * copy 模式让帧进入 GL 管线 —— mpv 允许运行时热切 hwdec，切换后滤镜即可
     * 生效，无需用户手动去设置页调解码器。关闭档位不反向强切（尊重用户选择）。
     */
    fun setSuperResolution(index: Int) {
        if (index != 0) {
            val hwdec = mpv.getPropertyString("hwdec-current")
            if (hwdec != null && hwdec != "no" && !hwdec.endsWith("-copy")) {
                Log.w(TAG, "hwdec=$hwdec is direct mode; switching to copy mode for glsl-shaders")
                mpv.setPropertyString("hwdec", "mediacodec-copy")
                GlobalToasts.show("已自动切换到兼容解码以启用滤镜")
            }
            val cmd = arrayOf("change-list", "glsl-shaders", "set", AnimeShaders.getShader(jzvd.context, index))
            mpv.command(*cmd)
        } else {
            clearSuperResolution()
        }
    }

    private val mpvEventObserver = object : MPV.EventObserver {
        override fun eventProperty(property: String) {
//            Log.d(TAG, "eventProperty: $property")
        }

        override fun eventProperty(property: String, value: Long) {

        }

        override fun eventProperty(property: String, value: Boolean) {
//            Log.d(TAG, "eventProperty: $property $value")
        }

        override fun eventProperty(property: String, value: String) {
//            Log.d(TAG, "eventProperty: $property $value")
        }

        override fun eventProperty(property: String, value: Double) {
//            Log.d(TAG, "eventProperty: $property $value")
            when (property) {
                "time-pos" -> mpvTimePos = value
                "demuxer-cache-duration" -> mpvCacheDuration = value
                "duration" -> mpvDuration = value
            }
            if (mpvDuration > 0) {
                val bufferedTime = mpvTimePos + mpvCacheDuration
                val safeBufferedTime = bufferedTime.coerceAtMost(mpvDuration)
                val bufferPercent = ((safeBufferedTime / mpvDuration) * 100).toInt()
                handler.post {
                    jzvd.setBufferProgress(bufferPercent)
                }
            }

        }

        override fun eventProperty(property: String, value: MPVNode) {
//            Log.d(TAG, "eventProperty: $property node")
        }

        override fun event(eventId: Int, data: MPVNode) {
            handler.post {
                when (eventId) {
                    MPV.mpvEvent.MPV_EVENT_START_FILE -> {
                        // 文件开始加载
                        mpvTimePos = 0.0
                        mpvCacheDuration = 0.0
                        mpvDuration = 0.0
                        jzvd.onStatePreparing()
                    }
                    MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> {
                        // 文件加载成功
                        jzvd.onPrepared()
                        mpv.setPropertyDouble("speed", defaultSpeed.toDouble())
                        // 每个新文件都重放一次超分设置：mpv 的 glsl-shaders
                        // 跟随 VO/文件生命周期，换文件后必须重新 set，否则滤镜丢失。
                        applyPersistedSuperResolution()
                    }
                    MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                        // 播放重新开始
                        jzvd.onStatePlaying()
                    }
                    MPV.mpvEvent.MPV_EVENT_END_FILE -> {
                        // 播放结束
                        releaseCurrentPfd("MPV_EVENT_END_FILE")
                        jzvd.onCompletion()
                    }
                    MPV.mpvEvent.MPV_EVENT_SHUTDOWN -> {
                        Log.e(TAG, "event: $eventId")
                    }
                }
            }
        }
    }

    /**
     * 读取持久化的超分档位并应用到当前 mpv 实例。
     * 由 MPV_EVENT_FILE_LOADED 触发（换视频/重建 kernel 后自动恢复滤镜）。
     */
    private fun applyPersistedSuperResolution() {
        try {
            val index = Preferences.superResolutionLevel
            if (index != 0) {
                val cmd = arrayOf("change-list", "glsl-shaders", "set", AnimeShaders.getShader(jzvd.context, index))
                mpv.command(*cmd)
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyPersistedSuperResolution failed", e)
        }
    }
}