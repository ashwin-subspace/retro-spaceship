package com.ashwin.retrospaceship;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayDeque;

/**
 * The surface, the render thread and the input pump.
 *
 * <p>Touch events arrive on the UI thread while the world is being simulated on
 * the render thread. Rather than locking the world, events are queued and
 * drained at the top of each frame, so the entire game only ever runs on one
 * thread and no gameplay code has to think about concurrency at all.
 */
public final class GameView extends SurfaceView implements Runnable, SurfaceHolder.Callback {

    private static final int DOWN = 0, MOVE = 1, UP = 2;

    /** A queued pointer event, in screen pixels; converted to world units on use. */
    private static final class Event {
        int type;
        int pointerId;
        float x, y;
    }

    private Thread thread;
    private volatile boolean running = false;
    private volatile boolean surfaceReady = false;

    private final ArrayDeque<Event> pending = new ArrayDeque<>();
    private final ArrayDeque<Event> spare = new ArrayDeque<>();

    private Game game;
    private Draw draw;
    private Assets assets;
    private Sfx sfx;

    private volatile float scale = 1f;
    private volatile float offsetX = 0f;
    private volatile float offsetY = 0f;
    // Written by surfaceChanged on the UI thread, read by the render thread.
    private volatile int surfaceW = 0;
    private volatile int surfaceH = 0;

    private final Object startLock = new Object();

    public GameView(Context context) {
        super(context);
        getHolder().addCallback(this);
        setFocusable(true);
    }

    public Game game() {
        return game;
    }

    // ---------- surface lifecycle ----------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceW = width;
        surfaceH = height;
        recomputeLayout();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stop();
    }

    private void recomputeLayout() {
        if (game == null || surfaceW == 0) {
            return;
        }
        // The playfield is exactly the screen: width fills, and the world height
        // was derived from this surface, so there is nothing left to letterbox.
        // Sprites keep a uniform scale on every device -- only how much sky the
        // player gets changes.
        scale = surfaceW / Cfg.W;
        offsetX = 0f;
        offsetY = (surfaceH - game.worldH * scale) * 0.5f;
    }

    public void start() {
        synchronized (startLock) {
            if (running || !surfaceReady) {
                return;
            }
            running = true;
            thread = new Thread(this, "retro-spaceship-loop");
            thread.start();
        }
    }

    public void stop() {
        Thread current;
        synchronized (startLock) {
            running = false;
            current = thread;
            thread = null;
        }
        if (current == null) {
            return;
        }
        try {
            current.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- input ----------

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                queue(DOWN, event.getPointerId(index), event.getX(index), event.getY(index));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    queue(MOVE, event.getPointerId(i), event.getX(i), event.getY(i));
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int index = event.getActionIndex();
                queue(UP, event.getPointerId(index), event.getX(index), event.getY(index));
                break;
            }
            default:
                break;
        }
        return true;
    }

    private void queue(int type, int pointerId, float x, float y) {
        synchronized (pending) {
            // Cap the backlog. A finger dragged fast can outpace a slow frame, and
            // replaying a hundred stale moves would rubber-band the ship.
            if (pending.size() > 120) {
                pending.pollFirst();
            }
            Event event = spare.pollFirst();
            if (event == null) {
                event = new Event();
            }
            event.type = type;
            event.pointerId = pointerId;
            event.x = x;
            event.y = y;
            pending.addLast(event);
        }
    }

    private void drainInput() {
        while (true) {
            Event event;
            synchronized (pending) {
                event = pending.pollFirst();
            }
            if (event == null) {
                return;
            }
            float wx = (event.x - offsetX) / scale;
            float wy = (event.y - offsetY) / scale;
            switch (event.type) {
                case DOWN:
                    game.onDown(event.pointerId, wx, wy);
                    break;
                case MOVE:
                    game.onMove(event.pointerId, wx, wy);
                    break;
                default:
                    game.onUp(event.pointerId, wx, wy);
                    break;
            }
            synchronized (pending) {
                if (spare.size() < 160) {
                    spare.addLast(event);
                }
            }
        }
    }

    // ---------- loop ----------

    @Override
    public void run() {
        if (assets == null) {
            assets = new Assets(getContext().getAssets());
            sfx = new Sfx(getContext().getAssets());
            draw = new Draw(assets);
        }
        if (game == null) {
            // The gutter height, and therefore the whole world size, is derived
            // from the real surface. surfaceCreated can beat surfaceChanged, so
            // wait for a measured size rather than laying out against zero.
            long deadline = System.currentTimeMillis() + 2000L;
            while (running && surfaceW == 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(8L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (surfaceW == 0) {
                surfaceW = Math.max(1, getWidth());
                surfaceH = Math.max(1, getHeight());
            }
            // Fix the playfield to the real screen before anything reads Cfg.H:
            // enemy hold positions, the boss hover line and the player's own
            // start position are all derived from it at construction time.
            Cfg.setPlayfield(surfaceH / (surfaceW / Cfg.W));
            game = new Game(getContext(), assets, sfx);
            recomputeLayout();
        } else {
            recomputeLayout();
            game.onResumeApp();
        }

        long previous = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float realMs = (now - previous) / 1000000f;
            previous = now;

            drainInput();
            game.update(realMs);
            render();

            // Aim for 60fps. Sleeping the remainder keeps the device cool and the
            // battery alive; a busy-wait would gain nothing on a vsynced surface.
            long spent = (System.nanoTime() - now) / 1000000L;
            long sleep = 16L - spent;
            if (sleep > 1L) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (game != null) {
            game.onPauseApp();
        }
    }

    private void render() {
        SurfaceHolder holder = getHolder();
        if (!surfaceReady || !holder.getSurface().isValid()) {
            return;
        }
        Canvas canvas = null;
        boolean hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
        try {
            canvas = hardware ? holder.lockHardwareCanvas() : holder.lockCanvas();
            if (canvas == null) {
                return;
            }
            canvas.drawColor(Color.BLACK);
            canvas.save();
            canvas.translate(offsetX, offsetY);
            canvas.scale(scale, scale);
            // Clip to the world box so a shake can never smear content into the
            // letterbox bars.
            canvas.clipRect(0f, 0f, Cfg.W, game.worldH);
            draw.canvas = canvas;
            game.draw(draw);
            canvas.restore();
        } catch (IllegalArgumentException | IllegalStateException e) {
            // The surface can be torn out from under a frame during rotation or
            // backgrounding. Dropping the frame is the correct response.
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas);
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    // Same cause as above; nothing to recover.
                }
            }
        }
    }

    public void releaseAudio() {
        if (sfx != null) {
            sfx.release();
            sfx = null;
        }
    }
}
