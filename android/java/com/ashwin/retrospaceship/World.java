package com.ashwin.retrospaceship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The live game world: entity ownership, collisions and scoring.
 *
 * <p>Collision is deliberately asymmetric. Player shots against enemies use
 * inflated boxes, so a near miss counts. Enemy bullets against the player use
 * exact circle maths against a 3px core, so a dense screen is survivable by
 * skill rather than by luck. Getting that pair the wrong way round is the single
 * fastest way to make a bullet-hell game feel cheap.
 */
public final class World {

    /** Per-run statistics, used for the results screen and the difficulty read. */
    public static final class Stats {
        public int kills = 0;
        public final Map<String, Integer> killsByType = new HashMap<>();
        public int shotsFired = 0;
        public int shotsHit = 0;
        public int hitsTaken = 0;
        public int bombsUsed = 0;
        public int bestCombo = 0;
        public int grazeTotal = 0;
        public int maxGrazeStreak = 0;
        public int bossesKilled = 0;
        public int perfectWaves = 0;
        public double timeMs = 0.0;
        public int stage = 1;
        public int wave = 1;

        public float accuracy() {
            if (shotsFired <= 0) {
                return 0f;
            }
            return Math.min(1f, shotsHit / (float) shotsFired);
        }

        /** An arcade letter grade from a weighted composite of the whole run. */
        public String rank(int score) {
            float points = 0f;
            points += Math.min(1f, score / 30000f) * 42f;
            points += accuracy() * 20f;
            points += Math.min(1f, bestCombo / 40f) * 14f;
            points += Math.min(1f, maxGrazeStreak / 40f) * 10f;
            points += Math.min(1f, bossesKilled / 3f) * 8f;
            points += Math.max(0f, 1f - hitsTaken / 12f) * 6f;
            if (points >= 85f) return "S";
            if (points >= 70f) return "A";
            if (points >= 55f) return "B";
            if (points >= 38f) return "C";
            if (points >= 20f) return "D";
            return "E";
        }
    }

    public final Player player = new Player();
    public final List<Enemy> enemies = new ArrayList<>();
    public final List<Bullets.PlayerBullet> playerBullets = new ArrayList<>();
    public final List<Bullets.EnemyBullet> enemyBullets = new ArrayList<>();
    public final List<Pickup> pickups = new ArrayList<>();
    public final List<Bullets.Laser> lasers = new ArrayList<>();
    public Boss boss = null;

    public int score = 0;
    public int combo = 0;
    private double comboUntil = 0.0;
    public final Stats stats = new Stats();
    public boolean waveDamageTaken = false;
    public double lastHitT = -99999.0;

    private Ctx ctx;

    public void bind(Ctx context) {
        ctx = context;
        context.world = this;
        context.player = player;
    }

    // ---------- spawning ----------

    public void addEnemy(Enemy e) {
        enemies.add(e);
    }

    public void addPlayerBullet(Bullets.PlayerBullet b) {
        // Counted per bullet, not per volley, so accuracy means what it says.
        stats.shotsFired++;
        playerBullets.add(b);
    }

    public void addEnemyBullet(Bullets.EnemyBullet b) {
        // Hard cap: past this the screen stops being readable, and an unreadable
        // screen is not difficulty, it is noise.
        if (enemyBullets.size() >= Cfg.MAX_ENEMY_BULLETS) {
            return;
        }
        enemyBullets.add(b);
    }

    public void addPickup(Pickup p) {
        pickups.add(p);
    }

    public void addLaser(Bullets.Laser l) {
        lasers.add(l);
    }

    public void setBoss(Boss b) {
        boss = b;
    }

    // ---------- queries ----------

