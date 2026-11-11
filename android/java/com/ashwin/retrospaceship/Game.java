package com.ashwin.retrospaceship;

import android.content.Context;

import java.util.List;

/**
 * The state machine: what screen we are on, what a touch means there, and what
 * gets updated and drawn each frame.
 *
 * <p>Gameplay time and interface time are kept separate throughout. The world
 * advances on {@link Clock#t}, which stops dead when the game is paused or
 * frozen for a hitstop; menus and title animations advance on wall time, so they
 * keep breathing regardless of what the simulation is doing.
 *
 * <p>The playfield is the entire screen. There is no control strip: abilities
 * are gestures (see {@link Gestures}) and the only permanent chrome is a small
 * pause target at the top centre, in the band above where the ship can fly.
 */
public final class Game implements Gestures.Listener {

    public static final int TITLE = 0, PLAYING = 1, PAUSED = 2, SETTINGS = 3,
            SCORES = 4, HELP = 5, UPGRADE = 6, RESULTS = 7, DYING = 8;

    // Menu actions.
    private static final int A_PLAY = 1, A_SCORES = 2, A_SETTINGS = 3, A_HELP = 4,
            A_RESUME = 5, A_QUIT = 6, A_BACK = 7, A_THEME = 8, A_MUSIC = 9, A_SFX = 10,
            A_CRT = 11, A_QUALITY = 12, A_MUTE = 13, A_AGAIN = 14, A_MENU = 15;

    /** How long the gesture reminder stays up at the start of a run. */
    private static final double HINT_MS = 7000.0;

    public final Assets assets;
    public final Sfx sfx;
    public final Save save;
    private final Clock clock = new Clock();
    private final Fx fx = new Fx();
    private final Ctx ctx;
    private final Starfield stars;
    private final Gestures gestures;

    public final float worldH;

    private World world;
    private Director director;

    public int state = TITLE;
    private int returnFromSettings = TITLE;
    private double wallMs = 0.0;
    private double deathUntil = 0.0;
    private double hintUntil = 0.0;
    private int lastRank = 0;

    private Ui.Menu menu;
    private int pressedRow = -1;
    private boolean pauseArmed = false;
    private List<Upgrades.Card> upgradeCards;
    private int upgradeHighlight = -1;

    // Relative drag: the ship follows finger *motion* from where the touch began,
    // rather than jumping under the fingertip. Absolute drag would park a finger
    // on top of the 3px hitbox the player has to be able to see -- which matters
    // even more now that the field covers the whole screen.
    private float dragAnchorX, dragAnchorY, shipAnchorX, shipAnchorY;
    private boolean dragging = false;

    public Game(Context context, Assets assets, Sfx sfx) {
        this.assets = assets;
        this.sfx = sfx;
        this.save = new Save(context);
        this.worldH = Cfg.H;

        ctx = new Ctx(clock, fx, sfx);
        stars = new Starfield();
        gestures = new Gestures(this);

        Theme.apply(save.theme);
        sfx.muted = save.muted;
        sfx.sfxVolume = save.sfxVolume;
        sfx.musicVolume = save.musicVolume;
        fx.setLowQuality(save.lowQuality);

        buildTitleMenu();
        sfx.playMusic(Sfx.MENU);
    }

    // ---------- menus ----------

    private void buildTitleMenu() {
        menu = new Ui.Menu(Cfg.H * 0.40f);
        menu.add("PLAY", A_PLAY);
        menu.add("HIGH SCORES", A_SCORES);
        menu.add("SETTINGS", A_SETTINGS);
        menu.add("HOW TO PLAY", A_HELP);
    }

    private void buildPauseMenu() {
        menu = new Ui.Menu(Cfg.H * 0.36f);
        menu.add("RESUME", A_RESUME);
        menu.add("SETTINGS", A_SETTINGS);
        menu.add("QUIT RUN", A_QUIT);
    }

    private void buildSettingsMenu() {
        menu = new Ui.Menu(Cfg.H * 0.24f);
        menu.add("THEME", A_THEME, Theme.currentName());
        menu.add("MUSIC", A_MUSIC, save.musicVolume + "%");
        menu.add("SFX", A_SFX, save.sfxVolume + "%");
        menu.add("MUTE", A_MUTE, save.muted ? "ON" : "OFF");
        menu.add("SCANLINES", A_CRT, save.crt ? "ON" : "OFF");
        menu.add("EFFECTS", A_QUALITY, save.lowQuality ? "LOW" : "HIGH");
        menu.add("BACK", A_BACK);
    }

