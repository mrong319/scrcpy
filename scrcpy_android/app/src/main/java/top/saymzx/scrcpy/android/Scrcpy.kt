package top.saymzx.scrcpy.android

import android.content.ClipData
import android.content.ClipDescription.MIMETYPE_TEXT_PLAIN
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioTrack.WRITE_NON_BLOCKING
import android.media.MediaCodec
import android.media.MediaCodec.Callback
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.SurfaceView
import android.widget.Toast
import dev.mobile.dadb.AdbKeyPair
import dev.mobile.dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.BufferedSource
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue

class Scrcpy(private val device: Device) {

  // ip地址
  private var ip = ""

  // 视频悬浮窗
  private lateinit var floatVideo: FloatVideo

  // 音频
  private var canAudio = true
  private lateinit var audioTrack: AudioTrack
  private lateinit var loudnessEnhancer: LoudnessEnhancer

  // 连接流
  private lateinit var adb: Dadb
  private lateinit var videoStream: BufferedSource
  private lateinit var audioStream: BufferedSource
  private lateinit var controlOutStream: BufferedSink
  private lateinit var controlInStream: BufferedSource

  // 解码器
  private lateinit var videoDecodec: MediaCodec
  private lateinit var audioDecodec: MediaCodec

  // 剪切板
  private var clipBoardText = ""

  // 开始投屏
  fun start() {
    device.isFull = device.defaultFull
    device.status = 0
    // 显示加载中
    val alert = appData.publicTools.showLoading("连接中...", appData.main, true) {
      stop("用户停止")
    }
    appData.mainScope.launch {
      try {
        // 获取IP地址
        ip = withContext(Dispatchers.IO) {
          Inet4Address.getByName(device.address)
        }.hostAddress!!
        // 发送server
        sendServer()
        // 转发端口
        tcpForward()
      } catch (e: Exception) {
        alert.cancel()
        stop("连接错误", e)
      }
      try {
        alert.cancel()
        // 配置视频解码
        setVideoDecodec()
        // 配置音频解码
        setAudioDecodec()
        // 配置音频播放
        if (canAudio) setAudioTrack()
        // 投屏中
        device.status = 1
        // 设置被控端熄屏（默认投屏后熄屏）
        setPowerOff()
      } catch (e: Exception) {
        stop("启动错误", e)
      }
      // 视频解码输入
      Thread {
        try {
          decodeInput("video")
        } catch (e: Exception) {
          stop("投屏停止", e)
        }
      }.start()
      // 音频解码输入
      if (canAudio) {
        Thread {
          try {
            decodeInput("audio")
          } catch (e: Exception) {
            stop("投屏停止", e)
          }
        }.start()
      }
      // 配置控制输入
      Thread {
        try {
          setControlInput()
        } catch (e: Exception) {
          stop("投屏停止", e)
        }
      }.start()
      // 配置控制输出
      Thread {
        try {
          setControlOutput()
        } catch (e: Exception) {
          stop("投屏停止", e)
        }
      }.start()
    }
  }

  // 停止投屏
  fun stop(scrcpyError: String, e: Exception? = null) {
    // 防止多次调用
    if (device.status == -1) return
    val oldStatus = device.status
    device.status = -1
    appData.mainScope.launch {
      withContext(Dispatchers.Main) {
        Toast.makeText(appData.main, scrcpyError, Toast.LENGTH_SHORT).show()
        if (e != null)
          Toast.makeText(appData.main, "详细信息：$e", Toast.LENGTH_SHORT).show()
      }
    }
    Log.e("Scrcpy", "$scrcpyError---${e?.toString() ?: ""}")
    appData.mainScope.launch {
      try {
        // 恢复分辨率
        if (device.setResolution) runAdbCmd("wm size reset")
        runAdbCmd("ps -ef | grep scrcpy | grep -v grep | grep -E \"^[a-z]+ +[0-9]+\" -o | grep -E \"[0-9]+\" -o | xargs kill -9")
        adb.close()
      } catch (_: Exception) {
      }
    }
    try {
      if (canAudio) {
        loudnessEnhancer.release()
        audioTrack.stop()
        audioTrack.release()
        audioDecodec.stop()
        audioDecodec.release()
      }
    } catch (_: Exception) {
    }
    try {
      videoDecodec.stop()
      videoDecodec.release()
    } catch (_: Exception) {
    }
    try {
      videoStream.close()
      audioStream.close()
      controlOutStream.close()
      controlInStream.close()
    } catch (_: Exception) {
    }
    try {
      floatVideo.hide()
    } catch (_: Exception) {
    }
  }

