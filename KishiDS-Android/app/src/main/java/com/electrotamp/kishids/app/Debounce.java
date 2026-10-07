package com.electrotamp.kishids.app;

import android.os.Handler;

/** A restartable one-shot timer on the main thread (the Android counterpart of a WPF DispatcherTimer that is stopped and started again). */
final class Debounce {
    private final Handler handler;
    private final Runnable action;
    private final Runnable fire;
    private boolean pending;

    Debounce(Handler handler, Runnable action) {
        this.handler = handler;
        this.action = action;
        this.fire = () -> { pending = false; this.action.run(); };
    }

    /** (Re)start the countdown. */
    void restart(long ms) {
        handler.removeCallbacks(fire);
        pending = true;
        handler.postDelayed(fire, ms);
    }

    /** Start only if not already counting down. */
    void startIfIdle(long ms) { if (!pending) restart(ms); }

    void cancel() {
        handler.removeCallbacks(fire);
        pending = false;
    }

    boolean isPending() { return pending; }
}
