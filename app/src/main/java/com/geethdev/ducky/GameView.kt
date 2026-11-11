package com.geethdev.ducky

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Ducky - guide the duck's stretchy neck through the maze to reach the fish.
 * This view only draws the game world. HUD / buttons / win screen live in XML
 * (activity_main.xml) and are driven through [GameListener].
 *
 * Game rules, maze generation, neck logic and the public API are unchanged.
 * Added on top: polished rendering (tubular walls, floor, animated fish, expressive
 * duck), touch feedback, wall-bump / neck-limit feedback with haptics, idle hints,
 * a level intro animation and a win celebration.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface GameListener {
        fun onLevelChanged(level: Int)
        fun onNeckChanged(used: Int, max: Int)
        fun onWin(level: Int)
    }

    companion object {
        // directions
        private const val UP = 0
        private const val RIGHT = 1
        private const val DOWN = 2
        private const val LEFT = 3
        private val DC = intArrayOf(0, 1, 0, -1)
        private val DR = intArrayOf(-1, 0, 1, 0)
        private const val STEP_MS = 85L

        // result of trying to move the head
        private const val MOVED = 0
        private const val BLOCKED_WALL = 1
        private const val BLOCKED_LIMIT = 2

        // tuning
        /** Idle time before the path hint fades in. Set to Long.MAX_VALUE to disable hints. */
        private const val HINT_IDLE_MS = 9000L
        private const val HINT_LEN = 6
        private const val BUMP_FX_MS = 260L
        private const val INTRO_S = 0.35f
        private const val MAX_PARTICLES = 220
        private const val BG_BUBBLES = 14

        // particle kinds
        private const val P_CONFETTI = 0
        private const val P_SPARKLE = 1
        private const val P_PUFF = 2
        private const val P_BUBBLE = 3
        private const val P_RING = 4

        // colors
        private val C_MAZE_BG = 0xFF9EAEFA.toInt()
        private val C_MAZE_TOP = 0xFFA9B8FC.toInt()
        private val C_MAZE_BOTTOM = 0xFF93A3F5.toInt()
        private val C_WALL = 0xFF2B3A9E.toInt()
        private val C_WALL_DARK = 0xFF1F2B7A.toInt()
        private val C_SHADOW = 0x3318206B.toInt()
        private val C_YELLOW = 0xFFFFD43B.toInt()
        private val C_YELLOW_DARK = 0xFFF2B705.toInt()
        private val C_YELLOW_LIGHT = 0xFFFFE887.toInt()
        private val C_WING = 0xFFFFC928.toInt()
        private val C_OUTLINE = 0xFF1B1B2F.toInt()
        private val C_ORANGE = 0xFFFF9F1C.toInt()
        private val C_ORANGE_DARK = 0xFFE57C00.toInt()
        private val C_BELLY = 0xFFFFC46B.toInt()
        private val C_WHITE = 0xFFFFFFFF.toInt()
        private val C_STRAIN_ORANGE = 0xFFFF8A3D.toInt()
        private val C_STRAIN_RED = 0xFFFF5252.toInt()
        private val C_CHEEK = 0x66FF6B6B.toInt()
        private val CONFETTI_COLORS = intArrayOf(
            0xFFFFD43B.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFFFFF.toInt(),
            0xFF7DE0FF.toInt(), 0xFFFF7EB6.toInt(), 0xFF6BEA8B.toInt()
        )
    }

    private class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val maxLife: Float,
        val size: Float,
        val color: Int,
        val kind: Int
    ) {
        var life = 0f
        var rot = 0f
        var vr = 0f
    }

    private var gameListener: GameListener? = null

    // game state
    private var level = 1
    private var cols = 0
    private var rows = 0
    private var wall: Array<Array<BooleanArray>> = emptyArray()   // [row][col][dir]
    private val neck = ArrayList<IntArray>()                      // cells {col,row}
    private var fishC = 0
    private var fishR = 0
    private var maxNeck = 0
    private var lastDir = DOWN
    private var won = false
    private var touching = false
    private var touchX = 0f
    private var touchY = 0f
    private var lastStep = 0L

    // layout
    private var viewW = 0
    private var viewH = 0
    private var cell = 0f
    private var ox = 0f
    private var oy = 0f
    private var bodyX = 0f
    private var bodyY = 0f
    private var headX = 0f
    private var headY = 0f

    // animation / feedback state
    private var lastFrame = 0L
    private var introT = 0f
    private var wonAt = 0L
    private var winT = 0f
    private var stepPulse = 0f
    private var bump = 0f
    private var bumpDir = DOWN
    private var strain = 0f
    private var lastBumpFx = 0L
    private var touchAlpha = 0f
    private var lastBubbleAt = 0L
    private var bodyOffY = 0f
    private var drawHeadX = 0f
    private var drawHeadY = 0f

    // hint
    private var lastProgress = 0L
    private var hintDirty = true
    private var hintAlpha = 0f
    private var hintCount = 0
    private val hintCells = IntArray(HINT_LEN * 2)

    // cached drawing data
    private val wallPath = Path()
    private val panelPath = Path()
    private var panelShader: Shader? = null
    private var bgShader: Shader? = null

    // background bubbles
    private val bubX = FloatArray(BG_BUBBLES)
    private val bubY = FloatArray(BG_BUBBLES)
    private val bubR = FloatArray(BG_BUBBLES)
    private val bubSp = FloatArray(BG_BUBBLES)
    private val bubPh = FloatArray(BG_BUBBLES)

    private val particles = ArrayList<Particle>()

    // paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    // ------------------------------------------------------------ public API

    val currentLevel: Int
        get() = level

    fun setListener(l: GameListener?) {
        gameListener = l
        if (wall.isNotEmpty()) {
            notifyLevel()
            notifyNeck()
        }
    }

    fun setStartingLevel(startingLevel: Int) {
        level = max(1, startingLevel)
        if (viewW > 0 && wall.isNotEmpty()) newLevel()
    }

    fun resetLevel() {
        if (viewW > 0) newLevel()
    }

    fun nextLevel() {
        level++
        if (viewW > 0) newLevel()
    }

    /** Shows the path hint right away (it also appears automatically after a while of inactivity). */
    fun showHint() {
        if (won) return
        lastProgress = SystemClock.uptimeMillis() - HINT_IDLE_MS - 1L
        hintDirty = true
    }

    private fun notifyLevel() {
        gameListener?.onLevelChanged(level)
    }

    private fun notifyNeck() {
        gameListener?.onNeckChanged(neck.size, maxNeck)
    }

    // ------------------------------------------------------------------ setup

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w
        viewH = h
        if (w > 0 && h > 0) {
            bgShader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                0x26FFFFFF, 0x2E2B3A9E, Shader.TileMode.CLAMP
            )
            for (i in 0 until BG_BUBBLES) {
                bubR[i] = w * (0.012f + Random.nextFloat() * 0.03f)
                bubX[i] = Random.nextFloat() * w
                bubY[i] = Random.nextFloat() * h
                bubSp[i] = h * (0.025f + Random.nextFloat() * 0.06f)
                bubPh[i] = Random.nextFloat() * 6.28f
            }
            newLevel()
        }
    }

    private fun newLevel() {
        cols = min(4 + level, 10)
        val margin = viewW * 0.04f
        cell = (viewW - 2 * margin) / cols
        val topSpace = cell * 1.7f                 // room for the duck
        val bottomSpace = margin * 1.5f
        val fit = ((viewH - topSpace - bottomSpace) / cell).toInt()
        rows = max(5, min(fit, cols + 2 + level))
        ox = margin
        val free = viewH - topSpace - bottomSpace - rows * cell
        oy = topSpace + max(0f, free / 2f)

        generateMaze()
        fishC = cols - 1
        fishR = rows - 1

        val dist = shortestPathCells()
        val factor = max(1.4f, 2.4f - 0.12f * level)
        maxNeck = ceil(dist * factor).toInt()

        bodyX = ox + cell * 0.5f
        bodyY = oy - cell * 0.85f
        neck.clear()
        neck.add(intArrayOf(0, 0))
        lastDir = DOWN
        headX = bodyX
        headY = bodyY
        won = false
        touching = false

        // visual state
        buildWallPath()
        buildPanel()
        particles.clear()
        lastFrame = 0L
        introT = 0f
        winT = 0f
        stepPulse = 0f
        bump = 0f
        strain = 0f
        touchAlpha = 0f
        hintAlpha = 0f
        hintCount = 0
        hintDirty = true
        lastProgress = SystemClock.uptimeMillis()
        lastBubbleAt = lastProgress
        drawHeadX = headX
        drawHeadY = headY

        notifyLevel()
        notifyNeck()
    }

    private fun generateMaze() {
        wall = Array(rows) { Array(cols) { BooleanArray(4) { true } } }

        val vis = Array(rows) { BooleanArray(cols) }
        val stack = ArrayDeque<IntArray>()
        stack.push(intArrayOf(0, 0))
        vis[0][0] = true
        while (stack.isNotEmpty()) {
            val cur = stack.peek()!!
            val options = IntArray(4)
            var n = 0
            for (d in 0..3) {
                val nc = cur[0] + DC[d]
                val nr = cur[1] + DR[d]
                if (nc in 0 until cols && nr in 0 until rows && !vis[nr][nc]) {
                    options[n++] = d
                }
            }
            if (n == 0) {
                stack.pop()
            } else {
                val d = options[Random.nextInt(n)]
                val nc = cur[0] + DC[d]
                val nr = cur[1] + DR[d]
                wall[cur[1]][cur[0]][d] = false
                wall[nr][nc][(d + 2) % 4] = false
                vis[nr][nc] = true
                stack.push(intArrayOf(nc, nr))
            }
        }
    }

    /** BFS: number of cells on the shortest route from start to fish. */
    private fun shortestPathCells(): Int {
        val dist = Array(rows) { IntArray(cols) { -1 } }
        val q = ArrayDeque<IntArray>()
        q.add(intArrayOf(0, 0))
        dist[0][0] = 1
        while (q.isNotEmpty()) {
            val cur = q.poll()!!
            if (cur[0] == fishC && cur[1] == fishR) return dist[cur[1]][cur[0]]
            for (d in 0..3) {
                if (wall[cur[1]][cur[0]][d]) continue
                val nc = cur[0] + DC[d]
                val nr = cur[1] + DR[d]
                if (dist[nr][nc] == -1) {
                    dist[nr][nc] = dist[cur[1]][cur[0]] + 1
                    q.add(intArrayOf(nc, nr))
                }
            }
        }
        return cols + rows
    }

    /** Pre-builds all wall segments once per level so drawing is a handful of path strokes. */
    private fun buildWallPath() {
        wallPath.reset()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = ox + c * cell
                val y = oy + r * cell
                if (wall[r][c][UP] && !(r == 0 && c == 0)) { // entrance for the duck
                    wallPath.moveTo(x, y)
                    wallPath.lineTo(x + cell, y)
                }
                if (wall[r][c][LEFT]) {
                    wallPath.moveTo(x, y)
                    wallPath.lineTo(x, y + cell)
                }
                if (r == rows - 1 && wall[r][c][DOWN]) {
                    wallPath.moveTo(x, y + cell)
                    wallPath.lineTo(x + cell, y + cell)
                }
                if (c == cols - 1 && wall[r][c][RIGHT]) {
                    wallPath.moveTo(x + cell, y)
                    wallPath.lineTo(x + cell, y + cell)
                }
            }
        }
    }

    private fun buildPanel() {
        val rad = cell * 0.14f
        panelPath.reset()
        rect.set(ox, oy, ox + cols * cell, oy + rows * cell)
        panelPath.addRoundRect(rect, rad, rad, Path.Direction.CW)
        panelShader = LinearGradient(
            0f, oy, 0f, oy + rows * cell,
            C_MAZE_TOP, C_MAZE_BOTTOM, Shader.TileMode.CLAMP
        )
    }

    // ----------------------------------------------------------------- helpers

    private fun cx(c: Int): Float = ox + (c + 0.5f) * cell
    private fun cy(r: Int): Float = oy + (r + 0.5f) * cell

    private fun angleOf(dir: Int): Float = when (dir) {
        UP -> -90f
        DOWN -> 90f
        LEFT -> 180f
        else -> 0f
    }

    private fun withAlpha(color: Int, a: Float): Int {
        val alpha = (((color ushr 24) and 0xFF) * a.coerceIn(0f, 1f)).toInt()
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return ca + ((cb - ca) * f).toInt()
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    private fun haptic(constant: Int) {
        performHapticFeedback(constant)
    }

    // ------------------------------------------------------------------ logic

    private fun update(now: Long, dt: Float) {
        introT = min(1f, introT + dt / INTRO_S)
        winT = if (won) (now - wonAt) / 1000f else 0f

        if (touching && !won && now - lastStep >= STEP_MS) {
            stepToward(touchX, touchY)
            lastStep = now
        }

        // head glides toward the last neck cell (frame-rate independent)
        val last = neck[neck.size - 1]
        val k = 1f - 0.65f.pow(dt * 60f)
        headX += (cx(last[0]) - headX) * k
        headY += (cy(last[1]) - headY) * k

        // effect decay
        stepPulse = max(0f, stepPulse - dt * 7f)
        bump = max(0f, bump - dt * 5f)
        strain = max(0f, strain - dt * 2.2f)
        val touchTarget = if (touching) 1f else 0f
        touchAlpha += (touchTarget - touchAlpha) * min(1f, dt * 14f)

        // hint
        val idle = !won && now - lastProgress > HINT_IDLE_MS
        hintAlpha += ((if (idle) 1f else 0f) - hintAlpha) * min(1f, dt * 4f)
        if (idle && hintDirty) rebuildHint()

        // duck idle bob + victory hop
        val bob = sin(now / 520.0).toFloat() * cell * 0.015f
        val jump = if (won && winT < 1.3f) {
            -abs(sin(winT * 9f)) * cell * 0.2f * (1f - winT / 1.3f)
        } else {
            0f
        }
        bodyOffY = bob + jump

        // head draw position (recoil on bump, tremble when the neck is maxed out)
        var hx = headX
        var hy = headY
        if (bump > 0f) {
            hx -= DC[bumpDir] * bump * cell * 0.07f
            hy -= DR[bumpDir] * bump * cell * 0.07f
        }
        if (strain > 0f) {
            hx += sin(now / 22.0).toFloat() * strain * cell * 0.03f
        }
        drawHeadX = hx
        drawHeadY = hy

        // little bubbles rising from the fish
        if (!won && now - lastBubbleAt > 850L) {
            lastBubbleAt = now
            val p = Particle(
                cx(fishC) - cell * 0.2f, cy(fishR) - cell * 0.12f,
                0f, -cell * 0.7f, 1.1f, cell * (0.035f + Random.nextFloat() * 0.025f),
                C_WHITE, P_BUBBLE
            )
            p.rot = Random.nextFloat() * 6.28f
            addParticle(p)
        }

        updateBackground(dt)
        updateParticles(dt)
    }

    private fun updateBackground(dt: Float) {
        for (i in 0 until BG_BUBBLES) {
            bubY[i] -= bubSp[i] * dt
            if (bubY[i] < -bubR[i] * 2f) {
                bubY[i] = viewH + bubR[i] * 2f
                bubX[i] = Random.nextFloat() * viewW
            }
        }
    }

    private fun updateParticles(dt: Float) {
        var i = particles.size - 1
        while (i >= 0) {
            val p = particles[i]
            p.life += dt
            if (p.life >= p.maxLife) {
                particles.removeAt(i)
            } else {
                p.x += p.vx * dt
                p.y += p.vy * dt
                when (p.kind) {
                    P_CONFETTI -> {
                        p.vy += cell * 16f * dt
                        p.vx *= (1f - 0.8f * dt)
                        p.rot += p.vr * dt
                    }
                    P_SPARKLE -> {
                        p.vx *= (1f - 2f * dt)
                        p.vy *= (1f - 2f * dt)
                    }
                    P_BUBBLE -> p.x += sin(p.life * 7f + p.rot) * cell * 0.25f * dt
                    else -> Unit
                }
            }
            i--
        }
    }

    private fun addParticle(p: Particle) {
        if (particles.size < MAX_PARTICLES) particles.add(p)
    }

    private fun rebuildHint() {
        hintDirty = false
        hintCount = 0
        val h = neck[neck.size - 1]
        val start = h[1] * cols + h[0]
        val parent = Array(rows) { IntArray(cols) { -1 } }
        val q = ArrayDeque<IntArray>()
        parent[h[1]][h[0]] = start
        q.add(intArrayOf(h[0], h[1]))
        var found = false
        while (q.isNotEmpty()) {
            val cur = q.poll()!!
            if (cur[0] == fishC && cur[1] == fishR) {
                found = true
                break
            }
            for (d in 0..3) {
                if (wall[cur[1]][cur[0]][d]) continue
                val nc = cur[0] + DC[d]
                val nr = cur[1] + DR[d]
                if (parent[nr][nc] == -1) {
                    parent[nr][nc] = cur[1] * cols + cur[0]
                    q.add(intArrayOf(nc, nr))
                }
            }
        }
        if (!found) return

        val route = ArrayList<Int>()
        var node = fishR * cols + fishC
        while (node != start) {
            route.add(node)
            node = parent[node / cols][node % cols]
        }
        route.reverse()
        hintCount = min(route.size, HINT_LEN)
        for (i in 0 until hintCount) {
            hintCells[i * 2] = route[i] % cols
            hintCells[i * 2 + 1] = route[i] / cols
        }
    }

    private fun stepToward(tx: Float, ty: Float) {
        val h = neck[neck.size - 1]
        val dx = tx - cx(h[0])
        val dy = ty - cy(h[1])
        val ax = abs(dx)
        val ay = abs(dy)
        val dead = cell * 0.25f
        if (ax < dead && ay < dead) return

        val dirX = if (dx > 0) RIGHT else LEFT
        val dirY = if (dy > 0) DOWN else UP
        val first: Int
        val second: Int
        if (ax >= ay) {
            first = dirX
            second = if (ay > dead) dirY else -1
        } else {
            first = dirY
            second = if (ax > dead) dirX else -1
        }
        val r1 = tryMove(h, first)
        if (r1 == MOVED) return
        var r2 = BLOCKED_WALL
        if (second >= 0) {
            r2 = tryMove(h, second)
            if (r2 == MOVED) return
        }
        // nothing moved: tell the player why
        val limit = r1 == BLOCKED_LIMIT || r2 == BLOCKED_LIMIT
        onBlocked(limit, first)
    }

    private fun tryMove(h: IntArray, d: Int): Int {
        if (wall[h[1]][h[0]][d]) return BLOCKED_WALL
        val nc = h[0] + DC[d]
        val nr = h[1] + DR[d]

        // moving back onto the previous cell = retract the neck
        if (neck.size >= 2) {
            val prev = neck[neck.size - 2]
            if (prev[0] == nc && prev[1] == nr) {
                neck.removeAt(neck.size - 1)
                lastDir = d
                onMoved()
                notifyNeck()
                return MOVED
            }
        }
        // neck can't stretch any further
        if (neck.size >= maxNeck) return BLOCKED_LIMIT

        neck.add(intArrayOf(nc, nr))
        lastDir = d
        onMoved()
        notifyNeck()
        if (nc == fishC && nr == fishR) {
            won = true
            touching = false
            celebrate()
            gameListener?.onWin(level)
        }
        return MOVED
    }

    private fun onMoved() {
        stepPulse = 1f
        lastProgress = SystemClock.uptimeMillis()
        hintDirty = true
    }

    private fun onBlocked(limit: Boolean, dir: Int) {
        val t = SystemClock.uptimeMillis()
        if (t - lastBumpFx < BUMP_FX_MS) return
        lastBumpFx = t
        if (limit) {
            strain = 1f
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        } else {
            bump = 1f
            bumpDir = dir
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            val p = Particle(
                headX + DC[dir] * cell * 0.42f, headY + DR[dir] * cell * 0.42f,
                0f, 0f, 0.35f, cell * 0.12f, C_WHITE, P_PUFF
            )
            addParticle(p)
        }
    }

    private fun celebrate() {
        wonAt = SystemClock.uptimeMillis()
        haptic(HapticFeedbackConstants.LONG_PRESS)
        val x = cx(fishC)
        val y = cy(fishR)
        for (i in 0 until 64) {
            val ang = Random.nextFloat() * 6.2832f
            val sp = cell * (2.5f + Random.nextFloat() * 6f)
            val p = Particle(
                x, y,
                cos(ang) * sp, sin(ang) * sp - cell * 3f,
                1.3f + Random.nextFloat() * 0.9f,
                cell * (0.05f + Random.nextFloat() * 0.05f),
                CONFETTI_COLORS[Random.nextInt(CONFETTI_COLORS.size)],
                P_CONFETTI
            )
            p.rot = Random.nextFloat() * 360f
            p.vr = (Random.nextFloat() - 0.5f) * 720f
            addParticle(p)
        }
        for (i in 0 until 10) {
            val ang = Random.nextFloat() * 6.2832f
            val sp = cell * (0.8f + Random.nextFloat() * 1.6f)
            addParticle(
                Particle(
                    x + cos(ang) * cell * 0.3f, y + sin(ang) * cell * 0.3f,
                    cos(ang) * sp, sin(ang) * sp,
                    0.7f, cell * (0.09f + Random.nextFloat() * 0.07f),
                    C_YELLOW_LIGHT, P_SPARKLE
                )
            )
        }
        addParticle(Particle(x, y, 0f, 0f, 0.55f, cell * 0.2f, C_WHITE, P_RING))
    }

    // ------------------------------------------------------------------- input

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (won) return true
                touching = true
                touchX = e.x
                touchY = e.y
                addParticle(Particle(e.x, e.y, 0f, 0f, 0.4f, cell * 0.14f, C_WHITE, P_RING))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = e.x
                touchY = e.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    // ----------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        if (wall.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        update(now, dt)

        drawBackground(canvas, now)

        // the whole play field pops in at the start of a level
        val s = 0.88f + 0.12f * easeOutBack(introT)
        canvas.save()
        canvas.scale(s, s, viewW / 2f, viewH / 2f)
        drawMaze(canvas, now)
        drawFish(canvas, now)
        drawNeck(canvas)
        canvas.save()
        canvas.translate(0f, bodyOffY)
        drawBody(canvas)
        canvas.restore()
        drawHead(canvas, now)
        canvas.restore()

        drawParticles(canvas)
        drawTouch(canvas, now)

        postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas, now: Long) {
        fill.shader = bgShader
        canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), fill)
        fill.shader = null

        for (i in 0 until BG_BUBBLES) {
            val r = bubR[i]
            val x = bubX[i] + sin(now / 900.0 + bubPh[i]).toFloat() * r * 0.8f
            val y = bubY[i]
            stroke.color = 0x38FFFFFF
            stroke.strokeWidth = max(1.5f, r * 0.12f)
            canvas.drawCircle(x, y, r, stroke)
            fill.color = 0x30FFFFFF
            canvas.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.18f, fill)
        }
    }

    private fun drawMaze(canvas: Canvas, now: Long) {
        val w = cols * cell
        val h = rows * cell
        val rad = cell * 0.14f

        // soft drop shadow under the board
        fill.color = C_SHADOW
        rect.set(ox, oy + cell * 0.07f, ox + w, oy + h + cell * 0.07f)
        canvas.drawRoundRect(rect, rad, rad, fill)

        // board
        rect.set(ox, oy, ox + w, oy + h)
        fill.shader = panelShader
        canvas.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null

        // floor details (clipped to the rounded board)
        canvas.save()
        canvas.clipPath(panelPath)
        fill.color = 0x12FFFFFF
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if ((r + c) % 2 == 0) {
                    val x = ox + c * cell
                    val y = oy + r * cell
                    canvas.drawRect(x, y, x + cell, y + cell, fill)
                }
            }
        }
        // start pad + goal tile
        fill.color = withAlpha(C_WHITE, 0.2f)
        canvas.drawRect(ox, oy, ox + cell, oy + cell, fill)
        if (!won) {
            fill.color = withAlpha(C_YELLOW, 0.24f)
            canvas.drawRect(ox + fishC * cell, oy + fishR * cell, ox + (fishC + 1) * cell, oy + (fishR + 1) * cell, fill)
        }
        canvas.restore()

        drawHint(canvas, now)

        // walls: shadow, dark edge, body, highlight
        canvas.save()
        canvas.translate(cell * 0.03f, cell * 0.05f)
        stroke.color = C_SHADOW
        stroke.strokeWidth = cell * 0.12f
        canvas.drawPath(wallPath, stroke)
        canvas.restore()

        stroke.color = C_WALL_DARK
        stroke.strokeWidth = cell * 0.13f
        canvas.drawPath(wallPath, stroke)
        stroke.color = C_WALL
        stroke.strokeWidth = cell * 0.09f
        canvas.drawPath(wallPath, stroke)
        stroke.color = 0x4DFFFFFF
        stroke.strokeWidth = cell * 0.025f
        canvas.drawPath(wallPath, stroke)
    }

    private fun drawHint(canvas: Canvas, now: Long) {
        if (hintAlpha < 0.02f || hintCount == 0) return
        for (i in 0 until hintCount) {
            val x = cx(hintCells[i * 2])
            val y = cy(hintCells[i * 2 + 1])
            val wave = 0.5f + 0.5f * sin(now / 260.0 - i * 0.9).toFloat()
            val fade = 1f - i * 0.12f
            fill.color = withAlpha(C_WHITE, hintAlpha * fade * (0.35f + 0.5f * wave))
            canvas.drawCircle(x, y, cell * (0.07f + 0.04f * wave), fill)
        }
    }

    private fun drawFish(canvas: Canvas, now: Long) {
        val scale = if (won) 1f - ((now - wonAt) / 280f).coerceIn(0f, 1f) else 1f
        if (scale <= 0f) return

        val x = cx(fishC)
        val y = cy(fishR) + sin(now / 250.0).toFloat() * cell * 0.04f
        val tail = sin(now / 120.0).toFloat() * 0.05f
        val pulse = 0.5f + 0.5f * sin(now / 400.0).toFloat()

        // glowing halo so the goal is easy to spot
        fill.color = withAlpha(C_YELLOW, 0.18f * scale)
        canvas.drawCircle(x, cy(fishR), cell * (0.34f + 0.05f * pulse), fill)
        fill.color = withAlpha(C_YELLOW, 0.12f * scale)
        canvas.drawCircle(x, cy(fishR), cell * (0.45f + 0.03f * pulse), fill)

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(sin(now / 300.0).toFloat() * 5f)
        canvas.scale(cell * scale, cell * scale)   // from here on, 1 unit = 1 cell

        // tail
        path.reset()
        path.moveTo(0.16f, 0f)
        path.lineTo(0.38f, -0.17f + tail)
        path.lineTo(0.38f, 0.17f + tail)
        path.close()
        stroke.color = C_OUTLINE
        stroke.strokeWidth = 0.05f
        canvas.drawPath(path, stroke)
        fill.color = C_ORANGE_DARK
        canvas.drawPath(path, fill)

        // top fin
        path.reset()
        path.moveTo(-0.06f, -0.15f)
        path.lineTo(0.02f, -0.30f)
        path.lineTo(0.12f, -0.13f)
        path.close()
        canvas.drawPath(path, stroke)
        canvas.drawPath(path, fill)

        // body
        rect.set(-0.28f, -0.17f, 0.22f, 0.17f)
        fill.color = C_ORANGE
        canvas.drawOval(rect, fill)
        stroke.strokeWidth = 0.045f
        canvas.drawOval(rect, stroke)

        // belly + gill
        rect.set(-0.20f, 0.0f, 0.15f, 0.13f)
        fill.color = C_BELLY
        canvas.drawOval(rect, fill)
        path.reset()
        path.moveTo(-0.08f, -0.08f)
        path.quadTo(-0.04f, 0f, -0.08f, 0.08f)
        stroke.color = withAlpha(C_OUTLINE, 0.55f)
        stroke.strokeWidth = 0.02f
        canvas.drawPath(path, stroke)

        // eye
        fill.color = C_WHITE
        canvas.drawCircle(-0.15f, -0.04f, 0.06f, fill)
        fill.color = C_OUTLINE
        canvas.drawCircle(-0.165f, -0.04f, 0.032f, fill)
        fill.color = C_WHITE
        canvas.drawCircle(-0.172f, -0.052f, 0.011f, fill)

        canvas.restore()
    }

    /** Neck path with softly rounded corners, from the duck's body to the head. */
    private fun buildNeckPath(hx: Float, hy: Float, startY: Float) {
        path.reset()
        path.moveTo(bodyX, startY)
        val n = neck.size
        for (i in 0 until n) {
            val x = if (i < n - 1) cx(neck[i][0]) else hx
            val y = if (i < n - 1) cy(neck[i][1]) else hy
            if (i == n - 1) {
                path.lineTo(x, y)
            } else {
                val nx = if (i + 1 < n - 1) cx(neck[i + 1][0]) else hx
                val ny = if (i + 1 < n - 1) cy(neck[i + 1][1]) else hy
                path.quadTo(x, y, (x + nx) / 2f, (y + ny) / 2f)
            }
        }
    }

    private fun drawNeck(canvas: Canvas) {
        buildNeckPath(drawHeadX, drawHeadY, bodyY + bodyOffY)

        // neck turns orange/red as it reaches its limit
        val ratio = if (maxNeck == 0) 0f else neck.size / maxNeck.toFloat()
        var color = lerpColor(C_YELLOW, C_STRAIN_ORANGE, (ratio - 0.75f) / 0.25f)
        if (strain > 0f) color = lerpColor(color, C_STRAIN_RED, strain)

        stroke.color = C_OUTLINE
        stroke.strokeWidth = cell * 0.46f
        canvas.drawPath(path, stroke)
        stroke.color = color
        stroke.strokeWidth = cell * 0.32f
        canvas.drawPath(path, stroke)

        // glossy highlight
        canvas.save()
        canvas.translate(-cell * 0.05f, -cell * 0.05f)
        stroke.color = withAlpha(C_WHITE, 0.35f)
        stroke.strokeWidth = cell * 0.07f
        canvas.drawPath(path, stroke)
        canvas.restore()
    }

    private fun drawBody(canvas: Canvas) {
        val w = cell * 0.82f
        val h = cell * 0.55f
        path.reset()
        path.moveTo(bodyX - w * 0.48f, bodyY - h * 0.38f)
        path.cubicTo(
            bodyX - w * 0.63f, bodyY - h * 0.58f,
            bodyX - w * 0.78f, bodyY - h * 0.50f,
            bodyX - w * 0.82f, bodyY - h * 0.34f
        )
        path.cubicTo(
            bodyX - w * 0.73f, bodyY - h * 0.22f,
            bodyX - w * 0.75f, bodyY - h * 0.13f,
            bodyX - w * 0.82f, bodyY - h * 0.03f
        )
        path.cubicTo(
            bodyX - w * 0.72f, bodyY + h * 0.04f,
            bodyX - w * 0.70f, bodyY + h * 0.16f,
            bodyX - w * 0.60f, bodyY + h * 0.34f
        )
        path.cubicTo(
            bodyX - w * 0.50f, bodyY + h * 0.70f,
            bodyX + w * 0.28f, bodyY + h * 0.72f,
            bodyX + w * 0.66f, bodyY + h * 0.36f
        )
        path.cubicTo(
            bodyX + w * 0.94f, bodyY + h * 0.10f,
            bodyX + w * 0.90f, bodyY - h * 0.28f,
            bodyX + w * 0.64f, bodyY - h * 0.47f
        )
        path.cubicTo(
            bodyX + w * 0.32f, bodyY - h * 0.76f,
            bodyX - w * 0.30f, bodyY - h * 0.72f,
            bodyX - w * 0.52f, bodyY - h * 0.56f
        )
        path.cubicTo(
            bodyX - w * 0.43f, bodyY - h * 0.52f,
            bodyX - w * 0.46f, bodyY - h * 0.46f,
            bodyX - w * 0.48f, bodyY - h * 0.38f
        )
        path.close()

        fill.color = C_OUTLINE
        canvas.drawPath(path, fill)
        canvas.save()
        canvas.scale(0.94f, 0.91f, bodyX, bodyY)
        fill.color = C_YELLOW
        canvas.drawPath(path, fill)
        canvas.restore()

        rect.set(
            bodyX - cell * 0.43f,
            bodyY - cell * 0.10f,
            bodyX + cell * 0.35f,
            bodyY + cell * 0.34f
        )
        fill.color = C_OUTLINE
        canvas.drawOval(rect, fill)
        rect.inset(cell * 0.035f, cell * 0.035f)
        fill.color = C_WING
        canvas.drawOval(rect, fill)

        path.reset()
        path.moveTo(bodyX - cell * 0.27f, bodyY + cell * 0.12f)
        path.cubicTo(
            bodyX - cell * 0.10f, bodyY + cell * 0.30f,
            bodyX + cell * 0.13f, bodyY + cell * 0.29f,
            bodyX + cell * 0.27f, bodyY + cell * 0.16f
        )
        stroke.color = C_YELLOW_DARK
        stroke.strokeWidth = cell * 0.035f
        canvas.drawPath(path, stroke)

        fill.color = C_YELLOW_LIGHT
        rect.set(
            bodyX - cell * 0.38f,
            bodyY - cell * 0.35f,
            bodyX - cell * 0.08f,
            bodyY - cell * 0.25f
        )
        canvas.drawOval(rect, fill)

        fill.color = C_YELLOW_DARK
        rect.set(
            bodyX + cell * 0.38f,
            bodyY + cell * 0.27f,
            bodyX + cell * 0.62f,
            bodyY + cell * 0.36f
        )
        canvas.drawOval(rect, fill)
    }

    private fun drawHead(canvas: Canvas, now: Long) {
        val r = cell * 0.30f * (1f + 0.07f * stepPulse)

        // beak opens as the duck nears the fish, and chomps on a win
        val fdx = cx(fishC) - headX
        val fdy = cy(fishR) - headY
        val dist = sqrt(fdx * fdx + fdy * fdy)
        var open = 0f
        if (won) {
            open = if (winT < 1.2f) 0.5f + 0.5f * sin(winT * 16f) else 0f
        } else if (dist < cell * 1.6f) {
            open = (1f - dist / (cell * 1.6f)) * 0.9f
        }

        canvas.save()
        canvas.translate(drawHeadX, drawHeadY)
        canvas.rotate(angleOf(lastDir))

        // beak
        if (open < 0.06f) {
            path.reset()
            path.moveTo(r * 0.6f, -r * 0.4f)
            path.lineTo(r * 1.6f, 0f)
            path.lineTo(r * 0.6f, r * 0.4f)
            path.close()
            fill.color = C_OUTLINE
            canvas.save()
            canvas.scale(1.15f, 1.15f)
            canvas.drawPath(path, fill)
            canvas.restore()
            fill.color = C_ORANGE
            canvas.drawPath(path, fill)
        } else {
            val gap = 0.05f + 0.30f * open
            // dark mouth
            path.reset()
            path.moveTo(r * 0.55f, -r * (gap + 0.08f))
            path.lineTo(r * 1.45f, 0f)
            path.lineTo(r * 0.55f, r * (gap + 0.08f))
            path.close()
            fill.color = C_OUTLINE
            canvas.drawPath(path, fill)
            // upper + lower beak
            path.reset()
            path.moveTo(r * 0.6f, -r * 0.4f)
            path.lineTo(r * 1.6f, -r * (0.04f + 0.30f * open))
            path.lineTo(r * 0.6f, -r * 0.02f)
            path.close()
            path.moveTo(r * 0.6f, r * 0.02f)
            path.lineTo(r * 1.55f, r * (0.04f + 0.30f * open))
            path.lineTo(r * 0.6f, r * 0.4f)
            path.close()
            stroke.color = C_OUTLINE
            stroke.strokeWidth = r * 0.12f
            canvas.drawPath(path, stroke)
            fill.color = C_ORANGE
            canvas.drawPath(path, fill)
        }

        // head
        fill.color = C_OUTLINE
        canvas.drawCircle(0f, 0f, r * 1.12f, fill)
        fill.color = C_YELLOW
        canvas.drawCircle(0f, 0f, r, fill)

        // cheek
        fill.color = C_CHEEK
        canvas.drawCircle(r * 0.18f, r * 0.42f, r * 0.2f, fill)

        // eye: happy on win, blinks now and then, pupil follows the finger
        if (won) {
            path.reset()
            path.moveTo(r * 0.02f, -r * 0.34f)
            path.quadTo(r * 0.29f, -r * 0.74f, r * 0.56f, -r * 0.34f)
            stroke.color = C_OUTLINE
            stroke.strokeWidth = r * 0.15f
            canvas.drawPath(path, stroke)
        } else if (now % 3400L < 120L) {
            stroke.color = C_OUTLINE
            stroke.strokeWidth = r * 0.13f
            canvas.drawLine(r * 0.04f, -r * 0.4f, r * 0.5f, -r * 0.4f, stroke)
        } else {
            var lookX = 0f
            var lookY = 0f
            if (touchAlpha > 0.1f) {
                val tdx = touchX - drawHeadX
                val tdy = touchY - drawHeadY
                val a = Math.toRadians(angleOf(lastDir).toDouble())
                val lx = (tdx * cos(a) + tdy * sin(a)).toFloat()
                val ly = (-tdx * sin(a) + tdy * cos(a)).toFloat()
                val len = sqrt(lx * lx + ly * ly)
                if (len > 1f) {
                    lookX = lx / len * r * 0.07f
                    lookY = ly / len * r * 0.07f
                }
            }
            fill.color = C_WHITE
            canvas.drawCircle(r * 0.25f, -r * 0.4f, r * 0.28f, fill)
            fill.color = C_OUTLINE
            canvas.drawCircle(r * 0.32f + lookX, -r * 0.4f + lookY, r * 0.14f, fill)
            fill.color = C_WHITE
            canvas.drawCircle(r * 0.36f + lookX, -r * 0.46f + lookY, r * 0.05f, fill)
        }
        canvas.restore()
    }

    private fun drawParticles(canvas: Canvas) {
        for (i in 0 until particles.size) {
            val p = particles[i]
            val t = p.life / p.maxLife
            val a = min(1f, (1f - t) * 2.2f)
            when (p.kind) {
                P_CONFETTI -> {
                    fill.color = withAlpha(p.color, a)
                    canvas.save()
                    canvas.translate(p.x, p.y)
                    canvas.rotate(p.rot)
                    rect.set(-p.size, -p.size * 0.55f, p.size, p.size * 0.55f)
                    canvas.drawRect(rect, fill)
                    canvas.restore()
                }
                P_SPARKLE -> {
                    fill.color = withAlpha(p.color, a)
                    drawStar(canvas, p.x, p.y, p.size * (1f - t))
                }
                P_PUFF -> {
                    fill.color = withAlpha(C_WHITE, a * 0.55f)
                    canvas.drawCircle(p.x, p.y, p.size * (1f + t * 1.4f), fill)
                }
                P_BUBBLE -> {
                    stroke.color = withAlpha(C_WHITE, a * 0.8f)
                    stroke.strokeWidth = max(1.5f, p.size * 0.25f)
                    canvas.drawCircle(p.x, p.y, p.size, stroke)
                }
                P_RING -> {
                    stroke.color = withAlpha(C_WHITE, a * 0.8f)
                    stroke.strokeWidth = cell * 0.04f
                    canvas.drawCircle(p.x, p.y, p.size * (1f + t * 1.8f), stroke)
                }
            }
        }
    }

    private fun drawStar(canvas: Canvas, x: Float, y: Float, s: Float) {
        if (s <= 0.5f) return
        path.reset()
        path.moveTo(x, y - s)
        path.quadTo(x, y, x + s, y)
        path.quadTo(x, y, x, y + s)
        path.quadTo(x, y, x - s, y)
        path.quadTo(x, y, x, y - s)
        path.close()
        canvas.drawPath(path, fill)
    }

    /** Soft ring under the finger so touch input feels responsive. */
    private fun drawTouch(canvas: Canvas, now: Long) {
        if (touchAlpha < 0.02f) return
        val r = cell * (0.34f + 0.04f * sin(now / 120.0).toFloat())
        stroke.color = withAlpha(C_WHITE, 0.55f * touchAlpha)
        stroke.strokeWidth = cell * 0.05f
        canvas.drawCircle(touchX, touchY, r, stroke)
        fill.color = withAlpha(C_WHITE, 0.25f * touchAlpha)
        canvas.drawCircle(touchX, touchY, r * 0.35f, fill)
    }
}