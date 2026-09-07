package com.baigao.countdown

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * 倒计时归零提示音播放器。
 * - 未设置自定义提示音：用 AudioTrack 合成三声短 beep（无需任何音频文件）。
 * - 设置了自定义提示音：用 MediaPlayer 播放用户选择的音频文件 URI。
 */
object SoundPlayer {
    private const val TAG = "SoundPlayer"

    fun play(context: Context, uri: String?) {
        Thread {
            try {
                if (uri.isNullOrEmpty()) {
                    playBeep()
                    return@Thread
                }
                try {
                    val mp = MediaPlayer()
                    mp.setDataSource(context, Uri.parse(uri))
                    mp.setOnCompletionListener { it.release() }
                    mp.setOnErrorListener { mp2, _, _ -> mp2.release(); playBeep(); true }
                    mp.prepare()
                    mp.start()
                } catch (e: Exception) {
                    Log.w(TAG, "自定义提示音播放失败，回退到默认 beep: ${e.message}")
                    playBeep()
                }
            } catch (e: Exception) {
                Log.w(TAG, "play error: ${e.message}")
            }
        }.start()
    }

    private fun playBeep() {
        try {
            val sr = 44100
            val durMs = 200
            val n = (sr * durMs / 1000)
            repeat(3) {
                val buf = ShortArray(n)
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    val v = (Math.sin(2 * Math.PI * 880 * t) * 0.6 * 32767).toInt()
                    buf[i] = v.toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep((durMs + 120).toLong())
                track.stop()
                track.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "beep failed: ${e.message}")
        }
    }
}
