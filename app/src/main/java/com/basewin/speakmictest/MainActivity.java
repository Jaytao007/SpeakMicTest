package com.basewin.speakmictest;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private static final int PERMISSION_REQUEST_CODE = 1;
    private static double TARGET_FREQUENCY = 1000; // Hz，标准频率（分析的是1000Hz）
    private static final double RESOLUTION = 10; // Hz，误差

    private static final String FILE_NAME = "MainMicRecord";
    private File mSampleFile;
    private static final long RECORD_TIME = 10000; // ms 1KHz need 10s at least and 1.5KHz/2KHz need 5s at least.
    private static final int SAMPLE_RATE = 44100;// Hz，采样频率
    private int bufferSize = 0;
    private AudioRecord mAudioRecord;
    private MediaPlayer mMediaPlayer;
    private TextView mResultTV;

    enum FREQUENCY {
        TARGET_1KHZ, TARGET_1_5KHZ, TARGET_2KHZ
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.activity_main);
        init();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {

            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "permission granted");
                }
            }
        }
    }

    private void init() {
        mResultTV = findViewById(R.id.result_tv);

        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.reset();

        AssetFileDescriptor afd;
        Random random = new Random();
        FREQUENCY frequencies[] = FREQUENCY.values();
        switch (frequencies[random.nextInt(frequencies.length)]) {
            case TARGET_1_5KHZ:
                TARGET_FREQUENCY = 1500;
                afd = getResources().openRawResourceFd(R.raw.frequency_1_5khz);
                break;
            case TARGET_2KHZ:
                TARGET_FREQUENCY = 2000;
                afd = getResources().openRawResourceFd(R.raw.frequency_2khz);
                break;
            case TARGET_1KHZ:
            default:
                TARGET_FREQUENCY = 1000;
                afd = getResources().openRawResourceFd(R.raw.frequency_1khz);
                break;
        }
        Log.d(TAG, "target frequency = " + TARGET_FREQUENCY);
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setLooping(true);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mMediaPlayer.setDataSource(afd);
            }
            mMediaPlayer.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        playAudio();
        startRecord();
        try {
            Thread.sleep(RECORD_TIME);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        stopRecord();
        frequencyAnalyse();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopAudio();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        release();
    }

    private void playAudio() {
        mMediaPlayer.start();
    }

    private void stopAudio() {
        if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
        }
    }

    private void release() {
        mMediaPlayer.release();
    }

    private void startRecord() {
        mSampleFile = new File(getFilesDir() + "/" + FILE_NAME);
        if (mSampleFile.exists()) {
            if (!mSampleFile.delete()) {
                return;
            }
        }
        try {
            if (!mSampleFile.createNewFile()) {
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);
        mAudioRecord.startRecording();
        new Thread(new AudioRecordThread()).start();
    }

    private void stopRecord() {
        mAudioRecord.stop();
    }

    private void frequencyAnalyse() {
        if(mSampleFile == null) {
            return;
        }
        try {
            DataInputStream inputStream = new DataInputStream(new FileInputStream(mSampleFile));
            short[] buffer=new short[SAMPLE_RATE];
            for(int i = 0;i<buffer.length;i++){
                buffer[i] = inputStream.readShort();
            }
            short[] data = new short[FFT.FFT_N];
            System.arraycopy(buffer, buffer.length - FFT.FFT_N,
                    data, 0, FFT.FFT_N);
            double frequency = FFT.GetFrequency(data);
            if(Math.abs(frequency - TARGET_FREQUENCY) < RESOLUTION){
                //测试通过
                Log.d(TAG, "pass frequency = " + frequency);
                mResultTV.setText(R.string.pass);
            }else{
                //测试失败
                Log.d(TAG, "fail frequency = " + frequency);
                mResultTV.setText(R.string.fail);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class  AudioRecordThread implements Runnable{
        @Override
        public void run() {
            short[] audioData = new short[bufferSize/2];
            DataOutputStream fos = null;
            try {
                fos = new DataOutputStream(new FileOutputStream(mSampleFile));
                int readSize;
                while (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING){
                    readSize = mAudioRecord.read(audioData,0,audioData.length);
                    if(AudioRecord.ERROR_INVALID_OPERATION != readSize){
                        for(int i = 0;i<readSize;i++){
                            fos.writeShort(audioData[i]);
                            fos.flush();
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if(fos!=null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                mAudioRecord.release();
                mAudioRecord = null;
            }
        }
    };
}