    public Object nearestTarget(float x, float y) {
        Enemy best = null;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < enemies.size(); i++) {
            Enemy e = enemies.get(i);
            float dx = e.x - x;
            float dy = e.y - y;
            float d = dx * dx + dy * dy;
            if (d < bestDistance) {
                best = e;
                bestDistance = d;
            }
        }
        if (best == null && boss != null && boss.active()) {
            return boss;
        }
        return best;
    }

    public int comboMultiplier() {
        return 1 + combo / 5;
    }

    // ---------- scoring ----------

    public int award(int amount, float x, float y, boolean popup) {
        int multiplier = comboMultiplier();
        float bonus = 1f + player.scoreBonus
                + (player.overdriveActive() ? Cfg.OVERDRIVE_SCORE_BONUS : 0f);
        int total = Math.round(amount * multiplier * bonus);
        score += total;
        if (popup) {
            ctx.fx.popup(x, y, "+" + total, Theme.color(Theme.YELLOW), 14f);
        }
        return total;
    }

    public void registerKill(Enemy e) {
        combo++;
        comboUntil = ctx.clock.t + Cfg.COMBO_TIMEOUT_MS;
        stats.kills++;
        stats.bestCombo = Math.max(stats.bestCombo, combo);
        Integer previous = stats.killsByType.get(e.key);
        stats.killsByType.put(e.key, previous == null ? 1 : previous + 1);
        player.addKillCharge();
        award(e.scoreValue(), e.x, e.y, true);
        Pickup drop = Pickup.rollDrop(e.x, e.y, player.health <= 1);
        if (drop != null) {
            addPickup(drop);
        }
    }

    // ---------- bulk effects ----------

    /** Wipe the screen. Bullets become score motes, which is the reward. */
    public int clearEnemyBullets(boolean toScore) {
        int count = enemyBullets.size();
        if (toScore) {
            int converted = Math.min(60, count);
            for (int i = 0; i < converted; i++) {
                Bullets.EnemyBullet b = enemyBullets.get(i);
                addPickup(new Pickup(b.x, b.y, Pickup.SCORE,
                        Rng.uniform(-40f, 40f), Rng.uniform(-60f, -10f), 5));
            }
        }
        enemyBullets.clear();
        lasers.clear();
        return count;
    }

    public boolean detonateBomb() {
        if (!player.useBomb(ctx)) {
            return false;
        }
        stats.bombsUsed++;
        clearEnemyBullets(true);
        ctx.fx.flash(Theme.color(Theme.WHITE), 260f, 190);
        ctx.fx.shockwave(player.x, player.y, 20f, 460f, 700f,
                Theme.color(Cfg.C_PICKUP_BOMB), 5f);
        ctx.fx.shake(Cfg.SHAKE_ON_BOMB);
        ctx.clock.slowmo(Cfg.SLOWMO_BOMB_SCALE, Cfg.SLOWMO_BOMB_MS);
        ctx.sfx.play("bomb");
        for (Enemy e : new ArrayList<>(enemies)) {
            if (e.takeDamage(3, ctx, Float.NaN, Float.NaN)) {
                registerKill(e);
            }
        }
        if (boss != null && boss.active()) {
            boss.damage(null, 18, ctx);
        }
        return true;
    }

    // ---------- frame ----------

    public void update() {
        Clock clock = ctx.clock;
        stats.timeMs += clock.dt;

        if (combo > 0 && clock.t > comboUntil) {
            combo = 0;
        }
        if (player.alive) {
            player.update(ctx);
        }
        for (int i = 0; i < enemies.size(); i++) {
            enemies.get(i).update(ctx);
        }
        for (int i = 0; i < playerBullets.size(); i++) {
            playerBullets.get(i).update(ctx);
        }
        for (int i = 0; i < enemyBullets.size(); i++) {
            enemyBullets.get(i).update(ctx);
        }
        for (int i = 0; i < pickups.size(); i++) {
            pickups.get(i).update(ctx);
        }
        for (int i = 0; i < lasers.size(); i++) {
            lasers.get(i).update(ctx);
        }

        if (boss != null) {
            boss.update(ctx);
            if (boss.gone()) {
                stats.bossesKilled++;
                award(boss.scoreValue, boss.x, boss.y, true);
                boss = null;
            }
        }

        collide();
        cull();
    }

    private void cull() {
        removeDead(enemies);
        removeDead(playerBullets);
        removeDead(enemyBullets);
        removeDead(pickups);
        removeDead(lasers);
    }

    private static <T extends Entity> void removeDead(List<T> list) {
        int write = 0;
        for (int i = 0; i < list.size(); i++) {
            T item = list.get(i);
            if (item.alive) {
                list.set(write++, item);
            }
        }
        while (list.size() > write) {
            list.remove(list.size() - 1);
        }
    }

    // ---------- collisions ----------

    private void collide() {
        playerShotsVsEnemies();
        playerShotsVsBoss();
        if (player.alive) {
            enemyBulletsVsPlayer();
            lasersVsPlayer();
            enemiesVsPlayer();
            pickupsVsPlayer();
        }
    }

    private void playerShotsVsEnemies() {
        if (enemies.isEmpty() || playerBullets.isEmpty()) {
            return;
        }
        for (int b = 0; b < playerBullets.size(); b++) {
            Bullets.PlayerBullet bullet = playerBullets.get(b);
            if (!bullet.alive) {
                continue;
            }
            for (int i = 0; i < enemies.size(); i++) {
                Enemy e = enemies.get(i);
                if (!e.alive) {
                    continue;
                }
                if (!Entity.overlaps(bullet.x, bullet.y, bullet.halfW(), bullet.halfH(),
                        e.x, e.y, e.halfW() + Cfg.ENEMY_HURTBOX_INFLATE,
                        e.halfH() + Cfg.ENEMY_HURTBOX_INFLATE)) {
                    continue;
                }
                stats.shotsHit++;
                if (e.takeDamage(bullet.damage, ctx, bullet.vx, bullet.vy)) {
                    registerKill(e);
                }
                if (bullet.onHit()) {
                    bullet.kill();
                    break;
                }
            }
        }
    }

    private void playerShotsVsBoss() {
        if (boss == null || !boss.active() || playerBullets.isEmpty()) {
            return;
        }
        for (int b = 0; b < playerBullets.size(); b++) {
            Bullets.PlayerBullet bullet = playerBullets.get(b);
            if (!bullet.alive) {
                continue;
            }
            // Arms are checked before the core, so shots always land on the
            // thing the player is being told to destroy.
            boolean hit = false;
            for (Boss.Arm arm : boss.arms) {
                if (!arm.alive()) {
                    continue;
                }
                if (Entity.overlaps(bullet.x, bullet.y, bullet.halfW(), bullet.halfH(),
                        arm.x, arm.y, arm.w * 0.5f, arm.h * 0.5f)) {
                    stats.shotsHit++;
                    boss.damage(arm, bullet.damage, ctx);
                    hit = true;
                    break;
                }
            }
            if (!hit && Entity.overlaps(bullet.x, bullet.y, bullet.halfW(), bullet.halfH(),
                    boss.x, boss.y, boss.halfW(), boss.halfH())) {
                stats.shotsHit++;
                boss.damage(null, bullet.damage, ctx);
                hit = true;
            }
            if (hit && bullet.onHit()) {
                bullet.kill();
            }
        }
    }

    private void enemyBulletsVsPlayer() {
        float px = player.x;
        float py = player.y;
        float hitRadius = player.radius;
        float grazeRadius = player.grazeRadius;
        for (int i = 0; i < enemyBullets.size(); i++) {
            Bullets.EnemyBullet bullet = enemyBullets.get(i);
            if (!bullet.alive) {
                continue;
            }
            float d = Entity.dist(px, py, bullet.x, bullet.y);
            if (d <= hitRadius + bullet.radius) {
                if (player.takeHit(ctx, px - bullet.x, py - bullet.y)) {
                    onPlayerHit();
                }
                bullet.kill();
            } else if (!bullet.grazed && d <= grazeRadius + bullet.radius) {
                // A close pass: the risk-reward core of the whole scoring system.
                bullet.grazed = true;
                player.addGraze(ctx);
                stats.grazeTotal++;
                stats.maxGrazeStreak = Math.max(stats.maxGrazeStreak, player.grazeStreak);
                score += Cfg.GRAZE_SCORE;
            }
        }
    }

    private void lasersVsPlayer() {
        for (int i = 0; i < lasers.size(); i++) {
            if (lasers.get(i).hitsPoint(player.x, player.y, player.radius)) {
                if (player.takeHit(ctx, 0f, 1f)) {
                    onPlayerHit();
                }
            }
        }
    }

    private void enemiesVsPlayer() {
        for (int i = 0; i < enemies.size(); i++) {
            Enemy e = enemies.get(i);
            if (!e.alive) {
                continue;
            }
            if (Entity.overlaps(e.x, e.y, e.halfW(), e.halfH(),
                    player.x, player.y, player.radius, player.radius)) {
                if (player.takeHit(ctx, player.x - e.x, player.y - e.y)) {
                    onPlayerHit();
                }
                if (e.takeDamage(2, ctx, Float.NaN, Float.NaN)) {
                    registerKill(e);
                }
            }
        }
    }

    private void pickupsVsPlayer() {
        // Pickups collect against the whole sprite, not the tiny hitbox: a drop
        // you flew through and did not get would read as a bug.
        float halfW = player.spriteW * 0.5f + 5f;
        float halfH = player.spriteH * 0.5f + 5f;
        for (int i = 0; i < pickups.size(); i++) {
            Pickup p = pickups.get(i);
            if (!p.alive) {
                continue;
            }
            if (Entity.overlaps(player.x, player.y, halfW, halfH,
                    p.x, p.y, p.radius, p.radius)) {
                if (p.kind == Pickup.SCORE) {
                    score += p.value;
                } else {
                    p.apply(player, ctx);
                }
                p.kill();
            }
        }
    }

    private void onPlayerHit() {
        stats.hitsTaken++;
        combo = 0;
        waveDamageTaken = true;
        lastHitT = ctx.clock.t;
    }

    // ---------- draw ----------

    public void draw(Draw d) {
        for (int i = 0; i < pickups.size(); i++) {
            pickups.get(i).draw(d);
        }
        for (int i = 0; i < enemies.size(); i++) {
            enemies.get(i).draw(d);
        }
        if (boss != null) {
            boss.draw(d);
        }
        if (player.alive) {
            player.draw(d);
        }
        for (int i = 0; i < playerBullets.size(); i++) {
            playerBullets.get(i).draw(d);
        }
        for (int i = 0; i < lasers.size(); i++) {
            lasers.get(i).draw(d);
        }
        // Enemy bullets draw last of the world layers: the player must never
        // lose one behind a sprite.
        for (int i = 0; i < enemyBullets.size(); i++) {
            enemyBullets.get(i).draw(d);
        }
    }
}