  // 发送server
  private suspend fun sendServer() {
    // 连接ADB
    adb =
      Dadb.create(ip, device.port, AdbKeyPair.read(appData.privateKey, appData.publicKey))
    // 修改分辨率
    if (device.setResolution) runAdbCmd(
      "wm size ${appData.deviceWidth}x${appData.deviceHeight}"
    )
    // 停止旧服务
    runAdbCmd("ps -ef | grep scrcpy | grep -v grep | grep -E \"^[a-z]+ +[0-9]+\" -o | grep -E \"[0-9]+\" -o | xargs kill -9")
    // 快速启动
    val isHaveServer = runAdbCmd(" ls -l /data/local/tmp/scrcpy_server${appData.versionCode}.jar ")
    if (isHaveServer.contains("No such file or directory") || isHaveServer.contains("Invalid argument")) {
      runAdbCmd("rm /data/local/tmp/scrcpy_server*")
      val serverFileBase64 = Base64.encodeToString(withContext(Dispatchers.IO) {
        val server = appData.main.resources.openRawResource(R.raw.scrcpy_server)
        val buffer = ByteArray(server.available())
        server.read(buffer)
        server.close()
        buffer
      }, 2)
      runAdbCmd("echo $serverFileBase64 >> /data/local/tmp/scrcpy_server_base64\n")
      runAdbCmd("base64 -d < /data/local/tmp/scrcpy_server_base64 > /data/local/tmp/scrcpy_server${appData.versionCode}.jar && rm /data/local/tmp/scrcpy_server_base64")
    }
    runAdbCmd("CLASSPATH=/data/local/tmp/scrcpy_server${appData.versionCode}.jar app_process / com.genymobile.scrcpy.Server 2.1 video_codec=${device.videoCodec} audio_codec=${device.audioCodec} max_size=${device.maxSize} video_bit_rate=${device.videoBit} max_fps=${device.fps} > /dev/null 2>&1 &")
  }

  // 转发端口
  private suspend fun tcpForward() {
    var connect = 0
    withContext(Dispatchers.IO) {
      for (i in 1..100) {
        try {
          if (connect == 0) {
            videoStream = adb.open("tcp:6006").source
            connect = 1
          }
          if (connect == 1) {
            audioStream = adb.open("tcp:6006").source
            connect = 2
          }
          val control = adb.open("tcp:6006")
          controlOutStream = control.sink
          controlInStream = control.source
          break
        } catch (_: Exception) {
          Log.i("Scrcpy", "连接失败，再次尝试")
          delay(50)
        }
      }
    }
  }

