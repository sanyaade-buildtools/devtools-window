/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.ui;

import org.mozilla.gecko.GeckoApp;
import org.mozilla.gecko.GeckoAppShell;
import org.mozilla.gecko.GeckoEvent;
import org.mozilla.gecko.PrefsHelper;
import org.mozilla.gecko.ZoomConstraints;
import org.mozilla.gecko.gfx.ImmutableViewportMetrics;
import org.mozilla.gecko.gfx.PointUtils;
import org.mozilla.gecko.gfx.ViewportMetrics;
import org.mozilla.gecko.util.EventDispatcher;
import org.mozilla.gecko.util.FloatUtils;
import org.mozilla.gecko.util.GeckoEventListener;

import org.json.JSONObject;

import android.graphics.PointF;
import android.graphics.RectF;
import android.util.FloatMath;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;

import java.util.Arrays;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;

/*
 * Handles the kinetic scrolling and zooming physics for a layer controller.
 *
 * Many ideas are from Joe Hewitt's Scrollability:
 *   https://github.com/joehewitt/scrollability/
 */
public class PanZoomController
    extends GestureDetector.SimpleOnGestureListener
    implements SimpleScaleGestureDetector.SimpleScaleGestureListener, GeckoEventListener
{
    private static final String LOGTAG = "GeckoPanZoomController";

    private static String MESSAGE_ZOOM_RECT = "Browser:ZoomToRect";
    private static String MESSAGE_ZOOM_PAGE = "Browser:ZoomToPageWidth";

    // Animation stops if the velocity is below this value when overscrolled or panning.
    private static final float STOPPED_THRESHOLD = 4.0f;

    // Animation stops is the velocity is below this threshold when flinging.
    private static final float FLING_STOPPED_THRESHOLD = 0.1f;

    // The distance the user has to pan before we recognize it as such (e.g. to avoid 1-pixel pans
    // between the touch-down and touch-up of a click). In units of density-independent pixels.
    public static final float PAN_THRESHOLD = 1/16f * GeckoAppShell.getDpi();

    // Angle from axis within which we stay axis-locked
    private static final double AXIS_LOCK_ANGLE = Math.PI / 6.0; // 30 degrees

    // The maximum amount we allow you to zoom into a page
    private static final float MAX_ZOOM = 4.0f;

    private enum PanZoomState {
        NOTHING,        /* no touch-start events received */
        FLING,          /* all touches removed, but we're still scrolling page */
        TOUCHING,       /* one touch-start event received */
        PANNING_LOCKED, /* touch-start followed by move (i.e. panning with axis lock) */
        PANNING,        /* panning without axis lock */
        PANNING_HOLD,   /* in panning, but not moving.
                         * similar to TOUCHING but after starting a pan */
        PANNING_HOLD_LOCKED, /* like PANNING_HOLD, but axis lock still in effect */
        PINCHING,       /* nth touch-start, where n > 1. this mode allows pan and zoom */
        ANIMATED_ZOOM,  /* animated zoom to a new rect */
        BOUNCE,         /* in a bounce animation */

        WAITING_LISTENERS, /* a state halfway between NOTHING and TOUCHING - the user has
                        put a finger down, but we don't yet know if a touch listener has
                        prevented the default actions yet. we still need to abort animations. */
    }

    private final PanZoomTarget mTarget;
    private final SubdocumentScrollHelper mSubscroller;
    private final Axis mX;
    private final Axis mY;
    private final EventDispatcher mEventDispatcher;
    private Thread mMainThread;

    /* The timer that handles flings or bounces. */
    private Timer mAnimationTimer;
    /* The runnable being scheduled by the animation timer. */
    private AnimationRunnable mAnimationRunnable;
    /* The zoom focus at the first zoom event (in page coordinates). */
    private PointF mLastZoomFocus;
    /* The time the last motion event took place. */
    private long mLastEventTime;
    /* Current state the pan/zoom UI is in. */
    private PanZoomState mState;

    public PanZoomController(PanZoomTarget target, EventDispatcher eventDispatcher) {
        mTarget = target;
        mSubscroller = new SubdocumentScrollHelper(this, eventDispatcher);
        mX = new AxisX(mSubscroller);
        mY = new AxisY(mSubscroller);

        mMainThread = GeckoApp.mAppContext.getMainLooper().getThread();
        checkMainThread();

        setState(PanZoomState.NOTHING);

        mEventDispatcher = eventDispatcher;
        registerEventListener(MESSAGE_ZOOM_RECT);
        registerEventListener(MESSAGE_ZOOM_PAGE);

        Axis.initPrefs();
    }

    public void destroy() {
        unregisterEventListener(MESSAGE_ZOOM_RECT);
        unregisterEventListener(MESSAGE_ZOOM_PAGE);
        mSubscroller.destroy();
    }

    private final static float easeOut(float t) {
        // ease-out approx.
        // -(t-1)^2+1
        t = t-1;
        return -t*t+1;
    }

    private void registerEventListener(String event) {
        mEventDispatcher.registerEventListener(event, this);
    }

    private void unregisterEventListener(String event) {
        mEventDispatcher.unregisterEventListener(event, this);
    }

    private void setState(PanZoomState state) {
        mState = state;
    }

    private ImmutableViewportMetrics getMetrics() {
        return mTarget.getViewportMetrics();
    }

    private ViewportMetrics getMutableMetrics() {
        return new ViewportMetrics(getMetrics());
    }

    // for debugging bug 713011; it can be taken out once that is resolved.
    private void checkMainThread() {
        if (mMainThread != Thread.currentThread()) {
            // log with full stack trace
            Log.e(LOGTAG, "Uh-oh, we're running on the wrong thread!", new Exception());
        }
    }

    public void handleMessage(String event, JSONObject message) {
        try {
            if (MESSAGE_ZOOM_RECT.equals(event)) {
                float x = (float)message.getDouble("x");
                float y = (float)message.getDouble("y");
                final RectF zoomRect = new RectF(x, y,
                                     x + (float)message.getDouble("w"),
                                     y + (float)message.getDouble("h"));
                mTarget.post(new Runnable() {
                    public void run() {
                        animatedZoomTo(zoomRect);
                    }
                });
            } else if (MESSAGE_ZOOM_PAGE.equals(event)) {
                ImmutableViewportMetrics metrics = getMetrics();
                RectF cssPageRect = metrics.getCssPageRect();

                RectF viewableRect = metrics.getCssViewport();
                float y = viewableRect.top;
                // attempt to keep zoom keep focused on the center of the viewport
                float newHeight = viewableRect.height() * cssPageRect.width() / viewableRect.width();
                float dh = viewableRect.height() - newHeight; // increase in the height
                final RectF r = new RectF(0.0f,
                                    y + dh/2,
                                    cssPageRect.width(),
                                    y + dh/2 + newHeight);
                mTarget.post(new Runnable() {
                    public void run() {
                        animatedZoomTo(r);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(LOGTAG, "Exception handling message \"" + event + "\":", e);
        }
    }

    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction() & MotionEvent.ACTION_MASK) {
        case MotionEvent.ACTION_DOWN:   return onTouchStart(event);
        case MotionEvent.ACTION_MOVE:   return onTouchMove(event);
        case MotionEvent.ACTION_UP:     return onTouchEnd(event);
        case MotionEvent.ACTION_CANCEL: return onTouchCancel(event);
        default:                        return false;
        }
    }

    /** This function must be called from the UI thread. */
    public void abortAnimation() {
        checkMainThread();
        // this happens when gecko changes the viewport on us or if the device is rotated.
        // if that's the case, abort any animation in progress and re-zoom so that the page
        // snaps to edges. for other cases (where the user's finger(s) are down) don't do
        // anything special.
        switch (mState) {
        case FLING:
            mX.stopFling();
            mY.stopFling();
            // fall through
        case BOUNCE:
        case ANIMATED_ZOOM:
            // the zoom that's in progress likely makes no sense any more (such as if
            // the screen orientation changed) so abort it
            setState(PanZoomState.NOTHING);
            // fall through
        case NOTHING:
            // Don't do animations here; they're distracting and can cause flashes on page
            // transitions.
            synchronized (mTarget.getLock()) {
                mTarget.setViewportMetrics(getValidViewportMetrics());
            }
            break;
        }
    }

    /** This function must be called on the UI thread. */
    public void startingNewEventBlock(MotionEvent event, boolean waitingForTouchListeners) {
        checkMainThread();
        mSubscroller.cancel();
        if (waitingForTouchListeners && (event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
            // this is the first touch point going down, so we enter the pending state
            // seting the state will kill any animations in progress, possibly leaving
            // the page in overscroll
            setState(PanZoomState.WAITING_LISTENERS);
        }
    }

    /** This function must be called on the UI thread. */
    public void preventedTouchFinished() {
        checkMainThread();
        if (mState == PanZoomState.WAITING_LISTENERS) {
            // if we enter here, we just finished a block of events whose default actions
            // were prevented by touch listeners. Now there are no touch points left, so
            // we need to reset our state and re-bounce because we might be in overscroll
            bounce();
        }
    }

    /** This must be called on the UI thread. */
    public void pageRectUpdated() {
        if (mState == PanZoomState.NOTHING) {
            synchronized (mTarget.getLock()) {
                ViewportMetrics validated = getValidViewportMetrics();
                if (! getMutableMetrics().fuzzyEquals(validated)) {
                    // page size changed such that we are now in overscroll. snap to the
                    // the nearest valid viewport
                    mTarget.setViewportMetrics(validated);
                }
            }
        }
    }

    /*
     * Panning/scrolling
     */

    private boolean onTouchStart(MotionEvent event) {
        // user is taking control of movement, so stop
        // any auto-movement we have going
        stopAnimationTimer();

        switch (mState) {
        case ANIMATED_ZOOM:
            // We just interrupted a double-tap animation, so force a redraw in
            // case this touchstart is just a tap that doesn't end up triggering
            // a redraw
            mTarget.setForceRedraw();
            // fall through
        case FLING:
        case BOUNCE:
        case NOTHING:
        case WAITING_LISTENERS:
            startTouch(event.getX(0), event.getY(0), event.getEventTime());
            return false;
        case TOUCHING:
        case PANNING:
        case PANNING_LOCKED:
        case PANNING_HOLD:
        case PANNING_HOLD_LOCKED:
        case PINCHING:
            Log.e(LOGTAG, "Received impossible touch down while in " + mState);
            return false;
        }
        Log.e(LOGTAG, "Unhandled case " + mState + " in onTouchStart");
        return false;
    }

    private boolean onTouchMove(MotionEvent event) {

        switch (mState) {
        case FLING:
        case BOUNCE:
        case WAITING_LISTENERS:
            // should never happen
            Log.e(LOGTAG, "Received impossible touch move while in " + mState);
            // fall through
        case ANIMATED_ZOOM:
        case NOTHING:
            // may happen if user double-taps and drags without lifting after the
            // second tap. ignore the move if this happens.
            return false;

        case TOUCHING:
            if (panDistance(event) < PAN_THRESHOLD) {
                return false;
            }
            cancelTouch();
            startPanning(event.getX(0), event.getY(0), event.getEventTime());
            track(event);
            return true;

        case PANNING_HOLD_LOCKED:
            setState(PanZoomState.PANNING_LOCKED);
            // fall through
        case PANNING_LOCKED:
            track(event);
            return true;

        case PANNING_HOLD:
            setState(PanZoomState.PANNING);
            // fall through
        case PANNING:
            track(event);
            return true;

        case PINCHING:
            // scale gesture listener will handle this
            return false;
        }
        Log.e(LOGTAG, "Unhandled case " + mState + " in onTouchMove");
        return false;
    }

    private boolean onTouchEnd(MotionEvent event) {

        switch (mState) {
        case FLING:
        case BOUNCE:
        case WAITING_LISTENERS:
            // should never happen
            Log.e(LOGTAG, "Received impossible touch end while in " + mState);
            // fall through
        case ANIMATED_ZOOM:
        case NOTHING:
            // may happen if user double-taps and drags without lifting after the
            // second tap. ignore if this happens.
            return false;

        case TOUCHING:
            // the switch into TOUCHING might have happened while the page was
            // snapping back after overscroll. we need to finish the snap if that
            // was the case
            bounce();
            return false;

        case PANNING:
        case PANNING_LOCKED:
        case PANNING_HOLD:
        case PANNING_HOLD_LOCKED:
            setState(PanZoomState.FLING);
            fling();
            return true;

        case PINCHING:
            setState(PanZoomState.NOTHING);
            return true;
        }
        Log.e(LOGTAG, "Unhandled case " + mState + " in onTouchEnd");
        return false;
    }

    private boolean onTouchCancel(MotionEvent event) {
        cancelTouch();

        if (mState == PanZoomState.WAITING_LISTENERS) {
            // we might get a cancel event from the TouchEventHandler while in the
            // WAITING_LISTENERS state if the touch listeners prevent-default the
            // block of events. at this point being in WAITING_LISTENERS is equivalent
            // to being in NOTHING with the exception of possibly being in overscroll.
            // so here we don't want to do anything right now; the overscroll will be
            // corrected in preventedTouchFinished().
            return false;
        }

        // ensure we snap back if we're overscrolled
        bounce();
        return false;
    }

    private void startTouch(float x, float y, long time) {
        mX.startTouch(x);
        mY.startTouch(y);
        setState(PanZoomState.TOUCHING);
        mLastEventTime = time;
    }

    private void startPanning(float x, float y, long time) {
        float dx = mX.panDistance(x);
        float dy = mY.panDistance(y);
        double angle = Math.atan2(dy, dx); // range [-pi, pi]
        angle = Math.abs(angle); // range [0, pi]

        // When the touch move breaks through the pan threshold, reposition the touch down origin
        // so the page won't jump when we start panning.
        mX.startTouch(x);
        mY.startTouch(y);
        mLastEventTime = time;

        if (!mX.scrollable() || !mY.scrollable()) {
            setState(PanZoomState.PANNING);
        } else if (angle < AXIS_LOCK_ANGLE || angle > (Math.PI - AXIS_LOCK_ANGLE)) {
            mY.setScrollingDisabled(true);
            setState(PanZoomState.PANNING_LOCKED);
        } else if (Math.abs(angle - (Math.PI / 2)) < AXIS_LOCK_ANGLE) {
            mX.setScrollingDisabled(true);
            setState(PanZoomState.PANNING_LOCKED);
        } else {
            setState(PanZoomState.PANNING);
        }
    }

    private float panDistance(MotionEvent move) {
        float dx = mX.panDistance(move.getX(0));
        float dy = mY.panDistance(move.getY(0));
        return FloatMath.sqrt(dx * dx + dy * dy);
    }

    private void track(float x, float y, long time) {
        float timeDelta = (float)(time - mLastEventTime);
        if (FloatUtils.fuzzyEquals(timeDelta, 0)) {
            // probably a duplicate event, ignore it. using a zero timeDelta will mess
            // up our velocity
            return;
        }
        mLastEventTime = time;

        mX.updateWithTouchAt(x, timeDelta);
        mY.updateWithTouchAt(y, timeDelta);
    }

    private void track(MotionEvent event) {
        mX.saveTouchPos();
        mY.saveTouchPos();

        for (int i = 0; i < event.getHistorySize(); i++) {
            track(event.getHistoricalX(0, i),
                  event.getHistoricalY(0, i),
                  event.getHistoricalEventTime(i));
        }
        track(event.getX(0), event.getY(0), event.getEventTime());

        if (stopped()) {
            if (mState == PanZoomState.PANNING) {
                setState(PanZoomState.PANNING_HOLD);
            } else if (mState == PanZoomState.PANNING_LOCKED) {
                setState(PanZoomState.PANNING_HOLD_LOCKED);
            } else {
                // should never happen, but handle anyway for robustness
                Log.e(LOGTAG, "Impossible case " + mState + " when stopped in track");
                setState(PanZoomState.PANNING_HOLD_LOCKED);
            }
        }

        mX.startPan();
        mY.startPan();
        updatePosition();
    }

    private void scrollBy(PointF point) {
        ViewportMetrics viewportMetrics = getMutableMetrics();
        PointF origin = viewportMetrics.getOrigin();
        origin.offset(point.x, point.y);
        viewportMetrics.setOrigin(origin);

        mTarget.setViewportMetrics(viewportMetrics);
    }

    private void fling() {
        updatePosition();

        stopAnimationTimer();

        boolean stopped = stopped();
        mX.startFling(stopped);
        mY.startFling(stopped);

        startAnimationTimer(new FlingRunnable());
    }

    /* Performs a bounce-back animation to the given viewport metrics. */
    private void bounce(ViewportMetrics metrics) {
        stopAnimationTimer();

        ViewportMetrics bounceStartMetrics = getMutableMetrics();
        if (bounceStartMetrics.fuzzyEquals(metrics)) {
            setState(PanZoomState.NOTHING);
            return;
        }

        // At this point we have already set mState to BOUNCE or ANIMATED_ZOOM, so
        // getRedrawHint() is returning false. This means we can safely call
        // setAnimationTarget to set the new final display port and not have it get
        // clobbered by display ports from intermediate animation frames.
        mTarget.setAnimationTarget(metrics);
        startAnimationTimer(new BounceRunnable(bounceStartMetrics, metrics));
    }

    /* Performs a bounce-back animation to the nearest valid viewport metrics. */
    private void bounce() {
        setState(PanZoomState.BOUNCE);
        bounce(getValidViewportMetrics());
    }

    /* Starts the fling or bounce animation. */
    private void startAnimationTimer(final AnimationRunnable runnable) {
        if (mAnimationTimer != null) {
            Log.e(LOGTAG, "Attempted to start a new fling without canceling the old one!");
            stopAnimationTimer();
        }

        mAnimationTimer = new Timer("Animation Timer");
        mAnimationRunnable = runnable;
        mAnimationTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() { mTarget.post(runnable); }
        }, 0, (int)Axis.MS_PER_FRAME);
    }

    /* Stops the fling or bounce animation. */
    private void stopAnimationTimer() {
        if (mAnimationTimer != null) {
            mAnimationTimer.cancel();
            mAnimationTimer = null;
        }
        if (mAnimationRunnable != null) {
            mAnimationRunnable.terminate();
            mAnimationRunnable = null;
        }
    }

    private float getVelocity() {
        float xvel = mX.getRealVelocity();
        float yvel = mY.getRealVelocity();
        return FloatMath.sqrt(xvel * xvel + yvel * yvel);
    }

    public PointF getVelocityVector() {
        return new PointF(mX.getRealVelocity(), mY.getRealVelocity());
    }

    private boolean stopped() {
        return getVelocity() < STOPPED_THRESHOLD;
    }

    PointF resetDisplacement() {
        return new PointF(mX.resetDisplacement(), mY.resetDisplacement());
    }

    private void updatePosition() {
        mX.displace();
        mY.displace();
        PointF displacement = resetDisplacement();
        if (FloatUtils.fuzzyEquals(displacement.x, 0.0f) && FloatUtils.fuzzyEquals(displacement.y, 0.0f)) {
            return;
        }
        if (! mSubscroller.scrollBy(displacement)) {
            synchronized (mTarget.getLock()) {
                scrollBy(displacement);
            }
        }
    }

    private abstract class AnimationRunnable implements Runnable {
        private boolean mAnimationTerminated;

        /* This should always run on the UI thread */
        public final void run() {
            /*
             * Since the animation timer queues this runnable on the UI thread, it
             * is possible that even when the animation timer is cancelled, there
             * are multiple instances of this queued, so we need to have another
             * mechanism to abort. This is done by using the mAnimationTerminated flag.
             */
            if (mAnimationTerminated) {
                return;
            }
            animateFrame();
        }

        protected abstract void animateFrame();

        /* This should always run on the UI thread */
        protected final void terminate() {
            mAnimationTerminated = true;
        }
    }

    /* The callback that performs the bounce animation. */
    private class BounceRunnable extends AnimationRunnable {
        /* The current frame of the bounce-back animation */
        private int mBounceFrame;
        /*
         * The viewport metrics that represent the start and end of the bounce-back animation,
         * respectively.
         */
        private ViewportMetrics mBounceStartMetrics;
        private ViewportMetrics mBounceEndMetrics;

        BounceRunnable(ViewportMetrics startMetrics, ViewportMetrics endMetrics) {
            mBounceStartMetrics = startMetrics;
            mBounceEndMetrics = endMetrics;
        }

        protected void animateFrame() {
            /*
             * The pan/zoom controller might have signaled to us that it wants to abort the
             * animation by setting the state to PanZoomState.NOTHING. Handle this case and bail
             * out.
             */
            if (!(mState == PanZoomState.BOUNCE || mState == PanZoomState.ANIMATED_ZOOM)) {
                finishAnimation();
                return;
            }

            /* Perform the next frame of the bounce-back animation. */
            if (mBounceFrame < (int)(256f/Axis.MS_PER_FRAME)) {
                advanceBounce();
                return;
            }

            /* Finally, if there's nothing else to do, complete the animation and go to sleep. */
            finishBounce();
            finishAnimation();
            setState(PanZoomState.NOTHING);
        }

        /* Performs one frame of a bounce animation. */
        private void advanceBounce() {
            synchronized (mTarget.getLock()) {
                float t = easeOut(mBounceFrame * Axis.MS_PER_FRAME / 256f);
                ViewportMetrics newMetrics = mBounceStartMetrics.interpolate(mBounceEndMetrics, t);
                mTarget.setViewportMetrics(newMetrics);
                mBounceFrame++;
            }
        }

        /* Concludes a bounce animation and snaps the viewport into place. */
        private void finishBounce() {
            synchronized (mTarget.getLock()) {
                mTarget.setViewportMetrics(mBounceEndMetrics);
                mBounceFrame = -1;
            }
        }
    }

    // The callback that performs the fling animation.
    private class FlingRunnable extends AnimationRunnable {
        protected void animateFrame() {
            /*
             * The pan/zoom controller might have signaled to us that it wants to abort the
             * animation by setting the state to PanZoomState.NOTHING. Handle this case and bail
             * out.
             */
            if (mState != PanZoomState.FLING) {
                finishAnimation();
                return;
            }

            /* Advance flings, if necessary. */
            boolean flingingX = mX.advanceFling();
            boolean flingingY = mY.advanceFling();

            boolean overscrolled = (mX.overscrolled() || mY.overscrolled());

            /* If we're still flinging in any direction, update the origin. */
            if (flingingX || flingingY) {
                updatePosition();

                /*
                 * Check to see if we're still flinging with an appreciable velocity. The threshold is
                 * higher in the case of overscroll, so we bounce back eagerly when overscrolling but
                 * coast smoothly to a stop when not. In other words, require a greater velocity to
                 * maintain the fling once we enter overscroll.
                 */
                float threshold = (overscrolled && !mSubscroller.scrolling() ? STOPPED_THRESHOLD : FLING_STOPPED_THRESHOLD);
                if (getVelocity() >= threshold) {
                    // we're still flinging
                    return;
                }

                mX.stopFling();
                mY.stopFling();
            }

            /* Perform a bounce-back animation if overscrolled. */
            if (overscrolled) {
                bounce();
            } else {
                finishAnimation();
                setState(PanZoomState.NOTHING);
            }
        }
    }

    private void finishAnimation() {
        checkMainThread();

        stopAnimationTimer();

        // Force a viewport synchronisation
        mTarget.setForceRedraw();
    }

    /* Returns the nearest viewport metrics with no overscroll visible. */
    private ViewportMetrics getValidViewportMetrics() {
        return getValidViewportMetrics(getMutableMetrics());
    }

    private ViewportMetrics getValidViewportMetrics(ViewportMetrics viewportMetrics) {
        /* First, we adjust the zoom factor so that we can make no overscrolled area visible. */
        float zoomFactor = viewportMetrics.getZoomFactor();
        RectF pageRect = viewportMetrics.getPageRect();
        RectF viewport = viewportMetrics.getViewport();

        float focusX = viewport.width() / 2.0f;
        float focusY = viewport.height() / 2.0f;

        float minZoomFactor = 0.0f;
        float maxZoomFactor = MAX_ZOOM;

        ZoomConstraints constraints = mTarget.getZoomConstraints();

        if (constraints.getMinZoom() > 0)
            minZoomFactor = constraints.getMinZoom();
        if (constraints.getMaxZoom() > 0)
            maxZoomFactor = constraints.getMaxZoom();

        if (!constraints.getAllowZoom()) {
            // If allowZoom is false, clamp to the default zoom level.
            maxZoomFactor = minZoomFactor = constraints.getDefaultZoom();
        }

        // Ensure minZoomFactor keeps the page at least as big as the viewport.
        if (pageRect.width() > 0) {
            float scaleFactor = viewport.width() / pageRect.width();
            minZoomFactor = Math.max(minZoomFactor, zoomFactor * scaleFactor);
            if (viewport.width() > pageRect.width())
                focusX = 0.0f;
        }
        if (pageRect.height() > 0) {
            float scaleFactor = viewport.height() / pageRect.height();
            minZoomFactor = Math.max(minZoomFactor, zoomFactor * scaleFactor);
            if (viewport.height() > pageRect.height())
                focusY = 0.0f;
        }

        maxZoomFactor = Math.max(maxZoomFactor, minZoomFactor);

        if (zoomFactor < minZoomFactor) {
            // if one (or both) of the page dimensions is smaller than the viewport,
            // zoom using the top/left as the focus on that axis. this prevents the
            // scenario where, if both dimensions are smaller than the viewport, but
            // by different scale factors, we end up scrolled to the end on one axis
            // after applying the scale
            PointF center = new PointF(focusX, focusY);
            viewportMetrics.scaleTo(minZoomFactor, center);
        } else if (zoomFactor > maxZoomFactor) {
            PointF center = new PointF(viewport.width() / 2.0f, viewport.height() / 2.0f);
            viewportMetrics.scaleTo(maxZoomFactor, center);
        }

        /* Now we pan to the right origin. */
        viewportMetrics.setViewport(viewportMetrics.getClampedViewport());

        return viewportMetrics;
    }

    private class AxisX extends Axis {
        AxisX(SubdocumentScrollHelper subscroller) { super(subscroller); }
        @Override
        public float getOrigin() { return getMetrics().viewportRectLeft; }
        @Override
        protected float getViewportLength() { return getMetrics().getWidth(); }
        @Override
        protected float getPageStart() { return getMetrics().pageRectLeft; }
        @Override
        protected float getPageLength() { return getMetrics().getPageWidth(); }
    }

    private class AxisY extends Axis {
        AxisY(SubdocumentScrollHelper subscroller) { super(subscroller); }
        @Override
        public float getOrigin() { return getMetrics().viewportRectTop; }
        @Override
        protected float getViewportLength() { return getMetrics().getHeight(); }
        @Override
        protected float getPageStart() { return getMetrics().pageRectTop; }
        @Override
        protected float getPageLength() { return getMetrics().getPageHeight(); }
    }

    /*
     * Zooming
     */
    @Override
    public boolean onScaleBegin(SimpleScaleGestureDetector detector) {
        if (mState == PanZoomState.ANIMATED_ZOOM)
            return false;

        if (!mTarget.getZoomConstraints().getAllowZoom())
            return false;

        setState(PanZoomState.PINCHING);
        mLastZoomFocus = new PointF(detector.getFocusX(), detector.getFocusY());
        cancelTouch();

        GeckoAppShell.sendEventToGecko(GeckoEvent.createNativeGestureEvent(GeckoEvent.ACTION_MAGNIFY_START, mLastZoomFocus, getMetrics().zoomFactor));

        return true;
    }

    @Override
    public boolean onScale(SimpleScaleGestureDetector detector) {
        if (GeckoApp.mAppContext == null || GeckoApp.mAppContext.mDOMFullScreen)
            return false;

        if (mState != PanZoomState.PINCHING)
            return false;

        float prevSpan = detector.getPreviousSpan();
        if (FloatUtils.fuzzyEquals(prevSpan, 0.0f)) {
            // let's eat this one to avoid setting the new zoom to infinity (bug 711453)
            return true;
        }

        float spanRatio = detector.getCurrentSpan() / prevSpan;

        /*
         * Apply edge resistance if we're zoomed out smaller than the page size by scaling the zoom
         * factor toward 1.0.
         */
        float resistance = Math.min(mX.getEdgeResistance(true), mY.getEdgeResistance(true));
        if (spanRatio > 1.0f)
            spanRatio = 1.0f + (spanRatio - 1.0f) * resistance;
        else
            spanRatio = 1.0f - (1.0f - spanRatio) * resistance;

        synchronized (mTarget.getLock()) {
            float newZoomFactor = getMetrics().zoomFactor * spanRatio;
            float minZoomFactor = 0.0f;
            float maxZoomFactor = MAX_ZOOM;

            ZoomConstraints constraints = mTarget.getZoomConstraints();

            if (constraints.getMinZoom() > 0)
                minZoomFactor = constraints.getMinZoom();
            if (constraints.getMaxZoom() > 0)
                maxZoomFactor = constraints.getMaxZoom();

            if (newZoomFactor < minZoomFactor) {
                // apply resistance when zooming past minZoomFactor,
                // such that it asymptotically reaches minZoomFactor / 2.0
                // but never exceeds that
                final float rate = 0.5f; // controls how quickly we approach the limit
                float excessZoom = minZoomFactor - newZoomFactor;
                excessZoom = 1.0f - (float)Math.exp(-excessZoom * rate);
                newZoomFactor = minZoomFactor * (1.0f - excessZoom / 2.0f);
            }

            if (newZoomFactor > maxZoomFactor) {
                // apply resistance when zooming past maxZoomFactor,
                // such that it asymptotically reaches maxZoomFactor + 1.0
                // but never exceeds that
                float excessZoom = newZoomFactor - maxZoomFactor;
                excessZoom = 1.0f - (float)Math.exp(-excessZoom);
                newZoomFactor = maxZoomFactor + excessZoom;
            }

            scrollBy(new PointF(mLastZoomFocus.x - detector.getFocusX(),
                    mLastZoomFocus.y - detector.getFocusY()));
            PointF focus = new PointF(detector.getFocusX(), detector.getFocusY());
            scaleWithFocus(newZoomFactor, focus);
        }

        mLastZoomFocus.set(detector.getFocusX(), detector.getFocusY());

        GeckoEvent event = GeckoEvent.createNativeGestureEvent(GeckoEvent.ACTION_MAGNIFY, mLastZoomFocus, getMetrics().zoomFactor);
        GeckoAppShell.sendEventToGecko(event);

        return true;
    }

    @Override
    public void onScaleEnd(SimpleScaleGestureDetector detector) {
        if (mState == PanZoomState.ANIMATED_ZOOM)
            return;

        // switch back to the touching state
        startTouch(detector.getFocusX(), detector.getFocusY(), detector.getEventTime());

        // Force a viewport synchronisation
        mTarget.setForceRedraw();

        PointF point = new PointF(detector.getFocusX(), detector.getFocusY());
        GeckoEvent event = GeckoEvent.createNativeGestureEvent(GeckoEvent.ACTION_MAGNIFY_END, point, getMetrics().zoomFactor);

        if (event == null) {
            return;
        }

        GeckoAppShell.sendEventToGecko(event);
    }

    /**
     * Scales the viewport, keeping the given focus point in the same place before and after the
     * scale operation. You must hold the monitor while calling this.
     */
    private void scaleWithFocus(float zoomFactor, PointF focus) {
        ViewportMetrics viewportMetrics = getMutableMetrics();
        viewportMetrics.scaleTo(zoomFactor, focus);
        mTarget.setViewportMetrics(viewportMetrics);
    }

    public boolean getRedrawHint() {
        switch (mState) {
            case PINCHING:
            case ANIMATED_ZOOM:
            case BOUNCE:
                // don't redraw during these because the zoom is (or might be, in the case
                // of BOUNCE) be changing rapidly and gecko will have to redraw the entire
                // display port area. we trigger a force-redraw upon exiting these states.
                return false;
            default:
                // allow redrawing in other states
                return true;
        }
    }

    private void sendPointToGecko(String event, MotionEvent motionEvent) {
        String json;
        try {
            PointF point = new PointF(motionEvent.getX(), motionEvent.getY());
            point = mTarget.convertViewPointToLayerPoint(point);
            if (point == null) {
                return;
            }
            json = PointUtils.toJSON(point).toString();
        } catch (Exception e) {
            Log.e(LOGTAG, "Unable to convert point to JSON for " + event, e);
            return;
        }

        GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent(event, json));
    }

    @Override
    public void onLongPress(MotionEvent motionEvent) {
        sendPointToGecko("Gesture:LongPress", motionEvent);
    }

    @Override
    public boolean onSingleTapUp(MotionEvent motionEvent) {
        // When zooming is enabled, wait to see if there's a double-tap.
        if (!mTarget.getZoomConstraints().getAllowZoom()) {
            sendPointToGecko("Gesture:SingleTap", motionEvent);
        }
        // return false because we still want to get the ACTION_UP event that triggers this
        return false;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent motionEvent) {
        // When zooming is disabled, we handle this in onSingleTapUp.
        if (mTarget.getZoomConstraints().getAllowZoom()) {
            sendPointToGecko("Gesture:SingleTap", motionEvent);
        }
        return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent motionEvent) {
        if (mTarget.getZoomConstraints().getAllowZoom()) {
            sendPointToGecko("Gesture:DoubleTap", motionEvent);
        }
        return true;
    }

    private void cancelTouch() {
        GeckoEvent e = GeckoEvent.createBroadcastEvent("Gesture:CancelTouch", "");
        GeckoAppShell.sendEventToGecko(e);
    }

    /**
     * Zoom to a specified rect IN CSS PIXELS.
     *
     * While we usually use device pixels, @zoomToRect must be specified in CSS
     * pixels.
     */
    private boolean animatedZoomTo(RectF zoomToRect) {
        setState(PanZoomState.ANIMATED_ZOOM);
        final float startZoom = getMetrics().zoomFactor;

        RectF viewport = getMetrics().getViewport();
        // 1. adjust the aspect ratio of zoomToRect to match that of the current viewport,
        // enlarging as necessary (if it gets too big, it will get shrunk in the next step).
        // while enlarging make sure we enlarge equally on both sides to keep the target rect
        // centered.
        float targetRatio = viewport.width() / viewport.height();
        float rectRatio = zoomToRect.width() / zoomToRect.height();
        if (FloatUtils.fuzzyEquals(targetRatio, rectRatio)) {
            // all good, do nothing
        } else if (targetRatio < rectRatio) {
            // need to increase zoomToRect height
            float newHeight = zoomToRect.width() / targetRatio;
            zoomToRect.top -= (newHeight - zoomToRect.height()) / 2;
            zoomToRect.bottom = zoomToRect.top + newHeight;
        } else { // targetRatio > rectRatio) {
            // need to increase zoomToRect width
            float newWidth = targetRatio * zoomToRect.height();
            zoomToRect.left -= (newWidth - zoomToRect.width()) / 2;
            zoomToRect.right = zoomToRect.left + newWidth;
        }

        float finalZoom = viewport.width() / zoomToRect.width();

        ViewportMetrics finalMetrics = getMutableMetrics();
        finalMetrics.setOrigin(new PointF(zoomToRect.left * finalMetrics.getZoomFactor(),
                                          zoomToRect.top * finalMetrics.getZoomFactor()));
        finalMetrics.scaleTo(finalZoom, new PointF(0.0f, 0.0f));

        // 2. now run getValidViewportMetrics on it, so that the target viewport is
        // clamped down to prevent overscroll, over-zoom, and other bad conditions.
        finalMetrics = getValidViewportMetrics(finalMetrics);

        bounce(finalMetrics);
        return true;
    }

    /** This function must be called from the UI thread. */
    public void abortPanning() {
        checkMainThread();
        bounce();
    }

    public void setOverScrollMode(int overscrollMode) {
        mX.setOverScrollMode(overscrollMode);
        mY.setOverScrollMode(overscrollMode);
    }

    public int getOverScrollMode() {
        return mX.getOverScrollMode();
    }
}
