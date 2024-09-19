package com.otaliastudios.transcoder.demo;

import static com.otaliastudios.transcoder.internal.utils.TrackMapKt.trackMapOf;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;
import com.otaliastudios.transcoder.TranscoderOptions;
import com.otaliastudios.transcoder.common.TrackStatus;
import com.otaliastudios.transcoder.common.TrackType;
import com.otaliastudios.transcoder.internal.utils.Logger;
import com.otaliastudios.transcoder.internal.utils.TrackMap;
import com.otaliastudios.transcoder.resize.AspectRatioResizer;
import com.otaliastudios.transcoder.resize.FractionResizer;
import com.otaliastudios.transcoder.resize.PassThroughResizer;
import com.otaliastudios.transcoder.source.DataSource;
import com.otaliastudios.transcoder.source.TrimDataSource;
import com.otaliastudios.transcoder.source.UriDataSource;
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy;
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy;
import com.otaliastudios.transcoder.strategy.RemoveTrackStrategy;
import com.otaliastudios.transcoder.strategy.TrackStrategy;
import com.otaliastudios.transcoder.time.TimeInterpolator;
import com.otaliastudios.transcoder.validator.DefaultValidator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import kotlin.collections.ArraysKt;


public class TranscoderActivity extends AppCompatActivity implements TranscoderListener {
    private static final String TAG = "TranscoderActivity";
    private static final Logger LOG = new Logger("TranscoderActivity");

    private static final String FILE_PROVIDER_AUTHORITY = "com.otaliastudios.transcoder.demo.fileprovider";
    private static final int REQUEST_CODE_PICK = 1;
    private static final int REQUEST_CODE_PICK_AUDIO = 5;
    private static final int PROGRESS_BAR_MAX = 1000;

    private RadioGroup mAudioChannelsGroup;
    private RadioGroup mAudioSampleRateGroup;
    private RadioGroup mVideoFramesGroup;
    private RadioGroup mVideoResolutionGroup;
    private RadioGroup mVideoAspectGroup;
    private RadioGroup mVideoRotationGroup;
    private RadioGroup mSpeedGroup;
    private RadioGroup mAudioReplaceGroup;

    private ProgressBar mProgressView;
    private TextView mButtonView;
    private EditText mTrimStartView;
    private EditText mTrimEndView;
    private TextView mAudioReplaceView;

    private boolean mIsTranscoding;
    private boolean mIsAudioOnly;
    private Future<Void> mTranscodeFuture;
    private Uri mAudioReplacementUri;
    private File mTranscodeOutputFile;
    private long mTranscodeStartTime;
    private TrackStrategy mTranscodeVideoStrategy;
    private TrackStrategy mTranscodeAudioStrategy;
    private long mTrimStartUs = 0;
    private long mTrimEndUs = 0;

    private final RadioGroup.OnCheckedChangeListener mRadioGroupListener = (group, checkedId) -> syncParameters();

