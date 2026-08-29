package com.caseythecoder.spring.startup;

import com.caseythecoder.spring.support.Recorder;

import org.springframework.context.SmartLifecycle;

/** A {@link SmartLifecycle} that does nothing except say when it was started and stopped. */
class RecordingLifecycle implements SmartLifecycle {

    private final Recorder recorder;

    private final String name;

    private final int phase;

    private final boolean autoStartup;

    private volatile boolean running;

    RecordingLifecycle(Recorder recorder, String name, int phase) {
        this(recorder, name, phase, true);
    }

    RecordingLifecycle(Recorder recorder, String name, int phase, boolean autoStartup) {
        this.recorder = recorder;
        this.name = name;
        this.phase = phase;
        this.autoStartup = autoStartup;
    }

    @Override
    public void start() {
        recorder.record(name + ": start");
        this.running = true;
    }

    @Override
    public void stop() {
        recorder.record(name + ": stop");
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public int getPhase() {
        return this.phase;
    }

    @Override
    public boolean isAutoStartup() {
        return this.autoStartup;
    }
}
