/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Visualizer;
import java.util.Arrays;
public final class AudioEffectHostTest {
    private static int checks;
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; }
    private static void unsupported(Runnable constructor, String label) {
        try { constructor.run(); } catch (UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError(label);
    }
    public static void main(String[] args) {
        if (args[0].equals("queries")) {
            AudioEffect.Descriptor[] effects = AudioEffect.queryEffects();
            check(effects != null && effects.getClass() == AudioEffect.Descriptor[].class && effects.length == 0,
                    "empty capabilities retain Descriptor[] runtime type");
            check(!AudioEffect.isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_AEC), "AEC unavailable");
            check(!AudioEffect.isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_AGC), "AGC unavailable");
            check(!AudioEffect.isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_NS), "NS unavailable");
            check(AudioEffect.queryPreProcessings(0) == null, "no session preprocessing");
            int[] range = Visualizer.getCaptureSizeRange();
            check(Arrays.equals(range, new int[] {128, 1024}), "pinned visualizer capture bounds");
            range[0] = 9;
            check(Visualizer.getCaptureSizeRange()[0] == 128, "capture bounds array is owned by caller");
            check(Visualizer.getMaxCaptureRate() == 20000, "visualizer capture rate is millihertz");
        } else {
            for (int i = 0; i < 8; i++) {
                unsupported(() -> new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER, AudioEffect.EFFECT_TYPE_NULL, 0, 0), "audio effect cannot initialize without engine");
                unsupported(() -> new Visualizer(0), "visualizer cannot initialize without engine");
            }
            System.gc(); System.runFinalization();
        }
        System.out.println("Real framework audio effects " + args[0] + ": " + checks + " checks passed");
    }
}
