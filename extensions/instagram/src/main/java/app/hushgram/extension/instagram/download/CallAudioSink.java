/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

/**
 * One person's sound on a call, for Record calls (#732). A recording hands one to the call engine's
 * {@code addSink} per person, and the engine gives it each decoded frame of that person's stream.
 *
 * <p>It's written against Object, since the engine's sink type is Instagram's. The Record calls
 * patch makes Instagram's {@code com.facebook.rsys.audio.gen.AudioSourceSink} its superclass, points
 * this constructor's super call at that class's, and adds the three methods the engine calls:
 * {@code onFrame}, which hands the frame to {@link #frame}, and {@code onEncodedPacket} and
 * {@code onPositionUpdate}, which do nothing.
 */
public final class CallAudioSink {
    final CallRecorder.Track track;

    CallAudioSink(CallRecorder.Track track) {
        this.track = track;
    }

    /** A frame of this person's sound, on the engine's audio thread. Never throws. */
    public void frame(Object audioFrame) {
        CallRecorder.frame(track, audioFrame);
    }
}
