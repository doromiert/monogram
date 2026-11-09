// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.telegram.messenger.AndroidUtilities;

/** One sign-in owns its deadline, cancellation and exactly one completion. */
public final class MonogramLoginAttempt {
    private final Consumer<Exception> result;
    private final Runnable timeout = () -> stop(new SocketTimeoutException("XMPP sign-in timed out"));
    private Future<?> task;
    private Runnable abort;
    private boolean done, stopped;

    MonogramLoginAttempt(Consumer<Exception> result) { this(result,45000); }
    MonogramLoginAttempt(Consumer<Exception> result,long timeoutMillis) {
        this.result=result;
        AndroidUtilities.runOnUIThread(timeout,timeoutMillis);
    }
    synchronized void task(Future<?> task) { this.task=task;if(stopped)task.cancel(true); }
    synchronized void abort(Runnable abort) { this.abort=abort;if(stopped)abortAsync(abort); }
    public void cancel() { stop(new CancellationException("Sign-in cancelled")); }
    synchronized void check() { if(done || Thread.currentThread().isInterrupted())throw new CancellationException("Sign-in cancelled"); }
    private synchronized void stop(Exception error) {
        if(done)return;
        stopped=true;
        if(task!=null)task.cancel(true);
        if(abort!=null)abortAsync(abort);
        complete(error);
    }
    synchronized void complete(Exception error) {
        if(done)return;
        done=true;
        AndroidUtilities.cancelRunOnUIThread(timeout);
        AndroidUtilities.runOnUIThread(() -> result.accept(error));
    }
    private static void abortAsync(Runnable abort) {
        org.jivesoftware.smack.util.Async.go(() -> {try {abort.run();}catch(Exception ignored) {}},"Cancel XMPP sign-in");
    }
}
