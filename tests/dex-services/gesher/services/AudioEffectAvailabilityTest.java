/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Visualizer;
import java.util.Arrays;

/** Public API contract for the current mixer, which has no effect factory. */
public final class AudioEffectAvailabilityTest {
    private static int checks;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private static void unsupported(Runnable constructor, String label) {
        try { constructor.run(); }
        catch (UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime");
        MediaTestBootstrap.initialize("audio-effect-availability-contract");
        AudioEffect.Descriptor[] effects = AudioEffect.queryEffects();
        check(effects != null && effects.getClass() == AudioEffect.Descriptor[].class && effects.length == 0,
                "unavailable effects enumerate an empty typed array");
        check(!AudioEffect.isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_AEC), "AEC is unavailable");
        check(!AudioEffect.isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_AGC), "AGC is unavailable");
        check(!AudioEffect.isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_NS), "NS is unavailable");
        check(AudioEffect.queryPreProcessings(0) == null, "no default session preprocessing");
        check(Arrays.equals(Visualizer.getCaptureSizeRange(), new int[] {128, 1024}),
                "visualizer capture bounds match platform constants");
        check(Visualizer.getMaxCaptureRate() == 20000, "visualizer maximum capture rate in millihertz");
        for (int i = 0; i < 8; i++) {
            unsupported(() -> new Equalizer(0, 0), "Equalizer requires an effect engine");
            unsupported(() -> new Visualizer(0), "Visualizer requires an effect engine");
        }
        System.gc(); System.runFinalization();
        System.out.println("AudioEffectAvailabilityTest all passed: " + checks + " checks");
        // In-process services own workers; this isolated fixture has no event loop.
        System.exit(0);
    }
}