    private void buildListMenu(int action) {
        menu = new Ui.Menu(worldH - 108f);
        menu.add("BACK", action);
    }

    private void buildResultsMenu() {
        menu = new Ui.Menu(worldH - 150f);
        menu.add("PLAY AGAIN", A_AGAIN);
        menu.add("MENU", A_MENU);
    }

    // ---------- run lifecycle ----------

    private void startRun() {
        world = new World();
        world.bind(ctx);
        director = new Director();
        clock.clearEffects();
        fx.clear();
        releaseControls();
        world.player.resetForRun();
        hintUntil = wallMs + HINT_MS;
        state = PLAYING;
        sfx.playMusic(Sfx.BATTLE_A);
    }

    private void endRun() {
        lastRank = save.addScore(world.score, world.stats.stage, world.stats.wave,
                (long) world.stats.timeMs, world.player.weaponName());
        save.recordRun(world.stats);
        buildResultsMenu();
        releaseControls();
        state = RESULTS;
        sfx.playMusic(Sfx.MENU);
        sfx.play("game_over");
    }

    // ---------- frame ----------

    public void update(float realMs) {
        wallMs += realMs;

        if (state == PLAYING) {
            gestures.tick(wallMs);
        }
        if (state == PLAYING || state == DYING) {
            clock.tick(realMs);
            float boost = world.player.overdriveActive() ? 2.2f : 1f;
            stars.update(clock.dt, boost);
            world.update();
            director.update(ctx, world);
            Hud.lowHealthVignette(world, fx);

            if (state == PLAYING && !world.player.alive) {
                state = DYING;
                deathUntil = wallMs + 1600.0;
                clock.slowmo(Cfg.SLOWMO_DEATH_SCALE, 1400f);
                fx.flash(Theme.color(Theme.RED), 400f, 150);
                fx.shockwave(world.player.x, world.player.y, 8f, 300f, 900f,
                        Theme.color(Theme.RED), 4f);
            } else if (state == PLAYING && director.bossJustCleared) {
                upgradeCards = Upgrades.drawThree();
                upgradeHighlight = -1;
                state = UPGRADE;
            }
            if (state == DYING && wallMs >= deathUntil) {
                endRun();
            }
        } else {
            // Everything else still wants a living background, so the starfield
            // keeps drifting on wall time behind every menu.
            stars.update(realMs, 1f);
        }

        fx.update(state == PLAYING || state == DYING ? clock.dt : 0f, realMs);
    }

    public void draw(Draw d) {
        // The linework layer is the one part of the sprite treatment worth
        // dropping on a slow device, so it follows the existing EFFECTS setting.
        d.detailLines = !save.lowQuality;
        d.canvas.save();
        // Screen shake is applied to the whole scene, menus included, so a hit
        // taken as a pause menu opens still lands visually.
        d.canvas.translate(fx.shakeX, fx.shakeY);
        stars.draw(d);

        if (world != null && state != TITLE && state != SCORES && state != HELP) {
            world.draw(d);
        }
        fx.draw(d);

        if (save.crt) {
            drawScanlines(d);
        }

        if (state == PLAYING || state == DYING || state == PAUSED || state == UPGRADE) {
            Hud.draw(d, world, director, save.highScore, clock.t);
            Hud.drawPause(d, pauseArmed);
            if (state == PLAYING && wallMs < hintUntil) {
                Hud.drawGestureHint(d, (float) ((hintUntil - wallMs) / 900.0));
            }
        }
        d.canvas.restore();

        switch (state) {
            case TITLE:
                Ui.scrim(d, worldH, 120);
                Ui.title(d, wallMs);
                menu.draw(d, wallMs);
                footer(d, "TAP TO CHOOSE");
                break;
            case PAUSED:
                Ui.scrim(d, worldH, 175);
                Ui.heading(d, "PAUSED");
                menu.draw(d, wallMs);
                break;
            case SETTINGS:
                Ui.scrim(d, worldH, 195);
                Ui.heading(d, "SETTINGS");
                menu.draw(d, wallMs);
                footer(d, "TAP A ROW TO CHANGE IT");
                break;
            case SCORES:
                Ui.scrim(d, worldH, 195);
                Ui.scores(d, save);
                menu.draw(d, wallMs);
                break;
            case HELP:
                Ui.scrim(d, worldH, 205);
                Ui.help(d);
                menu.draw(d, wallMs);
                break;
            case UPGRADE:
                Ui.scrim(d, worldH, 185);
                Ui.upgrade(d, upgradeCards, upgradeHighlight, wallMs);
                break;
            case RESULTS:
                Ui.scrim(d, worldH, 205);
                Ui.results(d, world, lastRank, wallMs);
                menu.draw(d, wallMs);
                break;
            default:
                break;
        }
    }

