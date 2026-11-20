package ph.starswarm.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Star Swarm: a retro fixed-screen space shooter.
 * Alien squadrons fly in on looping paths, settle into a formation, then peel off and dive.
 * Commanders take two hits and can capture your fighter with a tractor beam; shoot that
 * commander while it dives to rescue the fighter and fly two ships side by side.
 *
 * The game runs in a logical space 240 units wide; the height follows the screen.
 */
public class GameView extends View {

    // ---------------- sprites (original pixel art) ----------------
    private static final String[] PLAYER = {
            "......W......",
            "......W......",
            ".....WWW.....",
            ".....WWW.....",
            "...R.WWW.R...",
            "...R.WBW.R...",
            "...WWWBWWW...",
            "R..WWBBBWW..R",
            "R.WWWWBWWWW.R",
            "WWWWWWWWWWWWW",
            "WWW.WWWWW.WWW",
            "WW..RWWWR..WW",
            "W....W.W....W"};
    private static final String[][] DRONE = {{
            "....CCC....",
            "..CCCCCCC..",
            ".CCYCCCYCC.",
            "CCCCCCCCCCC",
            "BB.CBCBC.BB",
            "B..B...B..B",
            "B.........B"}, {
            "....CCC....",
            "..CCCCCCC..",
            ".CCYCCCYCC.",
            "CCCCCCCCCCC",
            ".BBCBCBCBB.",
            "..B.B.B.B..",
            "..........."}};
    private static final String[][] RAIDER = {{
            "R.........R",
            "RR.......RR",
            "RRR.WWW.RRR",
            ".RRRWRWRRR.",
            "..RRRRRRR..",
            "...RYRYR...",
            "....R.R....",
            "....R.R...."}, {
            "...........",
            "R.........R",
            "RR..WWW..RR",
            "RRRRWRWRRRR",
            ".RRRRRRRRR.",
            "...RYRYR...",
            "....R.R....",
            "...R...R..."}};
    private static final String[][] COMMANDER = {{
            "G...........G",
            "GG..GGGGG..GG",
            "GGGGGGGGGGGGG",
            ".GGOGGGGGOGG.",
            "..GGGWGWGGG..",
            "...GGGGGGG...",
            "..G.GOOOG.G..",
            ".G...G.G...G.",
            ".....G.G....."}, {
            ".............",
            "GG..GGGGG..GG",
            "GGGGGGGGGGGGG",
            "GGGOGGGGGOGGG",
            ".GGGGWGWGGGG.",
            "...GGGGGGG...",
            "...GGOOOGG...",
            "..G..G.G..G..",
            ".....G.G....."}};

    private static int pal(char ch, boolean alt) {
        switch (ch) {
            case 'W': return alt ? 0xFFFF5050 : 0xFFF4F4F4;
            case 'R': return 0xFFFF3B3B;
            case 'B': return 0xFF2E7BFF;
            case 'Y': return 0xFFFFD23F;
            case 'C': return 0xFF35D0FF;
            case 'G': return alt ? 0xFFB050FF : 0xFF3ED67A;
            case 'O': return 0xFFFF9A2E;
            default: return 0;
        }
    }

    private static Bitmap sprite(String[] rows, boolean alt) {
        int h = rows.length, w = 0;
        for (String r : rows) w = Math.max(w, r.length());
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < rows[y].length(); x++) px[y * w + x] = pal(rows[y].charAt(x), alt);
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private Bitmap bPlayer, bCaptive;
    private final Bitmap[] bDrone = new Bitmap[2], bRaider = new Bitmap[2], bCmd = new Bitmap[2], bCmdHit = new Bitmap[2];
    private static final float PX = 1.15f;   // logical units per sprite pixel

    // ---------------- world ----------------
    private static final float LW = 240;
    private float LH = 400, s = 1;            // logical height, pixels per unit
    private int W, H;
    private final Random rnd = new Random();

    private enum State { TITLE, PLAY, OVER, PAUSED }
    private State state = State.TITLE;

    private static final int ENTER = 0, HOME = 1, FORM = 2, DIVE = 3, BEAM = 4, EXIT = 5;

    private static final class Enemy {
        int type, col, row, mode, hp;
        float x, y, vx, vy, delay, speed;
        float[] px, py, plen;
        float dist, beamT, beamX;
        boolean captive, fired, escort, bonusExit;
        float shotY1, shotY2;
    }

    private final List<Enemy> enemies = new ArrayList<>();
    private final List<float[]> pShots = new ArrayList<>();   // x, y
    private final List<float[]> eShots = new ArrayList<>();   // x, y, vx, vy
    private final List<float[]> parts = new ArrayList<>();    // x, y, vx, vy, life, maxLife, color
    private final float[][] stars = new float[80][5];         // x, y, speed, color, phase

    private int score, hi, lives, stage, shotsFired, hits;
    private int nextExtra;
    private float time, formT, diveTimer, stageTimer, bannerTimer;
    private String banner = "", banner2 = "";
    private boolean allIn, bonusStage;
    private int bonusHits, bonusTotal;
    private float resultTimer;

    // player
    private float playerX, playerY;
    private boolean alive, dual, touching;
    private float respawnTimer, invuln, fireCool, lastTouchX;
    // capture
    private Enemy capturer;
    private float captureT;
    private boolean capturing;
    // rescued ship coming home
    private float rescueX, rescueY, rescueT;
    private boolean rescuing;
    private float overTimer;

    private ToneGenerator tone;
    private boolean sound = true;
    private final SharedPreferences prefs;
    private long lastFrame;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sp = new Paint();      // sprite paint (no filtering: crisp pixels)
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private final RectF bPause = new RectF(), bSound = new RectF();

