package com.ymd.flutter_audio_capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.os.Handler
import android.os.Looper

import io.flutter.plugin.common.EventChannel.StreamHandler
import io.flutter.plugin.common.EventChannel.EventSink
import java.lang.Exception

import kotlin.math.sqrt
import kotlin.math.log10
import org.jtransforms.fft.FloatFFT_1D

public class AudioCaptureStreamHandler: StreamHandler {
    public val eventChannelName = "ymd.dev/audio_capture_event_channel"
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    private var SAMPLE_RATE: Int = 44000
    private val TAG: String = "AudioCaptureStream"
    private var isCapturing: Boolean = false
    private var listener = null
    private var thread: Thread? = null
    private var _events: EventSink? = null
    private val uiThreadHandler: Handler = Handler(Looper.getMainLooper())

    override fun onListen(arguments: Any?, events: EventSink?) {
        Log.d(TAG, "onListen started")
        if (arguments != null && arguments is Map<*, *>) {
            val sampleRate = arguments["sampleRate"]
            if (sampleRate != null && sampleRate is Int) {
                SAMPLE_RATE = sampleRate
            }
        }
        this._events = events
        startRecording()
    }

    override fun onCancel(p0: Any?) {
        Log.d(TAG, "onListen canceled")
        stopRecording()
    }

    public fun startRecording() {
        if (thread != null) return

        isCapturing = true
        val runnableObj: Runnable = object: Runnable {
          override public fun run() {
              record()
          }
        }
        thread = Thread(runnableObj)
        thread?.start()
    }

    public fun stopRecording() {
        if (thread == null) return
        isCapturing = false
        thread = null
    }

	private fun allToReal(obuf: DoubleArray, ibuf: FloatArray, bWeight: FloatArray, n: Int){
		var i:Int
		for(i in 0..n-1){
			var out : Float
			out = ibuf[2*i]*ibuf[2*i]+ibuf[2*i+1]*ibuf[2*i+1]
			obuf[i] = 10*log10(out.toDouble()) + bWeight[i]
		}
	}

	private fun bWeighting(freq: Float):Float{
    	val f2 = freq * freq
    	val bw = 1.019764760044717 * 148840000 *
        	freq * f2 / ((f2 + 424.36) * sqrt(f2 + 25122.25) * (f2 + 148840000))
		return bw.toFloat()
	}

    private fun record() {
		val FFT_CNT = 4096
		val FFT_SIZE =  FFT_CNT*4
        val TEMPO = 120
        val TICK_CNT = 60*SAMPLE_RATE/TEMPO/4;  // 4 note per tempo
        val TICK_SIZE = TICK_CNT*4;
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        var bufferSize: Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
		if(FFT_SIZE>bufferSize){
			bufferSize = FFT_SIZE;
		}
        if(TICK_SIZE>bufferSize){
            bufferSize = TICK_SIZE;
        }
        Log.d(TAG, "bufferSize: $bufferSize, $FFT_SIZE, $TICK_SIZE")
		val fft = FloatFFT_1D(FFT_CNT.toLong())
        val audioBuffer: FloatArray = FloatArray(bufferSize/4)
        val record: AudioRecord = AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                        .setAudioFormat(
                          AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            _events?.error("AUDIO_RECORD_INITIALIZE_ERROR", "AudioRecord can't initialize", null)
        }
		
		var bWeight: FloatArray = FloatArray(FFT_CNT)
		var i: Int
		for(i in 0..FFT_CNT-1){
			bWeight[i] = bWeighting(SAMPLE_RATE.toFloat()*i/FFT_CNT)
		}

        record.startRecording()
		var remainItems = 0
        val fftBuffer: FloatArray = FloatArray(FFT_CNT)
        while (isCapturing) {
            try {
                record.read(audioBuffer, remainItems, audioBuffer.size-remainItems, AudioRecord.READ_BLOCKING)
            } catch (e: Exception) {
                Log.d(TAG, e.toString())
            }

            audioBuffer.copyInto(fftBuffer,0,0,FFT_CNT)
			fft.realForward(fftBuffer)
			val obuf: DoubleArray = DoubleArray(FFT_CNT/2)
			allToReal(obuf,fftBuffer,bWeight, FFT_CNT/2)

			if(bufferSize>TICK_SIZE){
				audioBuffer.copyInto(audioBuffer, 0, TICK_CNT, audioBuffer.size) 
                remainItems = audioBuffer.size - TICK_CNT
			}

            uiThreadHandler.post(object: Runnable {
                override fun run() {
                    if (isCapturing) {
                        _events?.success(obuf.toList())
                    }
                }
            })
        }

        record.stop()
        record.release()
    }
}