  // 视频解码器
  private val videoDecodecQueue = LinkedBlockingQueue<Int>()
  private var checkRotationNotification = false
  private suspend fun setVideoDecodec() {
    // CodecMeta
    withContext(Dispatchers.IO) {
      videoStream.readInt()
      val remoteVideoWidth = videoStream.readInt()
      val remoteVideoHeight = videoStream.readInt()
      // 显示悬浮窗
      floatVideo =
        FloatVideo(device, remoteVideoWidth, remoteVideoHeight) {
          hasConerols = true
          controls.offer(it)
        }
    }
    floatVideo.show()
    // 创建解码器
    val codecMime =
      if (device.videoCodec == "h265") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
    videoDecodec = MediaCodec.createDecoderByType(codecMime)
    val mediaFormat = MediaFormat.createVideoFormat(
      codecMime,
      floatVideo.remoteVideoWidth,
      floatVideo.remoteVideoHeight
    )
    // 获取视频标识头
    val csd0 = withContext(Dispatchers.IO) { readFrame(videoStream) }
    val csd1 = withContext(Dispatchers.IO) { readFrame(videoStream) }
    mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
    mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
    // 配置低延迟解码
    val codeInfo = videoDecodec.codecInfo
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      if (codeInfo.getCapabilitiesForType(codecMime)
          .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
      ) mediaFormat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
    }
    // 配置解码器
    videoDecodec.configure(
      mediaFormat,
      floatVideo.floatVideo.findViewById<SurfaceView>(R.id.float_video_surface).holder.surface,
      null,
      0
    )
    // 配置异步
    videoDecodec.setCallback(object : Callback() {
      override fun onInputBufferAvailable(p0: MediaCodec, p1: Int) {
        videoDecodecQueue.offer(p1)
      }

      override fun onOutputBufferAvailable(
        decodec: MediaCodec,
        outIndex: Int,
        p2: MediaCodec.BufferInfo
      ) {
        try {
          // 是否需要检查旋转(仍需要检查，因为可能是90°和270°的旋转)
          if (checkRotationNotification) {
            checkRotationNotification = false
            floatVideo.checkRotation(
              decodec.getOutputFormat(outIndex).getInteger("width"),
              decodec.getOutputFormat(outIndex).getInteger("height")
            )
          }
          decodec.releaseOutputBuffer(outIndex, true)
        } catch (e: Exception) {
          stop("投屏停止", e)
        }
      }

      override fun onError(p0: MediaCodec, p1: MediaCodec.CodecException) {
      }

      override fun onOutputFormatChanged(p0: MediaCodec, p1: MediaFormat) {
      }

    })
    // 启动解码器
    videoDecodec.start()
    // 解析首帧，解决开始黑屏问题
    decodecQueueBuffer(videoDecodec, videoDecodecQueue, csd0)
    decodecQueueBuffer(videoDecodec, videoDecodecQueue, csd1)
  }

  // 音频解码器
  private val audioDecodecQueue = LinkedBlockingQueue<Int>()
  private suspend fun setAudioDecodec() {
    // 创建音频解码器
    val codecMime =
      if (device.audioCodec == "opus") MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC
    audioDecodec = MediaCodec.createDecoderByType(codecMime)
    // 是否不支持音频（安卓11以下不支持）
    val can = withContext(Dispatchers.IO) { audioStream.readInt() }
    if (can == 0) {
      canAudio = false
      return
    }
    // 音频参数
    val sampleRate = 48000
    val channelCount = 2
    val bitRate = 64000
    val mediaFormat =
      MediaFormat.createAudioFormat(codecMime, sampleRate, channelCount)
    mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
    // 获取音频标识头
    mediaFormat.setByteBuffer(
      "csd-0",
      ByteBuffer.wrap(withContext(Dispatchers.IO) { readFrame(audioStream) })
    )
    if (device.audioCodec == "opus") {
      // csd1和csd2暂时没用到，所以默认全是用0
      val csd12bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
      val csd12ByteBuffer = ByteBuffer.wrap(csd12bytes, 0, csd12bytes.size)
      mediaFormat.setByteBuffer("csd-1", csd12ByteBuffer)
      mediaFormat.setByteBuffer("csd-2", csd12ByteBuffer)
    }
    // 配置解码器
    audioDecodec.configure(mediaFormat, null, null, 0)
    // 配置异步
    var loopNum = 0
    audioDecodec.setCallback(object : Callback() {
      override fun onInputBufferAvailable(p0: MediaCodec, p1: Int) {
        audioDecodecQueue.offer(p1)
      }

      override fun onOutputBufferAvailable(
        decodec: MediaCodec,
        outIndex: Int,
        bufferInfo: MediaCodec.BufferInfo
      ) {
        loopNum++
        if (loopNum > 100) {
          loopNum = 0
          checkClipBoard()
        }
        try {
          audioTrack.write(
            decodec.getOutputBuffer(outIndex)!!,
            bufferInfo.size,
            WRITE_NON_BLOCKING
          )
          decodec.releaseOutputBuffer(outIndex, false)
        } catch (e: Exception) {
          stop("投屏停止", e)
        }
      }

      override fun onError(p0: MediaCodec, p1: MediaCodec.CodecException) {
      }

      override fun onOutputFormatChanged(p0: MediaCodec, p1: MediaFormat) {
      }
    })
    // 启动解码器
    audioDecodec.start()
  }

  // 初始化音频播放器
  private fun setAudioTrack() {
    val audioDecodecBuild = AudioTrack.Builder()
    val sampleRate = 48000
    val minBufferSize = AudioTrack.getMinBufferSize(
      sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
    )
    audioDecodecBuild.setBufferSizeInBytes(minBufferSize * 4)
    val audioAttributesBulider = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    audioDecodecBuild.setAudioAttributes(audioAttributesBulider.build())
    audioDecodecBuild.setAudioFormat(
      AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
    )
    audioTrack = audioDecodecBuild.build()
    // 声音增强
    try {
      loudnessEnhancer = LoudnessEnhancer(audioTrack.audioSessionId)
      loudnessEnhancer.setTargetGain(3000)
      loudnessEnhancer.enabled = true
    } catch (_: Exception) {
      Toast.makeText(appData.main, "音频放大器未生效", Toast.LENGTH_SHORT).show()
    }
    audioTrack.play()
  }

  // 输入
  private fun decodeInput(mode: String) {
    val stream = if (mode == "video") videoStream else audioStream
    val decodec = if (mode == "video") videoDecodec else audioDecodec
    val queue = if (mode == "video") videoDecodecQueue else audioDecodecQueue
    val isCheckScreen = mode == "video"
    var zeroFrameNum = 0
    // 开始解码
    while (true) {
      // 向缓冲区输入数据帧
      val buffer = readFrame(stream)
      // 连续4个空包检测是否熄屏了
      if (isCheckScreen) {
        if (buffer.size < 150) {
          zeroFrameNum++
          if (zeroFrameNum > 4) {
            zeroFrameNum = 0
            checkScreenOff()
          }
        }
      }
      decodecQueueBufferThead(decodec, queue, buffer)
    }
  }

  // 填充入解码器
  private fun decodecQueueBufferThead(
    decodec: MediaCodec,
    queue: LinkedBlockingQueue<Int>,
    buffer: ByteArray
  ) {
    // 找到一个空的输入缓冲区
    while (true) {
      if (queue.isEmpty()) {
        Thread.sleep(4)
        continue
      }
      queue.poll().let {
        decodec.getInputBuffer(it!!)!!.put(buffer)
        decodec.queueInputBuffer(it, 0, buffer.size, 0, 0)
      }
      break
    }
  }

  private suspend fun decodecQueueBuffer(
    decodec: MediaCodec,
    queue: LinkedBlockingQueue<Int>,
    buffer: ByteArray
  ) {
    // 找到一个空的输入缓冲区
    while (true) {
      if (queue.isEmpty()) {
        delay(4)
        continue
      }
      queue.poll().let {
        decodec.getInputBuffer(it!!)!!.put(buffer)
        decodec.queueInputBuffer(it, 0, buffer.size, 0, 0)
      }
      break
    }
  }

  // 检测报文输入
  private fun setControlInput() {
    while (true) {
      // 检测被控端剪切板变化
      val type =
        try {
          controlInStream.readByte().toInt()
        } catch (_: IllegalStateException) {
          -1
        }
      when (type) {
        // 剪切板报告报文
        0 -> {
          val newClipBoardText = String(
            controlInStream.readByteArray(
              controlInStream.readInt().toLong()
            ),
            StandardCharsets.UTF_8
          )
          if (clipBoardText != newClipBoardText) {
            clipBoardText = newClipBoardText
            appData.clipBorad.setPrimaryClip(
              ClipData.newPlainText(
                MIMETYPE_TEXT_PLAIN,
                clipBoardText
              )
            )
          }
        }
        // 设置剪切板回应报文
        1 -> {
          controlInStream.readLong()
        }
        // 设置旋转报文
        2 -> {
          // 延迟0.5秒等待画面稳定
          Thread.sleep(500)
          checkRotationNotification = true
        }
      }
    }
  }

  // 控制报文输出
  private val controls = LinkedBlockingQueue<ByteArray>()
  private var hasConerols = false
  private fun setControlOutput() {
    while (true) {
      if (!hasConerols) {
        Thread.sleep(4)
        continue
      }
      val buffer = controls.poll()
      if (buffer == null) {
        hasConerols = false
        continue
      }
      controlOutStream.write(buffer)
      controlOutStream.flush()
    }
  }

  // 防止被控端熄屏
  private var isScreenOning = false
  private fun checkScreenOff() {
    appData.mainScope.launch {
      if (!runAdbCmd("dumpsys deviceidle | grep mScreenOn").contains("mScreenOn=true") && !isScreenOning) {
        // 避免短时重复操作
        isScreenOning = true
        runAdbCmd("input keyevent 26")
        delay(100)
        isScreenOning = false
      }
    }
  }

  // 被控端熄屏
  private fun setPowerOff() {
    hasConerols = true
    controls.offer(byteArrayOf(10, 0))
  }

  // 同步本机剪切板至被控端
  private fun checkClipBoard() {
    val clipBorad = appData.clipBorad.primaryClip
    val newClipBoardText =
      if (clipBorad != null && clipBorad.itemCount > 0) clipBorad.getItemAt(0).text.toString() else ""
    if (clipBoardText != newClipBoardText && newClipBoardText != "") {
      clipBoardText = newClipBoardText
      setClipBoard(clipBoardText)
    }
  }

  // 设置剪切板文本
  private fun setClipBoard(text: String) {
    val textByteArray = text.toByteArray(StandardCharsets.UTF_8)
    val byteBuffer = ByteBuffer.allocate(14 + textByteArray.size)
    byteBuffer.clear()
    byteBuffer.put(9)
    byteBuffer.putLong(101)
    byteBuffer.put(0)
    byteBuffer.putInt(textByteArray.size)
    byteBuffer.put(textByteArray)
    byteBuffer.flip()
    hasConerols = true
    controls.offer(byteBuffer.array())
  }

  // 从socket流中解析数据
  private fun readFrame(stream: BufferedSource): ByteArray {
    return try {
      stream.readByteArray(stream.readInt().toLong())
    } catch (_: IllegalStateException) {
      ByteArray(1)
    }
  }

  // 执行adb命令
  private suspend fun runAdbCmd(cmd: String): String {
    return withContext(Dispatchers.IO) { adb.shell(cmd).allOutput }
  }

}