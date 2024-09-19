package com.otaliastudios.transcoder.stretch;

import androidx.annotation.NonNull;

import java.nio.ShortBuffer;
import java.util.Random;

/**
 * A {@link AudioStretcher} meant to be used when output size is bigger than the input.
 * It will insert noise samples to fill the gaps, at regular intervals.
 * This modifies the audio pitch of course.
 */
public class InsertAudioStretcher implements AudioStretcher {
    private static final String TAG = "InsertAudioStretcher";
    private final static Random NOISE = new Random();

    private static short noise() {
        return (short) NOISE.nextInt(300);
    }

    short first;
    short second;

    private static float ratio(int remaining, int all) {
        return (float) remaining / all;
    }

    @Override
    public void stretch(@NonNull ShortBuffer input, @NonNull ShortBuffer output, int channels) {
        if (input.remaining() >= output.remaining()) {
            throw new IllegalArgumentException("Illegal use of AudioStretcher.INSERT");
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("Illegal use of AudioStretcher.INSERT. Channels:" + channels);
        }
        final int inputSamples = input.remaining() / channels;
        final int fakeSamples = (int) Math.floor((double) (output.remaining() - input.remaining()) / channels);
        int remainingInputSamples = inputSamples;
        int remainingFakeSamples = fakeSamples;
        float remainingInputSamplesRatio = ratio(remainingInputSamples, inputSamples);
        float remainingFakeSamplesRatio = ratio(remainingFakeSamples, fakeSamples);

        int oneInputStretchingTo = fakeSamples / inputSamples;

        while (remainingInputSamples > 0 && remainingFakeSamples > 0) {
//            Log.d(TAG, "stretch: remainingInputSamplesRatio: " + remainingInputSamplesRatio + ", remainingFakeSamplesRatio: " + remainingFakeSamplesRatio);
            // Will this be an input sample or a fake sample?
            // Choose the one with the bigger ratio.
//            if (remainingInputSamplesRatio >= remainingFakeSamplesRatio) {
//                Log.d(TAG, "stretch: remainingInputSamplesRatio >= remainingFakeSamplesRatio true");
            first = input.get();
            if (channels == 2) {
                second = input.get();
            }
            for (int i = 0; i < oneInputStretchingTo+1; i++) {
                if (channels == 2) {
                    output.put(second);
                    output.put(first);
                } else {
                    output.put(first);
                }
            }
            remainingInputSamples--;
            remainingInputSamplesRatio = ratio(remainingInputSamples, inputSamples);
//            } else {
//                if (channels == 2) {
//
//                    output.put(second);
//                    output.put(first);
//                } else {
//                    output.put(first);
//                }
//                remainingFakeSamples--;
//                remainingFakeSamplesRatio = ratio(remainingFakeSamples, inputSamples);
//            }
        }
    }
}