    private void footer(Draw d, String text) {
        d.text(text, 12f, Cfg.W * 0.5f, worldH - 34f,
                Theme.shade(Theme.color(Theme.WHITE), 0.55f), 200, d.assets.hud);
    }

    private void drawScanlines(Draw d) {
        int color = Theme.withAlpha(Theme.color(Theme.BLACK), 60);
        for (float y = 0f; y < Cfg.H; y += 3f) {
            d.rect(0f, y, Cfg.W, 1f, color);
        }
    }

    // ---------- input ----------

    public void onDown(int pointerId, float x, float y) {
        if (state == PLAYING) {
            if (Hud.inPause(x, y)) {
                // Swallowed rather than passed to the recogniser, so a tap on
                // pause can never also be counted toward a bomb double-tap.
                pauseArmed = true;
                return;
            }
            gestures.down(pointerId, x, y, wallMs);
            return;
        }
        if (state == DYING) {
            return;
        }
        if (state == UPGRADE) {
            upgradeHighlight = Ui.cardHit(x, y, upgradeCards.size());
            return;
        }
        if (menu != null) {
            pressedRow = menu.hit(x, y);
            menu.highlight = pressedRow;
            if (pressedRow >= 0) {
                sfx.play("ui_move", 0.5f);
            }
        }
    }

    public void onMove(int pointerId, float x, float y) {
        if (state == PLAYING) {
            if (pauseArmed && !Hud.inPause(x, y)) {
                pauseArmed = false;
            }
            gestures.move(pointerId, x, y, wallMs);
            return;
        }
        if (state == UPGRADE) {
            upgradeHighlight = Ui.cardHit(x, y, upgradeCards.size());
            return;
        }
        if (menu != null && pressedRow >= 0) {
            int row = menu.hit(x, y);
            menu.highlight = row == pressedRow ? row : -1;
        }
    }

    public void onUp(int pointerId, float x, float y) {
        if (state == PLAYING || state == DYING) {
            if (pauseArmed) {
                pauseArmed = false;
                if (state == PLAYING && Hud.inPause(x, y)) {
                    pause();
                }
                return;
            }
            gestures.up(pointerId, x, y, wallMs);
            return;
        }
        if (state == UPGRADE) {
            int index = Ui.cardHit(x, y, upgradeCards.size());
            if (index >= 0) {
                upgradeCards.get(index).effect.apply(world);
                sfx.play("ui_select");
                fx.popup(Cfg.W * 0.5f, Cfg.H * 0.5f, upgradeCards.get(index).title,
                        Theme.color(Theme.YELLOW), 20f);
                releaseControls();
                state = PLAYING;
            }
            upgradeHighlight = -1;
            return;
        }
        if (menu != null && pressedRow >= 0 && menu.hit(x, y) == pressedRow) {
            Ui.Item item = menu.items.get(pressedRow);
            sfx.play("ui_select");
            act(item.action);
        }
        pressedRow = -1;
        if (menu != null) {
            menu.highlight = -1;
        }
    }

    // ---------- gesture callbacks ----------

    @Override
    public void onDragStart(float x, float y) {
        dragging = true;
        dragAnchorX = x;
        dragAnchorY = y;
        shipAnchorX = world.player.x;
        shipAnchorY = world.player.y;
        world.player.beginPilot();
    }

    @Override
    public void onDrag(float x, float y) {
        if (!dragging) {
            return;
        }
        world.player.setTarget(shipAnchorX + (x - dragAnchorX),
                shipAnchorY + (y - dragAnchorY));
    }

    @Override
    public void onDragEnd() {
        dragging = false;
        if (world != null) {
            world.player.endPilot();
        }
    }

    @Override
    public void onBomb() {
        if (state != PLAYING) {
            return;
        }
        if (!world.detonateBomb()) {
            // Nothing to spend: say so rather than silently eating the gesture.
            sfx.play("ui_back", 0.4f);
            fx.popup(world.player.x, world.player.y - 44f, "NO BOMBS",
                    Theme.color(Theme.RED), 14f);
        }
    }

