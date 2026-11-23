/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** Record calls (#732): the call's people, the engine's frames, the mix's queues and the button's clock. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class CallRecorderTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before public void prepare() {
        CallRecorder.resetForTests();
        HookStatus.clear();
    }

    @After public void restore() {
        CallRecorder.resetForTests();
        HookStatus.clear();
    }

    // The call's people, shaped like the engine's CallModel and CallParticipant.

    public static final class Participant {
        public String userId;
        public String username;
        public boolean isService;

        Participant(String userId, String username, boolean isService) {
            this.userId = userId;
            this.username = username;
            this.isService = isService;
        }
    }

    public static final class Model {
        public Participant selfParticipant;
        public ArrayList<Participant> remoteParticipants = new ArrayList<>();
    }

    @Test public void peopleAreYouThenEveryoneElseOnceWithoutTheServiceParticipant() throws Exception {
        Model model = new Model();
        model.selfParticipant = new Participant("1", "me", false);
        model.remoteParticipants.add(new Participant("2", "friend", false));
        model.remoteParticipants.add(new Participant("bot", "", true));
        model.remoteParticipants.add(new Participant("1", "me again", false));
        model.remoteParticipants.add(null);
        model.remoteParticipants.add(new Participant("", "nobody", false));
        model.remoteParticipants.add(new Participant("3", "", false));

        List<CallRecorder.Person> people = CallRecorder.people(model);

        assertEquals(3, people.size());
        assertEquals("1", people.get(0).id);
        assertEquals("me", people.get(0).username);
        assertTrue(people.get(0).self);
        assertEquals("2", people.get(1).id);
        assertEquals("friend", people.get(1).username);
        assertFalse(people.get(1).self);
        assertEquals("3", people.get(2).id);
        assertNull("an empty username is none", people.get(2).username);
    }

    @Test public void aCallWithNoOneYetHasNoPeople() throws Exception {
        assertTrue(CallRecorder.people(new Model()).isEmpty());
    }

    // The audio API's sinks.

    public static final class Api {
        public void addSink(String id, int streamType, String notASink) { }
        public void addSink(String id, int streamType, Object sink) { }
        public void addSink(String id, long streamType, Object sink) { }
        public void removeSink(String id, int streamType, Object sink) { }
    }

    @Test public void theSinkMethodTakesAnIdTheStreamTypeAndTheSink() throws Exception {
        CallAudioSink sink = new CallRecorder.Track("1").sink;
        Method add = CallRecorder.sinkMethod(new Api(), "addSink", sink);
        assertEquals(Arrays.asList(String.class, int.class, Object.class), Arrays.asList(add.getParameterTypes()));
        Method remove = CallRecorder.sinkMethod(new Api(), "removeSink", sink);
        assertEquals("removeSink", remove.getName());
        try {
            CallRecorder.sinkMethod(new Api(), "addSinks", sink);
            fail("no such method");
        } catch (NoSuchMethodException expected) {
            // The engine without it is refused, not guessed at.
        }
    }

    // The engine's frames, shaped like AudioFrame, its metadata and its data: abstract parents the
    // engine's proxy classes override.

    public abstract static class Metadata {
        public abstract int getSampleRate();
        public abstract int getNumberOfChannels();
        public abstract int getNumberOfSamples();
        public abstract boolean getIsMuted();
    }

    public static final class MetadataProxy extends Metadata {
        final int rate, channels, samples;
        final boolean muted;

        MetadataProxy(int rate, int channels, int samples, boolean muted) {
            this.rate = rate;
            this.channels = channels;
            this.samples = samples;
            this.muted = muted;
        }

        @Override public int getSampleRate() { return rate; }
        @Override public int getNumberOfChannels() { return channels; }
        @Override public int getNumberOfSamples() { return samples; }
        @Override public boolean getIsMuted() { return muted; }
    }

    public abstract static class Data {
        public abstract short[] getAudioData(int count);
    }

    public static final class DataProxy extends Data {
        final List<Integer> asked = new ArrayList<>();
        final short value;

        DataProxy(short value) {
            this.value = value;
        }

        @Override public short[] getAudioData(int count) {
            asked.add(count);
            short[] pcm = new short[count];
            Arrays.fill(pcm, value);
            return pcm;
        }
    }

    public abstract static class Frame {
        public abstract Metadata getMetadata();
        public abstract Data getAudioData();
    }

    public static final class FrameProxy extends Frame {
        final Metadata metadata;
        final DataProxy data;

        FrameProxy(Metadata metadata, DataProxy data) {
            this.metadata = metadata;
            this.data = data;
        }

        @Override public Metadata getMetadata() { return metadata; }
        @Override public Data getAudioData() { return data; }
    }

    public static final class BrokenFrame extends Frame {
        @Override public Metadata getMetadata() { throw new IllegalStateException("gone"); }
        @Override public Data getAudioData() { return null; }
    }

    @Test public void aStereoFrameIsReadWhole() {
        CallRecorder.Track track = new CallRecorder.Track("2");
        DataProxy data = new DataProxy((short) 16384);
        CallRecorder.frame(track, new FrameProxy(new MetadataProxy(48_000, 2, 480, false), data));
        assertEquals("both channels of 480", Collections.singletonList(960), data.asked);
        assertEquals(480, track.queuedForTests());
        assertEquals(1, track.frames);
    }

    @Test public void aMutedFrameIsSilenceAndNeverRead() {
        CallRecorder.Track track = new CallRecorder.Track("2");
        DataProxy data = new DataProxy((short) 16384);
        CallRecorder.frame(track, new FrameProxy(new MetadataProxy(48_000, 1, 480, true), data));
        assertTrue(data.asked.isEmpty());
        assertEquals("the time still passes", 480, track.queuedForTests());
        assertEquals(1, track.frames);
    }

    @Test public void anOddFrameIsCountedOnceAndNeverRead() {
        CallRecorder.Track track = new CallRecorder.Track("2");
        DataProxy data = new DataProxy((short) 1);
        CallRecorder.frame(track, new FrameProxy(new MetadataProxy(1_000, 1, 10, false), data));
        CallRecorder.frame(track, new FrameProxy(new MetadataProxy(48_000, 1, 48_000, false), data));
        assertTrue(data.asked.isEmpty());
        assertEquals(0, track.frames);
        assertEquals(counted("odd frame 1"), HookStatus.report());
    }

    @Test public void aFrameThatThrowsIsCountedOnceAndNeverEscapes() {
        CallRecorder.Track track = new CallRecorder.Track("2");
        CallRecorder.frame(track, new BrokenFrame());
        CallRecorder.frame(track, new BrokenFrame());
        assertEquals(0, track.frames);
        assertEquals(counted("frame failed 1"), HookStatus.report());
    }

    @Test public void aClosedTrackTakesNoMoreFrames() {
        CallRecorder.Track track = new CallRecorder.Track("2");
        track.closed = true;
        DataProxy data = new DataProxy((short) 1);
        CallRecorder.frame(track, new FrameProxy(new MetadataProxy(48_000, 1, 480, false), data));
        CallRecorder.frame(track, null);
        assertTrue(data.asked.isEmpty());
        assertEquals(0, track.queuedForTests());
    }

    @Test public void theFrameMethodsAreTheTopmostPublicOnes() throws Exception {
        assertSame(Metadata.class, CallRecorder.topmost(MetadataProxy.class, "getSampleRate").getDeclaringClass());
        assertSame(Data.class, CallRecorder.topmost(DataProxy.class, "getAudioData", int.class).getDeclaringClass());
        try {
            CallRecorder.topmost(MetadataProxy.class, "getLoudness");
            fail("no such method");
        } catch (NoSuchMethodException expected) {
            // Nothing to read.
        }
    }

    private static List<String> counted(String counts) {
        return Collections.singletonList(FamilyNames.CALL_RECORDER + ": invoked 0, 0 found, 0 missing. Counted: " + counts);
    }

    // What a frame holds.

    @Test public void shortsToReadCountsEveryChannelOfATenMillisecondFrame() {
        assertEquals(480, CallRecorder.shortsToRead(48_000, 1, 480));
        assertEquals("per channel at 10 ms", 960, CallRecorder.shortsToRead(48_000, 2, 480));
        assertEquals("the whole buffer otherwise", 960, CallRecorder.shortsToRead(48_000, 2, 960));
        assertEquals(160, CallRecorder.shortsToRead(16_000, 1, 160));
        assertEquals("100 ms is the most", 4_800, CallRecorder.shortsToRead(48_000, 1, 4_800));
    }

    @Test public void shortsToReadRefusesWhatIsNoFrameOfSound() {
        assertEquals(0, CallRecorder.shortsToRead(7_999, 1, 80));
        assertEquals(0, CallRecorder.shortsToRead(96_001, 1, 960));
        assertEquals(0, CallRecorder.shortsToRead(48_000, 0, 480));
        assertEquals(0, CallRecorder.shortsToRead(48_000, 9, 480));
        assertEquals(0, CallRecorder.shortsToRead(48_000, 1, 0));
        assertEquals("past 100 ms", 0, CallRecorder.shortsToRead(48_000, 1, 4_801));
    }

    @Test public void downmixAveragesTheChannelsAndFillsWhatIsMissingWithSilence() {
        float[] into = new float[3];
        Arrays.fill(into, 9f);
        CallRecorder.downmix(new short[] {16384, -16384, 8192, 8192}, 3, 2, into);
        assertEquals(0f, into[0], 0f);
        assertEquals(0.25f, into[1], 0f);
        assertEquals("never past the buffer", 0f, into[2], 0f);

        Arrays.fill(into, 9f);
        CallRecorder.downmix(null, 3, 1, into);
        assertEquals(Arrays.toString(new float[3]), Arrays.toString(into));
    }

    @Test public void clipHoldsTheMixToFullScale() {
        assertEquals(Short.MAX_VALUE, CallRecorder.clip(1.5f));
        assertEquals(Short.MAX_VALUE, CallRecorder.clip(1f));
        assertEquals(-Short.MAX_VALUE, CallRecorder.clip(-2f));
        assertEquals(16384, CallRecorder.clip(0.5f));
        assertEquals(0, CallRecorder.clip(0f));
    }

    // Resampling.

    @Test public void sixteenKilohertzFramesBecomeAnEvenFortyEightKilohertzStream() {
        CallRecorder.Resampler resampler = new CallRecorder.Resampler();
        float[] in = new float[160];
        float[] out = new float[CallRecorder.Resampler.most(160, 16_000)];
        assertEquals("the first frame stops at its last sample", 478, resampler.process(in, 160, 16_000, out));
        for (int frame = 0; frame < 10; frame++) {
            assertEquals("each later one leads in from the one before", 480, resampler.process(in, 160, 16_000, out));
        }
    }

    @Test public void resamplingInterpolatesAcrossTheFrameBoundary() {
        CallRecorder.Resampler resampler = new CallRecorder.Resampler();
        float[] out = new float[CallRecorder.Resampler.most(2, 24_000)];
        assertEquals(3, resampler.process(new float[] {0f, 1f}, 2, 24_000, out));
        assertEquals(Arrays.toString(new float[] {0f, 0.5f, 1f}), Arrays.toString(Arrays.copyOf(out, 3)));
        assertEquals(4, resampler.process(new float[] {0f, 1f}, 2, 24_000, out));
        assertEquals("from the last frame's 1 down to 0, then up",
                Arrays.toString(new float[] {0.5f, 0f, 0.5f, 1f}), Arrays.toString(Arrays.copyOf(out, 4)));
    }

    @Test public void aNewRateStartsOver() {
        CallRecorder.Resampler resampler = new CallRecorder.Resampler();
        float[] out = new float[CallRecorder.Resampler.most(160, 16_000)];
        resampler.process(new float[160], 160, 16_000, out);
        assertEquals(480, resampler.process(new float[480], 480, 48_000, out));
        assertEquals(0, resampler.process(new float[0], 0, 48_000, out));
        assertEquals(0, resampler.process(new float[10], 10, 0, out));
    }

    // A person's queue.

    private static void push(CallRecorder.Track track, int frames) {
        short[] pcm = new short[480];
        Arrays.fill(pcm, (short) 16384);
        for (int frame = 0; frame < frames; frame++) track.push(48_000, 1, pcm, 480);
    }

    @Test public void aQueueFillsToSixtyMillisecondsBeforeItIsMixed() {
        CallRecorder.Track track = new CallRecorder.Track("2");
        push(track, 5);
        assertFalse(track.ready());
        float[] mix = new float[960];
        track.take(mix, 960);
        assertEquals("nothing taken yet", 0f, mix[0], 0f);
        assertEquals(2_400, track.queuedForTests());

        push(track, 1);
        assertEquals(CallRecorder.PRIME, track.queuedForTests());
        assertTrue(track.ready());
        track.take(mix, 960);
        assertEquals(0.5f, mix[0], 0f);
        assertEquals(0.5f, mix[959], 0f);
        assertEquals(1_920, track.queuedForTests());
    }

    @Test public void aQueueThatRunsDryLeavesSilenceAndFillsAgain() {
        CallRecorder.Track track = new CallRecorder.Track("2");
        push(track, 6);
        float[] mix = new float[3_000];
        track.take(mix, 3_000);
        assertEquals(0.5f, mix[CallRecorder.PRIME - 1], 0f);
        assertEquals("silent past the end", 0f, mix[CallRecorder.PRIME], 0f);
        assertEquals(0, track.queuedForTests());
        push(track, 1);
        assertFalse("it fills to PRIME again first", track.ready());
    }

    @Test public void aQueueRunningBehindIsCutBack() {
        CallRecorder.Track track = new CallRecorder.Track("2");
        push(track, 60);
        assertEquals(28_800, track.queuedForTests());
        track.take(new float[480], 480);
        assertEquals(CallRecorder.KEEP - 480, track.queuedForTests());
    }

    @Test public void aQueueNeverHoldsMoreThanTwoSeconds() {
        CallRecorder.Track track = new CallRecorder.Track("2");
        push(track, 250);
        assertEquals(CallRecorder.RATE * 2, track.queuedForTests());
        assertEquals(250, track.frames);
    }

    @Test public void mixingTwoPeopleAddsThem() {
        CallRecorder.Track one = new CallRecorder.Track("1");
        CallRecorder.Track two = new CallRecorder.Track("2");
        push(one, 6);
        push(two, 6);
        float[] mix = new float[480];
        one.take(mix, 480);
        two.take(mix, 480);
        assertEquals(1f, mix[0], 0f);
        assertEquals(Short.MAX_VALUE, CallRecorder.clip(mix[0]));
    }

    // The button's clock.

    @Test public void theClockReadsLikeTheCallScreens() {
        assertEquals("0:00", CallRecorder.clock(0L));
        assertEquals("0:00", CallRecorder.clock(-5L));
        assertEquals("0:59", CallRecorder.clock(59_999L));
        assertEquals("1:05", CallRecorder.clock(65_000L));
        assertEquals("1:02:05", CallRecorder.clock(3_725_000L));
    }

    // The crash guard (#831): the engine closed Instagram as a recording handed it its sinks.

    @Test public void aStartThatClosedInstagramTurnsRecordCallsOffOnceAndSaysSo() {
        Context app = RuntimeEnvironment.getApplication();
        Utils.setContext(app);
        Settings.CALL_RECORDER.save(true);
        ShadowToast.reset();
        try {
            File mark = CallRecorder.startingMark(app);
            CallRecorder.markStarting(app, true);
            assertTrue("the mark is down while the engine takes the sinks", mark.exists());
            assertTrue(CallRecorder.offAfterACrash(app));
            assertFalse("the mark is used up", mark.exists());
            assertFalse("Record calls is off", Settings.CALL_RECORDER.savedValue());
            ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS);
            assertEquals("Record calls closed Instagram, so it's off now.", ShadowToast.getTextOfLatestToast());
            assertTrue(HookStatus.report().toString().contains("turned off after a crash"));
            assertFalse("only once", CallRecorder.offAfterACrash(app));
        } finally {
            Settings.CALL_RECORDER.resetToDefault();
            CallRecorder.markStarting(app, false);
        }
    }

    @Test public void withoutTheMarkRecordCallsStaysOn() {
        Context app = RuntimeEnvironment.getApplication();
        Utils.setContext(app);
        Settings.CALL_RECORDER.save(true);
        try {
            CallRecorder.markStarting(app, false);
            assertFalse(CallRecorder.offAfterACrash(app));
            assertTrue(Settings.CALL_RECORDER.savedValue());
        } finally {
            Settings.CALL_RECORDER.resetToDefault();
        }
    }

    @Test public void theMarkComesAndGoesAndNeverThrows() {
        Context app = RuntimeEnvironment.getApplication();
        File mark = CallRecorder.startingMark(app);
        CallRecorder.markStarting(app, true);
        CallRecorder.markStarting(app, true);
        assertTrue(mark.exists());
        CallRecorder.markStarting(app, false);
        CallRecorder.markStarting(app, false);
        assertFalse(mark.exists());
        CallRecorder.markStarting(null, true);
        assertNull(CallRecorder.startingMark(null));
        assertFalse(CallRecorder.offAfterACrash(null));
    }

    // The engine's references.

    @Test public void theHooksCountTheirCalls() {
        CallRecorder.audioApi(new Api());
        CallRecorder.callModel(new Model());
        CallRecorder.callModel(null);
        assertEquals(Collections.singletonList(FamilyNames.CALL_RECORDER + ": invoked 3, 0 found, 0 missing"),
                HookStatus.report());
    }
}
