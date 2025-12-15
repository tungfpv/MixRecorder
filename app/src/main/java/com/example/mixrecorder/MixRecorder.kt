package com.example.mixrecorder

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MixRecorder(
    private val context: Context,
    private val projection: MediaProjection
) {

    private val sampleRate = 44100
    private val channelCount = 1
    private val bitrate = 128_000

    val PLAY_VOLUME = 0.2f
    val MIC_VOLUME = 2.0f

    private lateinit var micRecord: AudioRecord
    private lateinit var playbackRecord: AudioRecord

    private lateinit var codec: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var trackIndex = -1
    private var isMuxerStarted = false

    private var recording = true
    private var presentationTimeUs = 0L
    private lateinit var outputUri: Uri
    private lateinit var outputResolver: ContentResolver


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @RequiresApi(Build.VERSION_CODES.Q)
    fun start() {
        setupAudioRecord()
        setupEncoder()

        micRecord.startRecording()
        playbackRecord.startRecording()

        Thread { recordLoop() }.start()
    }

    fun stop() {
        recording = false
    }

    // ---------------- SETUP ----------------

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun setupAudioRecord() {
        // MIC / USB MIXER
        micRecord = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        )

        // SYSTEM AUDIO
        val playbackConfig =
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        playbackRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            )
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupEncoder() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        )
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val (uri, resolver) =
            MediaStoreOutput.createM4aFile(
                context,
                "mix_${System.currentTimeMillis()}.m4a"
            )

        val pfd = resolver.openFileDescriptor(uri, "w")!!
        muxer = MediaMuxer(
            pfd.fileDescriptor,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        outputUri = uri
        outputResolver = resolver

    }

    // ---------------- RECORD LOOP ----------------

    private fun recordLoop() {
        val micBuf = ShortArray(2048)
        val playBuf = ShortArray(2048)
        val mixBuf = ShortArray(2048)

        while (recording) {
            val m = micRecord.read(micBuf, 0, micBuf.size)
            val p = playbackRecord.read(playBuf, 0, playBuf.size)
            val size = minOf(m, p)

            for (i in 0 until size) {

                /*val mixed = micBuf[i]  + playBuf[i]
                mixBuf[i] = mixed
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()*/

                val mixed = micBuf[i] * MIC_VOLUME  + playBuf[i] * PLAY_VOLUME
                mixBuf[i] = mixed
                    .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                    .toInt()
                    .toShort()
            }

            encode(mixBuf, size)
        }

        release()
    }

    // ---------------- AAC ENCODE ----------------

    private fun encode(buffer: ShortArray, size: Int) {
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)!!
            inputBuffer.clear()

            val byteBuf = ByteBuffer.allocate(size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until size) byteBuf.putShort(buffer[i])
            byteBuf.flip()

            inputBuffer.put(byteBuf)
            codec.queueInputBuffer(
                inputIndex,
                0,
                byteBuf.limit(),
                presentationTimeUs,
                0
            )

            presentationTimeUs += size * 1_000_000L / sampleRate
        }

        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, 0)
            if (outputIndex < 0) break

            val outBuf = codec.getOutputBuffer(outputIndex)!!

            if (!isMuxerStarted) {
                trackIndex = muxer.addTrack(codec.outputFormat)
                muxer.start()
                isMuxerStarted = true
            }

            muxer.writeSampleData(trackIndex, outBuf, info)
            codec.releaseOutputBuffer(outputIndex, false)
        }
    }

    private fun release() {
        micRecord.stop()
        playbackRecord.stop()
        micRecord.release()
        playbackRecord.release()

        codec.stop()
        codec.release()

        muxer.stop()
        muxer.release()
        MediaStoreOutput.finalize(outputResolver, outputUri)
    }
}