    @Override
    public void onOverdrive() {
        if (state != PLAYING) {
            return;
        }
        if (!world.player.activateOverdrive(ctx)) {
            sfx.play("ui_back", 0.4f);
            fx.popup(world.player.x, world.player.y - 44f,
                    ((int) (world.player.overdrive)) + "%", Theme.color(Theme.CYAN), 14f);
        }
    }

    @Override
    public void onPrecision(boolean precise) {
        if (world != null) {
            world.player.focus = precise;
        }
    }

    /**
     * Drop all touch state and hand the controls back.
     *
     * <p>{@link Gestures#reset} fires no callbacks by design, so without this the
     * ship would keep flying toward whatever target it last had -- which is
     * exactly what happens when a boss dies mid-drag and the upgrade screen opens.
     */
    private void releaseControls() {
        gestures.reset();
        dragging = false;
        if (world != null) {
            world.player.endPilot();
        }
    }

    public void pause() {
        if (state != PLAYING) {
            return;
        }
        state = PAUSED;
        releaseControls();
        buildPauseMenu();
        sfx.play("ui_back");
    }

    /** The hardware/gesture back action. Returns false when the app should exit. */
    public boolean onBack() {
        switch (state) {
            case PLAYING:
                pause();
                return true;
            case PAUSED:
                state = PLAYING;
                return true;
            case SETTINGS:
                leaveSettings();
                return true;
            case SCORES:
            case HELP:
                state = TITLE;
                buildTitleMenu();
                return true;
            case RESULTS:
                toMenu();
                return true;
            case UPGRADE:
            case DYING:
                return true;
            default:
                return false;
        }
    }

    private void act(int action) {
        switch (action) {
            case A_PLAY:
                startRun();
                break;
            case A_SCORES:
                state = SCORES;
                buildListMenu(A_BACK);
                break;
            case A_HELP:
                state = HELP;
                buildListMenu(A_BACK);
                break;
            case A_SETTINGS:
                returnFromSettings = state;
                state = SETTINGS;
                buildSettingsMenu();
                break;
            case A_RESUME:
                releaseControls();
                state = PLAYING;
                break;
            case A_QUIT:
                endRun();
                break;
            case A_BACK:
                if (state == SETTINGS) {
                    leaveSettings();
                } else {
                    state = TITLE;
                    buildTitleMenu();
                }
                break;
            case A_AGAIN:
                startRun();
                break;
            case A_MENU:
                toMenu();
                break;
            case A_THEME:
                Theme.cycle();
                save.theme = Theme.current();
                save.save();
                buildSettingsMenu();
                break;
            case A_MUSIC:
                save.musicVolume = cycleVolume(save.musicVolume);
                sfx.musicVolume = save.musicVolume;
                sfx.refreshMusic();
                save.save();
                buildSettingsMenu();
                break;
            case A_SFX:
                save.sfxVolume = cycleVolume(save.sfxVolume);
                sfx.sfxVolume = save.sfxVolume;
                save.save();
                buildSettingsMenu();
                break;
            case A_MUTE:
                save.muted = !save.muted;
                sfx.setMuted(save.muted);
                save.save();
                buildSettingsMenu();
                break;
            case A_CRT:
                save.crt = !save.crt;
                save.save();
                buildSettingsMenu();
                break;
            case A_QUALITY:
                save.lowQuality = !save.lowQuality;
                fx.setLowQuality(save.lowQuality);
                save.save();
                buildSettingsMenu();
                break;
            default:
                break;
        }
    }

    private void leaveSettings() {
        state = returnFromSettings == PAUSED ? PAUSED : TITLE;
        if (state == PAUSED) {
            buildPauseMenu();
        } else {
            buildTitleMenu();
        }
    }

    private void toMenu() {
        state = TITLE;
        world = null;
        director = null;
        releaseControls();
        buildTitleMenu();
        sfx.playMusic(Sfx.MENU);
    }

    private static int cycleVolume(int value) {
        int next = value + 20;
        return next > 100 ? 0 : next;
    }

    public void onPauseApp() {
        if (state == PLAYING) {
            pause();
        }
        sfx.stopMusic();
    }

    public void onResumeApp() {
        sfx.refreshMusic();
    }
}
