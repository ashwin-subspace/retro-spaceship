package com.ashwin.retrospaceship;

/** Position, velocity and liveness. Subclasses add behaviour. */
public abstract class Entity {
    public float x, y, vx, vy;
    public float radius;
    public boolean alive = true;

    protected Entity(float x, float y, float vx, float vy, float radius) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.radius = radius;
    }

    public void integrate(float dts) {
        x += vx * dts;
        y += vy * dts;
    }

    public float left() {
        return x - radius;
    }

    public float top() {
        return y - radius;
    }

    public float width() {
        return radius * 2f;
    }

    public float height() {
        return radius * 2f;
    }

    public boolean offscreen(float margin) {
        return y > Cfg.H + margin || y < -margin * 4f
                || x < -margin * 2f || x > Cfg.W + margin * 2f;
    }

    public void kill() {
        alive = false;
    }

    public abstract void update(Ctx ctx);

    public abstract void draw(Draw d);

    // ---------- shared geometry ----------

    public static float dist(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(ax - bx, ay - by);
    }

    /** Axis-aligned overlap test on two centre/half-extent boxes. */
    public static boolean overlaps(float ax, float ay, float ahw, float ahh,
                                   float bx, float by, float bhw, float bhh) {
        return Math.abs(ax - bx) <= ahw + bhw && Math.abs(ay - by) <= ahh + bhh;
    }

    public static float clamp(float value, float lo, float hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