    public GameView(Context c) {
        super(c);
        prefs = c.getSharedPreferences("starswarm", Context.MODE_PRIVATE);
        hi = prefs.getInt("hi", 20000);
        sound = prefs.getBoolean("sound", true);
        sp.setFilterBitmap(false);
        sp.setAntiAlias(false);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        bPlayer = sprite(PLAYER, false);
        bCaptive = sprite(PLAYER, true);
        for (int i = 0; i < 2; i++) {
            bDrone[i] = sprite(DRONE[i], false);
            bRaider[i] = sprite(RAIDER[i], false);
            bCmd[i] = sprite(COMMANDER[i], false);
            bCmdHit[i] = sprite(COMMANDER[i], true);
        }
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 55); } catch (RuntimeException e) { tone = null; }
        setKeepScreenOn(true);
    }

    private void beep(int t, int ms) {
        if (!sound || tone == null) return;
        try { tone.startTone(t, ms); } catch (RuntimeException ignored) { }
    }

    public void release() { if (tone != null) { tone.release(); tone = null; } }

    public void pause() { if (state == State.PLAY) { state = State.PAUSED; touching = false; invalidate(); } }

    public boolean onBack() {
        if (state == State.TITLE) return false;
        if (state == State.PLAY) { pause(); return true; }
        state = State.TITLE;
        invalidate();
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w; H = h;
        s = W / LW;
        LH = H / s;
        playerY = LH - 30;
        for (float[] st : stars) {
            st[0] = rnd.nextFloat() * LW; st[1] = rnd.nextFloat() * LH;
            st[2] = 8 + rnd.nextFloat() * 40;
            int[] cols = {0xFFFFFFFF, 0xFFFFD23F, 0xFF35D0FF, 0xFFFF5050, 0xFF3ED67A, 0xFFB050FF};
            st[3] = cols[rnd.nextInt(cols.length)];
            st[4] = rnd.nextFloat() * 6.28f;
        }
        bPause.set(LW - 30, 3, LW - 4, 17);
    }

    // ---------------- paths ----------------

    private static final float[][] TOP_L = {{0.42f, -0.05f}, {0.42f, 0.12f}, {0.30f, 0.32f}, {0.12f, 0.48f}, {0.10f, 0.60f}, {0.22f, 0.66f}, {0.34f, 0.58f}, {0.34f, 0.44f}};
    private static final float[][] SIDE_L = {{-0.06f, 0.80f}, {0.15f, 0.70f}, {0.38f, 0.60f}, {0.55f, 0.52f}, {0.60f, 0.42f}, {0.52f, 0.34f}, {0.40f, 0.36f}, {0.38f, 0.46f}, {0.45f, 0.50f}};
    private static final float[][] TOP_L_EXIT = {{0.55f, 0.48f}, {0.80f, 0.34f}, {1.15f, 0.28f}};
    private static final float[][] SIDE_L_EXIT = {{0.62f, 0.36f}, {0.78f, 0.12f}, {0.84f, -0.10f}};

    /** Scales a normalized path; mirror flips it left-right. */
    private float[][] scaled(float[][] a, float[][] exit, boolean mirror) {
        int n = a.length + (exit == null ? 0 : exit.length);
        float[][] out = new float[n][2];
        for (int i = 0; i < n; i++) {
            float[] p = i < a.length ? a[i] : exit[i - a.length];
            float fx = mirror ? 1 - p[0] : p[0];
            out[i][0] = fx * LW;
            out[i][1] = p[1] * Math.min(LH, LW * 1.9f);
        }
        return out;
    }

    /** Samples a Catmull-Rom spline through the points and stores it on the enemy. */
    private void setPath(Enemy e, float[][] pts) {
        int seg = 10, n = (pts.length - 1) * seg + 1;
        e.px = new float[n]; e.py = new float[n]; e.plen = new float[n];
        int k = 0;
        for (int i = 0; i < pts.length - 1; i++) {
            float[] p0 = pts[Math.max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(pts.length - 1, i + 2)];
            for (int j = 0; j < seg || (i == pts.length - 2 && j == seg); j++) {
                float t = j / (float) seg, t2 = t * t, t3 = t2 * t;
                e.px[k] = 0.5f * (2 * p1[0] + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3);
                e.py[k] = 0.5f * (2 * p1[1] + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3);
                e.plen[k] = k == 0 ? 0 : e.plen[k - 1] + (float) Math.hypot(e.px[k] - e.px[k - 1], e.py[k] - e.py[k - 1]);
                k++;
                if (k >= n) break;
            }
        }
        e.dist = 0;
    }

    /** Moves an enemy along its path; returns true at the end. */
    private boolean follow(Enemy e, float dt) {
        e.dist += e.speed * dt;
        int n = e.px.length;
        if (e.dist >= e.plen[n - 1]) { setPos(e, e.px[n - 1], e.py[n - 1], dt); return true; }
        int i = 1;
        while (i < n - 1 && e.plen[i] < e.dist) i++;
        float seg = e.plen[i] - e.plen[i - 1];
        float f = seg <= 0 ? 0 : (e.dist - e.plen[i - 1]) / seg;
        setPos(e, e.px[i - 1] + (e.px[i] - e.px[i - 1]) * f, e.py[i - 1] + (e.py[i] - e.py[i - 1]) * f, dt);
        return false;
    }

    private void setPos(Enemy e, float x, float y, float dt) {
        if (dt > 0) { e.vx = (x - e.x) / dt; e.vy = (y - e.y) / dt; }
        e.x = x; e.y = y;
    }

    // ---------------- formation ----------------

    private float slotX(Enemy e) {
        if (!allIn) return LW / 2 + 14 * (float) Math.sin(formT * 1.1) + (e.col - 4.5f) * 16;
        float f = 1 + 0.09f * (0.5f + 0.5f * (float) Math.sin(formT * 1.8));
        return LW / 2 + (e.col - 4.5f) * 16 * f;
    }

    private float slotY(Enemy e) {
        float f = allIn ? 1 + 0.09f * (0.5f + 0.5f * (float) Math.sin(formT * 1.8)) : 1;
        return 34 + Math.max(0, LH - 420) * 0.2f + e.row * 15 * f;
    }

    // ---------------- game flow ----------------

    private void newGame() {
        score = 0; lives = 3; stage = 0; shotsFired = 0; hits = 0;
        nextExtra = 20000;
        dual = false; rescuing = false; capturing = false; capturer = null;
        pShots.clear(); eShots.clear(); parts.clear();
        playerX = LW / 2;
        alive = true; invuln = 0;
        state = State.PLAY;
        lastFrame = 0;
        nextStage();
    }

    private void nextStage() {
        stage++;
        enemies.clear();
        eShots.clear();
        bonusStage = stage % 4 == 3;
        allIn = false;
        formT = 0;
        diveTimer = 4;
        stageTimer = -2.2f;                 // negative = banner time before the waves arrive
        banner = bonusStage ? "CHALLENGING STAGE" : "STAGE " + stage;
        banner2 = "";
        bannerTimer = 2.2f;
        resultTimer = 0;
        bonusHits = 0;
        buildWaves();
        beep(ToneGenerator.TONE_PROP_PROMPT, 250);
    }

    private void addEnemy(int type, int col, int row, float[][] pts, float delay) {
        Enemy e = new Enemy();
        e.type = type; e.col = col; e.row = row;
        e.hp = type == 2 ? 2 : 1;
        e.mode = ENTER;
        e.delay = delay;
        e.speed = Math.min(190, 135 + stage * 4);
        e.bonusExit = bonusStage;
        setPath(e, pts);
        e.x = e.px[0]; e.y = e.py[0];
        e.shotY1 = LH * (0.25f + rnd.nextFloat() * 0.3f);
        enemies.add(e);
    }

    private void buildWaves() {
        // slots: commanders row 0 cols 3-6; raiders rows 1-2 cols 1-8; drones rows 3-4 cols 0-9
        float gap = 0.15f, w = 3.0f;
        boolean b = bonusStage;
        float[][] tl = scaled(TOP_L, b ? TOP_L_EXIT : null, false), tr = scaled(TOP_L, b ? TOP_L_EXIT : null, true);
        float[][] sl = scaled(SIDE_L, b ? SIDE_L_EXIT : null, false), sr = scaled(SIDE_L, b ? SIDE_L_EXIT : null, true);
        // wave 1: drones from top-left and raiders from top-right together
        for (int i = 0; i < 4; i++) { addEnemy(0, 3 + i, 3, tl, i * gap); addEnemy(1, 3 + i, 1, tr, i * gap); }
        // wave 2: commanders with raiders from the lower left
        for (int i = 0; i < 4; i++) { addEnemy(2, 3 + i, 0, sl, w + i * 2 * gap); addEnemy(1, 3 + i, 2, sl, w + (i * 2 + 1) * gap); }
        // wave 3: raiders from the lower right
        int[] rc = {1, 2, 7, 8};
        for (int i = 0; i < 4; i++) { addEnemy(1, rc[i], 1, sr, 2 * w + i * 2 * gap); addEnemy(1, rc[i], 2, sr, 2 * w + (i * 2 + 1) * gap); }
        // wave 4: drones from the top right
        for (int i = 0; i < 4; i++) { addEnemy(0, 3 + i, 4, tr, 3 * w + i * 2 * gap); addEnemy(0, rc[i], 3, tr, 3 * w + (i * 2 + 1) * gap); }
        // wave 5: drones from the top left
        int[] c5 = {0, 9, 0, 1, 2, 7, 8, 9}, r5 = {3, 3, 4, 4, 4, 4, 4, 4};
        for (int i = 0; i < 8; i++) addEnemy(0, c5[i], r5[i], tl, 4 * w + i * gap);
        bonusTotal = enemies.size();
    }

    // ---------------- update ----------------

    private void update(float dt) {
        time += dt;
        for (float[] st : stars) { st[1] += st[2] * dt; if (st[1] > LH) { st[1] = 0; st[0] = rnd.nextFloat() * LW; } }
        updateParts(dt);
        if (state != State.PLAY) return;
        if (bannerTimer > 0) bannerTimer -= dt;
        stageTimer += dt;
        formT += dt;
        if (invuln > 0) invuln -= dt;

        updatePlayer(dt);
        updateEnemies(dt);
        updateShots(dt);
        if (!bonusStage && allIn && alive && !capturing) updateDives(dt);
        checkStageEnd(dt);
        if (overTimer > 0) {
            overTimer -= dt;
            if (overTimer <= 0) {
                state = State.OVER;
                if (score > hi) hi = score;
                prefs.edit().putInt("hi", hi).apply();
            }
        }
    }

    private void updatePlayer(float dt) {
        if (fireCool > 0) fireCool -= dt;
        if (alive && !capturing) {
            float maxX = LW - 10 - (dual ? 14 : 0);
            playerX = Math.max(10, Math.min(maxX, playerX));
            if (touching && fireCool <= 0 && stageTimer > 0) fire();
        }
        if (capturing && capturer != null) {
            captureT += dt;
            float tx = capturer.x, ty = capturer.y + 13;
            playerX += (tx - playerX) * Math.min(1, dt * 1.6f);
            playerY += (ty - playerY) * Math.min(1, dt * 1.2f);
            if (captureT > 2.4f) {
                capturing = false;
                capturer.captive = true;
                capturer.mode = HOME;
                capturer = null;
                playerY = LH - 30;
                banner = "FIGHTER CAPTURED";
                banner2 = "";
                bannerTimer = 2.5f;
                loseLife();
            }
        }
        if (rescuing) {
            rescueT += dt;
            float tx = alive ? playerX + 14 : LW / 2, ty = LH - 30;
            float dx = tx - rescueX, dy = ty - rescueY, d = (float) Math.hypot(dx, dy);
            float sp = 70 * dt;
            if (d <= sp + 0.5f) {
                rescuing = false;
                if (alive) { dual = true; playerX = Math.min(playerX, LW - 24); }
                else { alive = true; playerX = rescueX; respawnTimer = 0; invuln = 1.5f; }
                beep(ToneGenerator.TONE_CDMA_CONFIRM, 300);
            } else { rescueX += dx / d * sp; rescueY += dy / d * sp; }
        }
        if (!alive && !capturing && lives > 0 && overTimer <= 0) {
            respawnTimer -= dt;
            boolean calm = true;
            for (Enemy e : enemies) if (e.mode == DIVE || e.mode == BEAM) { calm = false; break; }
            if (respawnTimer <= 0 && (calm || respawnTimer < -4)) {
                alive = true;
                playerX = LW / 2;
                invuln = 1.6f;
                diveTimer = 2.5f;
            }
        }
    }

    private void fire() {
        int volleys = 0;
        for (float[] b : pShots) if (b[2] == 0) volleys++;
        if (volleys >= 2) return;
        pShots.add(new float[]{playerX, playerY - 8, 0});
        if (dual) pShots.add(new float[]{playerX + 14, playerY - 8, 1});
        shotsFired++;
        fireCool = 0.2f;
        beep(ToneGenerator.TONE_PROP_BEEP, 15);
    }

    private void updateEnemies(float dt) {
        boolean entering = false;
        for (Enemy e : enemies) {
            if (e.delay > 0) {
                if (stageTimer > 0) e.delay -= dt;
                entering = true;
                continue;
            }
            switch (e.mode) {
                case ENTER:
                    entering = true;
                    if (!bonusStage && stage > 1 && !e.fired && e.y > e.shotY1 && alive && rnd.nextFloat() < 0.35f + stage * 0.03f) {
                        e.fired = true;
                        enemyShot(e);
                    } else if (e.y > e.shotY1) e.fired = true;
                    if (follow(e, dt)) {
                        if (e.bonusExit) e.mode = EXIT;
                        else e.mode = HOME;
                    }
                    break;
                case HOME: {
                    entering = entering || !allIn;
                    float tx = slotX(e), ty = slotY(e);
                    float dx = tx - e.x, dy = ty - e.y, d = (float) Math.hypot(dx, dy);
                    float spd = 130 * dt;
                    if (d <= spd) { setPos(e, tx, ty, dt); e.mode = FORM; e.vx = 0; e.vy = 0; }
                    else setPos(e, e.x + dx / d * spd, e.y + dy / d * spd, dt);
                    break;
                }
                case FORM:
                    e.x = slotX(e); e.y = slotY(e); e.vx = 0; e.vy = 0;
                    break;
                case DIVE:
                    if (!e.fired && e.y > e.shotY1 && alive) {
                        e.fired = true;
                        enemyShot(e);
                    }
                    if (e.fired && e.shotY2 > 0 && e.y > e.shotY2 && alive) { e.shotY2 = -1; enemyShot(e); }
                    if (follow(e, dt)) {
                        e.y = -12;
                        e.x = slotX(e);
                        e.mode = HOME;
                    }
                    break;
                case BEAM:
                    updateBeam(e, dt);
                    break;
                default:
                    break;
            }
            // collide with player
            if (alive && invuln <= 0 && !capturing && e.mode != FORM && e.mode != EXIT && e.delay <= 0) {
                if (Math.abs(e.x - playerX) < 9 && Math.abs(e.y - playerY) < 8) { killEnemy(e, false); playerHit(0); }
                else if (dual && Math.abs(e.x - (playerX + 14)) < 9 && Math.abs(e.y - playerY) < 8) { killEnemy(e, false); playerHit(1); }
            }
        }
        if (!entering && !allIn && stageTimer > 0) {
            boolean anyEntering = false;
            for (Enemy e : enemies) if (e.mode == ENTER || e.delay > 0) { anyEntering = true; break; }
            if (!anyEntering) allIn = true;
        }
        enemies.removeIf(e -> e.hp <= 0 || e.mode == EXIT);
    }

    private void enemyShot(Enemy e) {
        float tx = playerX + (dual ? 7 : 0);
        float spd = Math.min(210, 140 + stage * 5);
        float dx = tx - e.x, dy = Math.max(40, playerY - e.y);
        float vx = dx / dy * spd;
        vx = Math.max(-60, Math.min(60, vx));
        eShots.add(new float[]{e.x, e.y + 6, vx, spd});
    }

    private void updateDives(float dt) {
        diveTimer -= dt;
        if (diveTimer > 0) return;
        int form = 0;
        for (Enemy e : enemies) if (e.mode == FORM) form++;
        int active = enemies.size() - form;
        float base = Math.max(0.7f, 2.6f - stage * 0.15f);
        if (enemies.size() <= 8) base *= 0.6f;
        diveTimer = base * (0.6f + rnd.nextFloat() * 0.8f);
        if (form == 0 || active >= 3 + stage / 2) return;
        // pick a candidate
        List<Enemy> pool = new ArrayList<>();
        for (Enemy e : enemies) if (e.mode == FORM) pool.add(e);
        Enemy e = pool.get(rnd.nextInt(pool.size()));
        boolean beamBusy = capturing || rescuing;
        for (Enemy o : enemies) if (o.mode == BEAM || o.captive) beamBusy = true;
        if (e.type == 2 && !e.captive && !dual && !beamBusy && rnd.nextFloat() < 0.45f) startBeam(e);
        else {
            startDive(e, 0, false);
            if (e.type == 2) {               // a commander takes up to two escorts
                int taken = 0;
                for (Enemy o : enemies) {
                    if (taken >= 2) break;
                    if (o.mode == FORM && o.type == 1 && o.row == 1 && Math.abs(o.col - e.col) <= 1) {
                        startDive(o, (o.col < e.col ? -1 : 1) * 13, true);
                        taken++;
                    }
                }
            }
        }
    }

    private void startDive(Enemy e, float offset, boolean escort) {
        float side = e.x < LW / 2 ? -1 : 1;
        float x0 = e.x, y0 = e.y;
        float tx = Math.max(16, Math.min(LW - 16, playerX + (rnd.nextFloat() - 0.5f) * 50)) + offset;
        float[][] pts = {{x0, y0}, {x0 + side * 8, y0 - 12}, {x0 + side * 22, y0 - 10}, {x0 + side * 28, y0 + 6}, {x0 + side * 18, y0 + 30},
                {(x0 + tx) / 2, LH * 0.45f}, {tx, LH * 0.72f}, {tx - side * 30, LH * 0.92f}, {tx - side * 50, LH + 24}};
        setPath(e, pts);
        e.mode = DIVE;
        e.escort = escort;
        e.fired = false;
        e.speed = Math.min(200, 120 + stage * 5);
        e.shotY1 = LH * (0.3f + rnd.nextFloat() * 0.15f);
        e.shotY2 = stage >= 3 && rnd.nextBoolean() ? e.shotY1 + 28 : -1;
        if (!escort) beep(ToneGenerator.TONE_PROP_NACK, 120);
    }

    private void startBeam(Enemy e) {
        float side = e.x < LW / 2 ? -1 : 1;
        float x0 = e.x, y0 = e.y;
        float bx = Math.max(30, Math.min(LW - 30, playerX));
        float[][] pts = {{x0, y0}, {x0 + side * 8, y0 - 12}, {x0 + side * 22, y0 - 10}, {x0 + side * 28, y0 + 6},
                {(x0 + bx) / 2, LH * 0.35f}, {bx, LH * 0.56f}};
        setPath(e, pts);
        e.mode = BEAM;
        e.beamT = -1;          // still flying to the beam point
        e.beamX = bx;
        e.speed = 110;
    }

    private void updateBeam(Enemy e, float dt) {
        if (e.beamT < 0) {
            if (follow(e, dt)) { e.beamT = 0; e.vx = 0; e.vy = 0; beep(ToneGenerator.TONE_CDMA_HIGH_L, 600); }
            return;
        }
        if (capturing && capturer == e) return;     // hold still while the fighter is pulled up
        e.beamT += dt;
        float hold = 3.6f;
        if (e.beamT > 0.8f && e.beamT < hold - 0.6f && alive && !dual && invuln <= 0 && !capturing
                && Math.abs(playerX - e.x) < 16 && e.captive == false) {
            capturing = true;
            capturer = e;
            captureT = 0;
            pShots.clear();
            beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
            return;
        }
        if (e.beamT > hold) {
            float[][] pts = {{e.x, e.y}, {e.x, LH * 0.8f}, {e.x + 10, LH + 24}};
            setPath(e, pts);
            e.mode = DIVE;
            e.fired = true;
            e.shotY2 = -1;
            e.speed = 150;
        }
    }

    /** Beam strength 0..1 for drawing. */
    private float beamLevel(Enemy e) {
        if (e.mode != BEAM || e.beamT < 0) return 0;
        if (capturing && capturer == e) return 1;
        float t = e.beamT, hold = 3.6f;
        if (t < 0.8f) return t / 0.8f;
        if (t > hold - 0.6f) return Math.max(0, (hold - t) / 0.6f);
        return 1;
    }

    private void updateShots(float dt) {
        for (Iterator<float[]> it = pShots.iterator(); it.hasNext(); ) {
            float[] b = it.next();
            b[1] -= 330 * dt;
            if (b[1] < -10) { it.remove(); continue; }
            for (Enemy e : enemies) {
                if (e.hp <= 0 || e.delay > 0 || e.mode == EXIT) continue;
                float hw = e.type == 2 ? 8 : 7;
                if (Math.abs(b[0] - e.x) < hw && Math.abs(b[1] - e.y) < 6) {
                    it.remove();
                    hits++;
                    e.hp--;
                    if (e.hp > 0) { beep(ToneGenerator.TONE_PROP_BEEP2, 40); burst(e.x, e.y, 0xFFB050FF, 5); }
                    else killEnemy(e, true);
                    break;
                }
            }
        }
        for (Iterator<float[]> it = eShots.iterator(); it.hasNext(); ) {
            float[] b = it.next();
            b[0] += b[2] * dt;
            b[1] += b[3] * dt;
            if (b[1] > LH + 10) { it.remove(); continue; }
            if (!alive || invuln > 0 || capturing) continue;
            if (Math.abs(b[0] - playerX) < 5 && Math.abs(b[1] - playerY) < 6) { it.remove(); playerHit(0); }
            else if (dual && Math.abs(b[0] - (playerX + 14)) < 5 && Math.abs(b[1] - playerY) < 6) { it.remove(); playerHit(1); }
        }
    }

    private int points(Enemy e) {
        boolean flying = e.mode != FORM;
        if (bonusStage) return 100;
        switch (e.type) {
            case 0: return flying ? 100 : 50;
            case 1: return flying ? 160 : 80;
            default: return flying ? 400 : 150;
        }
    }

    private void killEnemy(Enemy e, boolean byShot) {
        e.hp = 0;
        int col = e.type == 0 ? 0xFF35D0FF : e.type == 1 ? 0xFFFF3B3B : 0xFF3ED67A;
        burst(e.x, e.y, col, 12);
        burst(e.x, e.y, 0xFFFFD23F, 6);
        beep(ToneGenerator.TONE_CDMA_PIP, 90);
        if (byShot) addScore(points(e));
        if (bonusStage && byShot) bonusHits++;
        if (e.type == 2 && e.captive) {
            if (e.mode != FORM && !rescuing) {
                rescuing = true;
                rescueX = e.x; rescueY = e.y - 12; rescueT = 0;
                addScore(byShot ? 1000 : 0);
            }
        }
        if (capturer == e) { capturing = false; capturer = null; playerY = LH - 30; }
    }

    private void addScore(int p) {
        score += p;
        if (score >= nextExtra) {
            lives++;
            nextExtra = nextExtra == 20000 ? 70000 : nextExtra + 70000;
            beep(ToneGenerator.TONE_CDMA_CONFIRM, 400);
        }
        if (score > hi) hi = score;
    }

    private void playerHit(int which) {
        float x = which == 0 ? playerX : playerX + 14;
        burst(x, playerY, 0xFFFFFFFF, 18);
        burst(x, playerY, 0xFFFF3B3B, 14);
        burst(x, playerY, 0xFFFFD23F, 10);
        beep(ToneGenerator.TONE_CDMA_LOW_L, 500);
        if (dual) {
            dual = false;
            if (which == 0) playerX += 14;
            invuln = 1.0f;
            return;
        }
        alive = false;
        loseLife();
    }

    private void loseLife() {
        lives--;
        alive = false;
        respawnTimer = 2.2f;
        if (lives <= 0 && !rescuing) overTimer = 2.5f;
    }

    private void checkStageEnd(float dt) {
        if (stageTimer < 1 || overTimer > 0) return;
        boolean pending = false;
        for (Enemy e : enemies) { pending = true; break; }
        if (pending || capturing) return;
        if (bonusStage) {
            if (resultTimer == 0) {
                resultTimer = 3.2f;
                int bonus = bonusHits == bonusTotal ? 10000 : bonusHits * 100;
                addScore(bonus);
                banner = "NUMBER OF HITS " + bonusHits;
                banner2 = bonusHits == bonusTotal ? "PERFECT!  BONUS 10000" : "BONUS " + bonus;
                bannerTimer = 3.2f;
            }
            resultTimer -= dt;
            if (resultTimer <= 0.001f) nextStage();
        } else if (eShots.isEmpty() && !rescuing) {
            nextStage();
        }
    }

    private void burst(float x, float y, int color, int n) {
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            float v = 20 + rnd.nextFloat() * 70;
            float life = 0.3f + rnd.nextFloat() * 0.5f;
            parts.add(new float[]{x, y, (float) Math.cos(a) * v, (float) Math.sin(a) * v, life, life, color});
        }
    }

    private void updateParts(float dt) {
        for (Iterator<float[]> it = parts.iterator(); it.hasNext(); ) {
            float[] p = it.next();
            p[0] += p[2] * dt; p[1] += p[3] * dt;
            p[2] *= 0.96f; p[3] *= 0.96f;
            p[4] -= dt;
            if (p[4] <= 0) it.remove();
        }
    }

    // ---------------- drawing ----------------

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.04f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (state != State.PAUSED) update(dt);

        c.drawColor(Color.rgb(3, 4, 18));
        c.save();
        c.scale(s, s);
        drawStars(c);
        if (state == State.TITLE) drawTitle(c);
        else {
            drawGame(c);
            drawHud(c);
            if (state == State.OVER) drawOver(c);
            if (state == State.PAUSED) drawPaused(c);
        }
        c.restore();
        if (state != State.PAUSED) postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    private void drawStars(Canvas c) {
        for (float[] st : stars) {
            float tw = 0.5f + 0.5f * (float) Math.sin(time * 3 + st[4]);
            if (tw < 0.25f) continue;
            fill.setColor((int) st[3]);
            fill.setAlpha((int) (255 * tw));
            c.drawRect(st[0], st[1], st[0] + 1.1f, st[1] + 1.1f, fill);
        }
        fill.setAlpha(255);
    }

    private void drawSprite(Canvas c, Bitmap b, float x, float y, float deg) {
        float w = b.getWidth() * PX, h = b.getHeight() * PX;
        c.save();
        if (deg != 0) c.rotate(deg, x, y);
        tmp.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        c.drawBitmap(b, null, tmp, sp);
        c.restore();
    }

    private float heading(Enemy e) {
        if (e.mode == FORM || (e.mode == BEAM && e.beamT >= 0) || (Math.abs(e.vx) < 1 && Math.abs(e.vy) < 1)) return 0;
        return (float) Math.toDegrees(Math.atan2(-e.vx, e.vy));
    }

    private void drawGame(Canvas c) {
        int frame = ((int) (time * 3)) % 2;
        // tractor beams first so ships draw over them
        for (Enemy e : enemies) {
            float lvl = beamLevel(e);
            if (lvl <= 0) continue;
            float top = e.y + 7, bottom = top + (LH - 22 - top) * lvl;
            for (int i = 0; i < 6; i++) {
                float f0 = i / 6f, f1 = (i + 0.6f) / 6f;
                float y0 = top + (bottom - top) * f0, y1 = top + (bottom - top) * f1;
                float w0 = 4 + 20 * f0, w1 = 4 + 20 * f1;
                path.reset();
                path.moveTo(e.x - w0, y0); path.lineTo(e.x + w0, y0); path.lineTo(e.x + w1, y1); path.lineTo(e.x - w1, y1); path.close();
                int col = ((i + (int) (time * 10)) % 3 == 0) ? 0x9935D0FF : 0x772E7BFF;
                fill.setColor(col);
                c.drawPath(path, fill);
            }
        }
        for (Enemy e : enemies) {
            if (e.delay > 0) continue;
            Bitmap b = e.type == 0 ? bDrone[frame] : e.type == 1 ? bRaider[frame] : (e.hp < 2 ? bCmdHit[frame] : bCmd[frame]);
            float deg = heading(e);
            if (e.captive) {
                c.save();
                c.rotate(deg, e.x, e.y);
                drawSprite(c, bCaptive, e.x, e.y - 13, 180);
                c.restore();
            }
            drawSprite(c, b, e.x, e.y, deg);
        }
        // player
        boolean blink = invuln > 0 && ((int) (time * 12)) % 2 == 0;
        if ((alive || capturing) && !blink) {
            float deg = capturing ? captureT * 400 : 0;
            drawSprite(c, capturing ? bCaptive : bPlayer, playerX, playerY, deg);
            if (dual && !capturing) drawSprite(c, bPlayer, playerX + 14, playerY, 0);
        }
        if (rescuing) drawSprite(c, bPlayer, rescueX, rescueY, rescueT < 1.2f ? rescueT * 600 : 0);
        // shots
        fill.setColor(0xFFFFFFFF);
        for (float[] b : pShots) {
            c.drawRect(b[0] - 0.6f, b[1] - 4, b[0] + 0.6f, b[1] + 3, fill);
        }
        fill.setColor(0xFFFF3B3B);
        for (float[] b : pShots) c.drawRect(b[0] - 0.6f, b[1] + 1, b[0] + 0.6f, b[1] + 3, fill);
        for (float[] b : eShots) {
            fill.setColor(0xFFFFD23F);
            c.drawRect(b[0] - 0.8f, b[1] - 3, b[0] + 0.8f, b[1] + 3, fill);
            fill.setColor(0xFFFF3B3B);
            c.drawRect(b[0] - 0.8f, b[1] + 1, b[0] + 0.8f, b[1] + 3, fill);
        }
        for (float[] p : parts) {
            fill.setColor((int) p[6]);
            fill.setAlpha((int) (255 * Math.max(0, p[4] / p[5])));
            float r = 1.4f;
            c.drawRect(p[0] - r / 2, p[1] - r / 2, p[0] + r / 2, p[1] + r / 2, fill);
        }
        fill.setAlpha(255);
        if (bannerTimer > 0) {
            txt(c, banner, LW / 2, LH * 0.55f, 11, 0xFF35D0FF, Paint.Align.CENTER);
            if (!banner2.isEmpty()) txt(c, banner2, LW / 2, LH * 0.55f + 16, 10, 0xFFFFD23F, Paint.Align.CENTER);
        } else if (!alive && !capturing && lives > 0 && overTimer <= 0 && !rescuing && state == State.PLAY) {
            txt(c, "READY", LW / 2, LH * 0.6f, 11, 0xFFFF3B3B, Paint.Align.CENTER);
        }
        if (overTimer > 0 && bannerTimer <= 0) txt(c, "GAME OVER", LW / 2, LH * 0.55f, 13, 0xFFFF3B3B, Paint.Align.CENTER);
    }

    private void txt(Canvas c, String t, float x, float y, float size, int color, Paint.Align al) {
        text.setTextSize(size);
        text.setTextAlign(al);
        text.setColor(color);
        c.drawText(t, x, y, text);
    }

    private void drawHud(Canvas c) {
        txt(c, "1UP", 8, 10, 8, 0xFFFF3B3B, Paint.Align.LEFT);
        txt(c, String.valueOf(score), 8, 20, 8, 0xFFFFFFFF, Paint.Align.LEFT);
        txt(c, "HIGH SCORE", LW / 2, 10, 8, 0xFFFF3B3B, Paint.Align.CENTER);
        txt(c, String.valueOf(hi), LW / 2, 20, 8, 0xFFFFFFFF, Paint.Align.CENTER);
        if (state == State.PLAY) {
            fill.setColor(0x55FFFFFF);
            c.drawRoundRect(bPause, 3, 3, fill);
            txt(c, "II", bPause.centerX(), bPause.centerY() + 3, 8, 0xFFFFFFFF, Paint.Align.CENTER);
        }
        // reserve ships
        int show = Math.max(0, lives - (alive || capturing ? 1 : 0));
        for (int i = 0; i < Math.min(show, 6); i++) {
            Bitmap b = bPlayer;
            tmp.set(6 + i * 11, LH - 12, 6 + i * 11 + 9, LH - 3);
            c.drawBitmap(b, null, tmp, sp);
        }
        // stage flags: big flag = 10 stages, small = 1
        float fx = LW - 8;
        int big = stage / 10, small = stage % 10;
        for (int i = 0; i < small; i++) { flag(c, fx, LH - 4, 1); fx -= 6; }
        for (int i = 0; i < big; i++) { flag(c, fx - 2, LH - 4, 1.6f); fx -= 10; }
    }

    private void flag(Canvas c, float x, float y, float k) {
        fill.setColor(0xFFF4F4F4);
        c.drawRect(x, y - 9 * k, x + 0.8f * k, y, fill);
        fill.setColor(0xFFFF3B3B);
        path.reset();
        path.moveTo(x + 0.8f * k, y - 9 * k);
        path.lineTo(x + 4.5f * k, y - 7 * k);
        path.lineTo(x + 0.8f * k, y - 5 * k);
        path.close();
        c.drawPath(path, fill);
    }

    private void drawTitle(Canvas c) {
        float y = Math.max(60, LH * 0.2f);
        txt(c, "HIGH SCORE  " + hi, LW / 2, 18, 9, 0xFFFF3B3B, Paint.Align.CENTER);
        txt(c, "STAR", LW / 2, y, 30, 0xFFFFD23F, Paint.Align.CENTER);
        txt(c, "SWARM", LW / 2, y + 30, 30, 0xFF35D0FF, Paint.Align.CENTER);
        float ty = y + 62;
        int frame = ((int) (time * 3)) % 2;
        txt(c, "SCORE        FORMATION  DIVING", LW / 2, ty, 7.5f, 0xFFFFFFFF, Paint.Align.CENTER);
        drawSprite(c, bDrone[frame], 50, ty + 16, 0);
        txt(c, "50", 128, ty + 19, 9, 0xFF35D0FF, Paint.Align.CENTER);
        txt(c, "100", 192, ty + 19, 9, 0xFF35D0FF, Paint.Align.CENTER);
        drawSprite(c, bRaider[frame], 50, ty + 36, 0);
        txt(c, "80", 128, ty + 39, 9, 0xFFFF3B3B, Paint.Align.CENTER);
        txt(c, "160", 192, ty + 39, 9, 0xFFFF3B3B, Paint.Align.CENTER);
        drawSprite(c, bCmd[frame], 50, ty + 57, 0);
        txt(c, "150", 128, ty + 60, 9, 0xFF3ED67A, Paint.Align.CENTER);
        txt(c, "400", 192, ty + 60, 9, 0xFF3ED67A, Paint.Align.CENTER);
        txt(c, "Commanders take 2 hits and can", LW / 2, ty + 82, 7.5f, 0xFFB0B8D0, Paint.Align.CENTER);
        txt(c, "capture your fighter. Shoot them", LW / 2, ty + 92, 7.5f, 0xFFB0B8D0, Paint.Align.CENTER);
        txt(c, "while diving to win it back!", LW / 2, ty + 102, 7.5f, 0xFFB0B8D0, Paint.Align.CENTER);
        drawSprite(c, bPlayer, LW / 2 - 8, ty + 124, 0);
        drawSprite(c, bPlayer, LW / 2 + 8, ty + 124, 0);
        txt(c, "Drag to move  -  hold to fire", LW / 2, ty + 148, 8, 0xFFFFFFFF, Paint.Align.CENTER);
        if (((int) (time * 2)) % 2 == 0) txt(c, "TAP TO START", LW / 2, ty + 172, 12, 0xFFFFD23F, Paint.Align.CENTER);
        bSound.set(LW / 2 - 40, LH - 34, LW / 2 + 40, LH - 18);
        fill.setColor(0x33FFFFFF);
        c.drawRoundRect(bSound, 4, 4, fill);
        txt(c, sound ? "SOUND ON" : "SOUND OFF", LW / 2, LH - 23, 8, 0xFFFFFFFF, Paint.Align.CENTER);
    }

    private void drawOver(Canvas c) {
        fill.setColor(0xCC03040F);
        c.drawRect(20, LH * 0.3f, LW - 20, LH * 0.3f + 120, fill);
        float y = LH * 0.3f;
        txt(c, "GAME OVER", LW / 2, y + 22, 14, 0xFFFF3B3B, Paint.Align.CENTER);
        txt(c, "- RESULTS -", LW / 2, y + 42, 9, 0xFFFFD23F, Paint.Align.CENTER);
        txt(c, String.format(Locale.US, "SHOTS FIRED   %6d", shotsFired), LW / 2, y + 60, 8.5f, 0xFFFFFFFF, Paint.Align.CENTER);
        txt(c, String.format(Locale.US, "NUMBER OF HITS%6d", hits), LW / 2, y + 74, 8.5f, 0xFFFFFFFF, Paint.Align.CENTER);
        float ratio = shotsFired == 0 ? 0 : 100f * hits / shotsFired;
        txt(c, String.format(Locale.US, "HIT RATIO   %6.1f %%", ratio), LW / 2, y + 88, 8.5f, 0xFFFFFFFF, Paint.Align.CENTER);
        txt(c, "Tap to continue", LW / 2, y + 110, 8, 0xFFB0B8D0, Paint.Align.CENTER);
    }

    private void drawPaused(Canvas c) {
        fill.setColor(0xAA000000);
        c.drawRect(0, 0, LW, LH, fill);
        txt(c, "PAUSED", LW / 2, LH * 0.45f, 16, 0xFFFFD23F, Paint.Align.CENTER);
        txt(c, "Tap to resume", LW / 2, LH * 0.45f + 20, 9, 0xFFFFFFFF, Paint.Align.CENTER);
        txt(c, "Back again for the title screen", LW / 2, LH * 0.45f + 34, 8, 0xFFB0B8D0, Paint.Align.CENTER);
    }

    // ---------------- input ----------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / s, y = e.getY() / s;
        int a = e.getActionMasked();
        switch (state) {
            case TITLE:
                if (a == MotionEvent.ACTION_UP) {
                    if (bSound.contains(x, y)) {
                        sound = !sound;
                        prefs.edit().putBoolean("sound", sound).apply();
                    } else newGame();
                }
                return true;
            case OVER:
                if (a == MotionEvent.ACTION_UP) state = State.TITLE;
                return true;
            case PAUSED:
                if (a == MotionEvent.ACTION_UP) { state = State.PLAY; lastFrame = 0; invalidate(); }
                return true;
            default:
                break;
        }
        switch (a) {
            case MotionEvent.ACTION_DOWN:
                if (x > bPause.left - 6 && y < bPause.bottom + 6) { pause(); return true; }
                touching = true;
                lastTouchX = x;
                break;
            case MotionEvent.ACTION_MOVE:
                if (touching) {
                    if (alive && !capturing) playerX += (x - lastTouchX) * 1.15f;
                    lastTouchX = x;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                break;
            default:
                break;
        }
        return true;
    }
}
