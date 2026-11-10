package com.geethdev.ducky

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ducky - guide the duck's stretchy neck through the maze to reach the fish.
 * This view only draws the game world. HUD / buttons / win screen live in XML
 * (activity_main.xml) and are driven through [GameListener].
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

        // colors
        private val C_MAZE_BG = 0xFF9EAEFA.toInt()
        private val C_WALL = 0xFF2B3A9E.toInt()
        private val C_YELLOW = 0xFFFFD43B.toInt()
        private val C_YELLOW_DARK = 0xFFF2B705.toInt()
        private val C_OUTLINE = 0xFF1B1B2F.toInt()
        private val C_ORANGE = 0xFFFF9F1C.toInt()
        private val C_WHITE = 0xFFFFFFFF.toInt()
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
        if (w > 0 && h > 0) newLevel()
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

    // ----------------------------------------------------------------- helpers

    private fun cx(c: Int): Float = ox + (c + 0.5f) * cell
    private fun cy(r: Int): Float = oy + (r + 0.5f) * cell

    private fun angleOf(dir: Int): Float = when (dir) {
        UP -> -90f
        DOWN -> 90f
        LEFT -> 180f
        else -> 0f
    }

    // ------------------------------------------------------------------ logic

    private fun update(now: Long) {
        if (touching && !won && now - lastStep >= STEP_MS) {
            stepToward(touchX, touchY)
            lastStep = now
        }
        val last = neck[neck.size - 1]
        headX += (cx(last[0]) - headX) * 0.35f
        headY += (cy(last[1]) - headY) * 0.35f
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
        if (tryMove(h, first)) return
        if (second >= 0) tryMove(h, second)
    }

    private fun tryMove(h: IntArray, d: Int): Boolean {
        if (wall[h[1]][h[0]][d]) return false
        val nc = h[0] + DC[d]
        val nr = h[1] + DR[d]

        // moving back onto the previous cell = retract the neck
        if (neck.size >= 2) {
            val prev = neck[neck.size - 2]
            if (prev[0] == nc && prev[1] == nr) {
                neck.removeAt(neck.size - 1)
                lastDir = d
                notifyNeck()
                return true
            }
        }
        // neck can't stretch any further
        if (neck.size >= maxNeck) return false

        neck.add(intArrayOf(nc, nr))
        lastDir = d
        notifyNeck()
        if (nc == fishC && nr == fishR) {
            won = true
            touching = false
            gameListener?.onWin(level)
        }
        return true
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
        update(now)

        drawMaze(canvas)
        drawFish(canvas, now)
        drawNeck(canvas)
        drawBody(canvas)
        drawHead(canvas)

        postInvalidateOnAnimation()
    }

    private fun drawMaze(canvas: Canvas) {
        fill.color = C_MAZE_BG
        canvas.drawRect(ox, oy, ox + cols * cell, oy + rows * cell, fill)

        stroke.color = C_WALL
        stroke.strokeWidth = cell * 0.09f
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = ox + c * cell
                val y = oy + r * cell
                if (wall[r][c][UP] && !(r == 0 && c == 0)) // entrance for the duck
                    canvas.drawLine(x, y, x + cell, y, stroke)
                if (wall[r][c][LEFT])
                    canvas.drawLine(x, y, x, y + cell, stroke)
                if (r == rows - 1 && wall[r][c][DOWN])
                    canvas.drawLine(x, y + cell, x + cell, y + cell, stroke)
                if (c == cols - 1 && wall[r][c][RIGHT])
                    canvas.drawLine(x + cell, y, x + cell, y + cell, stroke)
            }
        }
    }

    private fun drawFish(canvas: Canvas, now: Long) {
        if (won) return
        val x = cx(fishC)
        val y = cy(fishR) + sin(now / 250.0).toFloat() * cell * 0.04f
        val tail = sin(now / 120.0).toFloat() * cell * 0.05f

        fill.color = C_ORANGE
        rect.set(x - cell * 0.28f, y - cell * 0.17f, x + cell * 0.22f, y + cell * 0.17f)
        canvas.drawOval(rect, fill)

        path.reset()
        path.moveTo(x + cell * 0.18f, y)
        path.lineTo(x + cell * 0.38f, y - cell * 0.16f + tail)
        path.lineTo(x + cell * 0.38f, y + cell * 0.16f + tail)
        path.close()
        canvas.drawPath(path, fill)

        fill.color = C_WHITE
        canvas.drawCircle(x - cell * 0.14f, y - cell * 0.04f, cell * 0.05f, fill)
        fill.color = C_OUTLINE
        canvas.drawCircle(x - cell * 0.15f, y - cell * 0.04f, cell * 0.025f, fill)
    }

    private fun drawNeck(canvas: Canvas) {
        path.reset()
        path.moveTo(bodyX, bodyY)
        for (i in 0 until neck.size - 1) {
            val c = neck[i]
            path.lineTo(cx(c[0]), cy(c[1]))
        }
        path.lineTo(headX, headY)

        stroke.color = C_OUTLINE
        stroke.strokeWidth = cell * 0.46f
        canvas.drawPath(path, stroke)
        stroke.color = C_YELLOW
        stroke.strokeWidth = cell * 0.32f
        canvas.drawPath(path, stroke)
    }

    private fun drawBody(canvas: Canvas) {
        val r = cell * 0.55f
        // tail
        path.reset()
        path.moveTo(bodyX - r * 0.7f, bodyY)
        path.lineTo(bodyX - r * 1.5f, bodyY - r * 0.5f)
        path.lineTo(bodyX - r * 0.6f, bodyY + r * 0.5f)
        path.close()
        fill.color = C_YELLOW
        canvas.drawPath(path, fill)

        fill.color = C_OUTLINE
        canvas.drawCircle(bodyX, bodyY, r + cell * 0.04f, fill)
        fill.color = C_YELLOW
        canvas.drawCircle(bodyX, bodyY, r, fill)
        fill.color = C_YELLOW_DARK
        rect.set(bodyX - r * 0.75f, bodyY - r * 0.1f, bodyX + r * 0.1f, bodyY + r * 0.6f)
        canvas.drawOval(rect, fill)
    }

    private fun drawHead(canvas: Canvas) {
        val r = cell * 0.30f
        canvas.save()
        canvas.translate(headX, headY)
        canvas.rotate(angleOf(lastDir))

        // beak
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

        // head
        fill.color = C_OUTLINE
        canvas.drawCircle(0f, 0f, r * 1.12f, fill)
        fill.color = C_YELLOW
        canvas.drawCircle(0f, 0f, r, fill)

        // eye
        fill.color = C_WHITE
        canvas.drawCircle(r * 0.25f, -r * 0.4f, r * 0.28f, fill)
        fill.color = C_OUTLINE
        canvas.drawCircle(r * 0.32f, -r * 0.4f, r * 0.14f, fill)
        canvas.restore()
    }
}