    private final TextWatcher mTextListener = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            syncParameters();
        }
    };

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.setLogLevel(Logger.LEVEL_VERBOSE);
        setContentView(R.layout.activity_transcoder);

        mButtonView = findViewById(R.id.button);
        mButtonView.setOnClickListener(v -> {
            if (!mIsTranscoding) {
                Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(intent, REQUEST_CODE_PICK);
            } else {
                mTranscodeFuture.cancel(true);
            }
        });
        setIsTranscoding(false);

        mProgressView = findViewById(R.id.progress);
        mProgressView.setMax(PROGRESS_BAR_MAX);

        mTrimStartView = findViewById(R.id.trim_start);
        mTrimEndView = findViewById(R.id.trim_end);
        mAudioReplaceView = findViewById(R.id.replace_info);

        mAudioChannelsGroup = findViewById(R.id.channels);
        mVideoFramesGroup = findViewById(R.id.frames);
        mVideoResolutionGroup = findViewById(R.id.resolution);
        mVideoAspectGroup = findViewById(R.id.aspect);
        mVideoRotationGroup = findViewById(R.id.rotation);
        mSpeedGroup = findViewById(R.id.speed);
        mAudioSampleRateGroup = findViewById(R.id.sampleRate);
        mAudioReplaceGroup = findViewById(R.id.replace);

        mAudioChannelsGroup.setOnCheckedChangeListener(mRadioGroupListener);
        mVideoFramesGroup.setOnCheckedChangeListener(mRadioGroupListener);
        mVideoResolutionGroup.setOnCheckedChangeListener(mRadioGroupListener);
        mVideoAspectGroup.setOnCheckedChangeListener(mRadioGroupListener);
        mAudioSampleRateGroup.setOnCheckedChangeListener(mRadioGroupListener);
        mTrimStartView.addTextChangedListener(mTextListener);
        mTrimEndView.addTextChangedListener(mTextListener);
        syncParameters();

        mAudioReplaceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            mAudioReplacementUri = null;
            mAudioReplaceView.setText("No replacement selected.");
            if (checkedId == R.id.replace_yes) {
                if (!mIsTranscoding) {
                    startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("audio/*"), REQUEST_CODE_PICK_AUDIO);
                }
            }
            mRadioGroupListener.onCheckedChanged(group, checkedId);
        });


    }

    private void syncParameters() {
        int channels = DefaultAudioStrategy.CHANNELS_AS_INPUT;
        int channelsId = mAudioChannelsGroup.getCheckedRadioButtonId();
        if (channelsId == R.id.channels_mono) channels = 1;
        else if (channelsId == R.id.channels_stereo) channels = 2;

        int sampleRate = DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT;
        int sampleRateId = mAudioSampleRateGroup.getCheckedRadioButtonId();
        if (sampleRateId == R.id.sampleRate_32) sampleRate = 32000;
        else if (sampleRateId == R.id.sampleRate_48) sampleRate = 48000;

        boolean removeAudio = false;
        int removeAudioId = mAudioReplaceGroup.getCheckedRadioButtonId();
        if (removeAudioId == R.id.replace_remove) removeAudio = true;
        else if (removeAudioId == R.id.replace_yes) removeAudio = false;

        if (removeAudio) {
            mTranscodeAudioStrategy = new RemoveTrackStrategy();
        } else {
            mTranscodeAudioStrategy = DefaultAudioStrategy.builder().channels(channels).sampleRate(44100).build();
        }

        int frames = DefaultVideoStrategy.DEFAULT_FRAME_RATE;
        int framesId = mVideoFramesGroup.getCheckedRadioButtonId();
        if (framesId == R.id.frames_24) frames = 24;
        else if (framesId == R.id.frames_30) frames = 30;
        else if (framesId == R.id.frames_60) frames = 60;

        float fraction = 1F;
        int fractionId = mVideoResolutionGroup.getCheckedRadioButtonId();
        if (fractionId == R.id.resolution_half) fraction = 0.5F;
        else if (fractionId == R.id.resolution_third) fraction = 1F / 3F;

        float aspectRatio = 0F;
        int aspectRatioId = mVideoAspectGroup.getCheckedRadioButtonId();
        if (aspectRatioId == R.id.aspect_169) aspectRatio = 16F / 9F;
        else if (aspectRatioId == R.id.aspect_43) aspectRatio = 4F / 3F;
        else if (aspectRatioId == R.id.aspect_square) aspectRatio = 1F;

        mTranscodeVideoStrategy = new DefaultVideoStrategy.Builder().addResizer(aspectRatio > 0 ? new AspectRatioResizer(aspectRatio) : new PassThroughResizer()).addResizer(new FractionResizer(fraction)).frameRate(frames)
                // .keyFrameInterval(4F)
                .build();

        try {
            mTrimStartUs = Long.valueOf(mTrimStartView.getText().toString()) * 1000000;
        } catch (NumberFormatException e) {
            mTrimStartUs = 0;
            LOG.w("Failed to read trimStart value.", e);
        }
        try {
            mTrimEndUs = Long.valueOf(mTrimEndView.getText().toString()) * 1000000;
        } catch (NumberFormatException e) {
            mTrimEndUs = 0;
            LOG.w("Failed to read trimEnd value.", e);
        }
        if (mTrimStartUs < 0) mTrimStartUs = 0;
        if (mTrimEndUs < 0) mTrimEndUs = 0;
    }

    private void setIsTranscoding(boolean isTranscoding) {
        mIsTranscoding = isTranscoding;
        mButtonView.setText(mIsTranscoding ? "Cancel Transcoding" : "Select Video & Transcode");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK && resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                ClipData clipData = data.getClipData();
                List<Uri> uris = new ArrayList<>();
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    uris.add(clipData.getItemAt(i).getUri());
                }
                transcode(uris.toArray(new Uri[0]));
            } else if (data.getData() != null) {
                transcode(data.getData());
            }
        }
        if (requestCode == REQUEST_CODE_PICK_AUDIO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            mAudioReplacementUri = data.getData();
            mAudioReplaceView.setText(mAudioReplacementUri.toString());

        }
    }

    private void transcode(@NonNull Uri... uris) {
        // Create a temporary file for output.
        try {
            File outputDir = new File(getExternalFilesDir(null), "outputs");
            //noinspection ResultOfMethodCallIgnored
            outputDir.mkdir();
            mTranscodeOutputFile = File.createTempFile("transcode_test", ".mp4", outputDir);
            LOG.i("Transcoding into " + mTranscodeOutputFile);
        } catch (IOException e) {
            LOG.e("Failed to create temporary file.", e);
            Toast.makeText(this, "Failed to create temporary file.", Toast.LENGTH_LONG).show();
            return;
        }

        int rotation = 0;
        int rotationId = mVideoRotationGroup.getCheckedRadioButtonId();
        if (rotationId == R.id.rotation_90) rotation = 90;
        else if (rotationId == R.id.rotation_180) rotation = 180;
        else if (rotationId == R.id.rotation_270) rotation = 270;

        float speed = 1F;
        int speedId = mSpeedGroup.getCheckedRadioButtonId();
        if (speedId == R.id.speed_05x) speed = 0.5F;
        else if (speedId == R.id.speed_2x) speed = 2F;

        // Launch the transcoding operation.
        mTranscodeStartTime = SystemClock.uptimeMillis();
        setIsTranscoding(true);
        LOG.e("Building transcoding options...");
        TranscoderOptions.Builder builder = Transcoder.into(mTranscodeOutputFile.getAbsolutePath());
        List<DataSource> sources = ArraysKt.map(uris, uri -> new UriDataSource(this, uri));
        sources.set(0, new TrimDataSource(sources.get(0), mTrimStartUs, mTrimEndUs));
        if (mAudioReplacementUri == null) {
            for (DataSource source : sources) {
                builder.addDataSource(source);
            }
        } else {
            for (DataSource source : sources) {
                builder.addDataSource(TrackType.VIDEO, source);
            }
            builder.addDataSource(TrackType.AUDIO, this, mAudioReplacementUri);
        }
        LOG.e("Starting transcoding!");
        PathInterpolator interpolator = new PathInterpolator(0.5f, 0, 0.5f, 1);

        MediaExtractor extrac = new MediaExtractor();
        try {
            extrac.setDataSource(this, uris[0], null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        extrac.selectTrack(0);
        MediaFormat mediaFormat = extrac.getTrackFormat(0);
        long videoDuration = mediaFormat.getLong(MediaFormat.KEY_DURATION);
        long audioDuration = extrac.getTrackFormat(1).getLong(MediaFormat.KEY_DURATION);
//        long audioDuration = 8126983L;
        Log.d(TAG, "transcode: video duration: " + videoDuration + ", audio duration: " + audioDuration);
        final long[] videoTimeStamp = {Long.MIN_VALUE, Long.MIN_VALUE};
        final long[] audioTimeStamp = {Long.MIN_VALUE, Long.MIN_VALUE};

        TrackMap<TrackData> trackData = trackMapOf(new TrackData(videoDuration), new TrackData(audioDuration));
        TimeInterpolator timeInterpolator = new TimeInterpolator() {
            @Override
            public long interpolate(@NonNull TrackType type, long time) {
                TrackData data = trackData.get(type);
                if (data.lastRealTime == Long.MIN_VALUE) {
                    data.lastRealTime = time;
                    data.lastCorrectedTime = time;
                } else {
                    long realDelta = time - data.lastRealTime;
                    float interpolatedSpeed = (interpolator.getInterpolation((float) time / data.duration)) * (1 - 0.1f) + 0.1f;
//                    float interpolatedSpeed = 0.5f;
                    long correctedDelta = (long) ((double) realDelta / interpolatedSpeed);
                    data.lastRealTime = time;
                    data.lastCorrectedTime += correctedDelta;
                }
                Log.d(TAG, type + " time interpolate: " + ", real time: " + data.lastRealTime + ", corrected time: " + data.lastCorrectedTime);
                return data.lastCorrectedTime;

            }
        };
        mTranscodeFuture = builder.setListener(this)
                .setAudioTrackStrategy(mTranscodeAudioStrategy)
                .setVideoTrackStrategy(mTranscodeVideoStrategy)
                .setVideoRotation(rotation)
                .setTimeInterpolator(timeInterpolator)
                .setValidator(new DefaultValidator() {
            @Override
            public boolean validate(@NonNull TrackStatus videoStatus, @NonNull TrackStatus audioStatus) {
                mIsAudioOnly = !videoStatus.isTranscoding();
                return super.validate(videoStatus, audioStatus);
            }
        }).transcode();
    }

    @Override
    public void onTranscodeProgress(double progress) {
        if (progress < 0) {
            mProgressView.setIndeterminate(true);
        } else {
            mProgressView.setIndeterminate(false);
            mProgressView.setProgress((int) Math.round(progress * PROGRESS_BAR_MAX));
        }
    }

    @Override
    public void onTranscodeCompleted(int successCode) {
        if (successCode == Transcoder.SUCCESS_TRANSCODED) {
            LOG.w("Transcoding took " + (SystemClock.uptimeMillis() - mTranscodeStartTime) + "ms");
            onTranscodeFinished(true, "Transcoded file placed on " + mTranscodeOutputFile);
            File file = mTranscodeOutputFile;
            String type = mIsAudioOnly ? "audio/mp4" : "video/mp4";
            Uri uri = FileProvider.getUriForFile(TranscoderActivity.this, FILE_PROVIDER_AUTHORITY, file);
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } else if (successCode == Transcoder.SUCCESS_NOT_NEEDED) {
            LOG.i("Transcoding was not needed.");
            onTranscodeFinished(true, "Transcoding not needed, source file untouched.");
        }
    }

    @Override
    public void onTranscodeCanceled() {
        onTranscodeFinished(false, "Transcoder canceled.");
    }

    @Override
    public void onTranscodeFailed(@NonNull Throwable exception) {
        onTranscodeFinished(false, "Transcoder error occurred. " + exception.getMessage());
    }

    private void onTranscodeFinished(boolean isSuccess, String toastMessage) {
        mProgressView.setIndeterminate(false);
        mProgressView.setProgress(isSuccess ? PROGRESS_BAR_MAX : 0);
        setIsTranscoding(false);
        Toast.makeText(TranscoderActivity.this, toastMessage, Toast.LENGTH_LONG).show();
    }


    private static class TrackData {
        public TrackData(long duration) {
            this.duration = duration;
        }

        long duration;
        private long lastRealTime = Long.MIN_VALUE;
        private long lastCorrectedTime = Long.MIN_VALUE;
    }
}
