package com.john.nitro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    private enum class State { TITLE, COUNTDOWN, RACE, DONE }

    private val laps = 3
    private val track = Track(Tracks.first, 200f)
    private val cars = ArrayList<Car>()
    private lateinit var player: Car
    private val rnd = Random(System.nanoTime())

    private var state = State.TITLE
    private var countdown = 0f
    private var raceTime = 0f
    private var goFlash = 0f
    private var doneTimer = 0f
    private var finishCounter = 0
    private var time = 0f

    // Kamera
    private var camX = 0f
    private var camY = 0f
    private var zoom = 1f

    // Eingabe
    private val btnLeft = 0
    private val btnRight = 1
    private val btnNitro = 2
    private val btnGas = 3
    private val bx = FloatArray(4)
    private val by = FloatArray(4)
    private val br = FloatArray(4)
    private val pressed = BooleanArray(4)
    private val menuBtn = RectF()

    // Bildschirm
    private val dp = resources.displayMetrics.density
    private var safeL = 0f
    private var safeR = 0f
    private var lastNanos = 0L

    // Vorberechnete Streckengrafik (Weltkoordinaten), in Stücke geteilt
    private class Chunk(val box: RectF) {
        val asphalt = Path()
        val curbBase = Path()
        val curbRed = Path()
        val edges = Path()
    }
    private val chunks = ArrayList<Chunk>()
    private val startWhitePath = Path()
    private val startBlackPath = Path()
    private val miniPath = Path()
    private var miniScale = 1f
    private var miniX = 0f
    private var miniY = 0f
    private var miniW = 0f
    private var miniH = 0f

    private val treeX = ArrayList<Float>()
    private val treeY = ArrayList<Float>()
    private val treeR = ArrayList<Float>()

    // Farben und Pinsel
    private val grass = Color.rgb(38, 86, 44)
    private val grassLight = paint(Color.rgb(44, 96, 50))
    private val asphalt = paint(Color.rgb(56, 56, 62))
    private val curbWhite = paint(Color.rgb(236, 236, 236))
    private val curbRed = paint(Color.rgb(214, 48, 49))
    private val edgeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.rgb(132, 132, 138)
    }
    private val black = paint(Color.rgb(20, 20, 22))
    private val white = paint(Color.WHITE)
    private val shadow = paint(Color.argb(70, 0, 0, 0))
    private val treeDark = paint(Color.rgb(24, 64, 32))
    private val treeLight = paint(Color.rgb(36, 84, 44))
    private val body = paint(Color.WHITE)
    private val tyre = paint(Color.rgb(18, 18, 18))
    private val glass = paint(Color.argb(200, 20, 24, 32))
    private val flameOuter = paint(Color.rgb(255, 120, 30))
    private val flameInner = paint(Color.rgb(255, 228, 120))
    private val playerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val racingFont: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = racingFont; color = Color.WHITE
        setShadowLayer(4f, 0f, 2f, Color.argb(160, 0, 0, 0))
    }
    private val rect = RectF()
    private val tmpPath = Path()

    init {
        isFocusable = true
        buildTrackGraphics()
        buildTrees()
        reset()
    }

    private fun paint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }

    // ---------------------------------------------------------------- Aufbau

    private fun px(i: Int, off: Float) = track.xs[i] + track.nx[i] * off
    private fun py(i: Int, off: Float) = track.ys[i] + track.ny[i] * off

    private fun band(path: Path, i0: Int, count: Int, off: Float) {
        path.moveTo(px(i0, off), py(i0, off))
        for (k in 1..count) { val i = track.wrap(i0 + k); path.lineTo(px(i, off), py(i, off)) }
        for (k in count downTo 0) { val i = track.wrap(i0 + k); path.lineTo(px(i, -off), py(i, -off)) }
        path.close()
    }

    private fun quad(path: Path, i: Int, j: Int, o1: Float, o2: Float) {
        path.moveTo(px(i, o1), py(i, o1))
        path.lineTo(px(j, o1), py(j, o1))
        path.lineTo(px(j, o2), py(j, o2))
        path.lineTo(px(i, o2), py(i, o2))
        path.close()
    }

    private fun buildTrackGraphics() {
        val n = track.n
        val h = track.half
        val curb = 14f
        val size = 30
        var i0 = 0
        while (i0 < n) {
            val count = min(size, n - i0)   // ein Segment Überlappung zum nächsten Stück
            val box = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (k in 0..count) {
                val i = track.wrap(i0 + k)
                box.union(track.xs[i], track.ys[i])
            }
            box.inset(-(h + curb + 10f), -(h + curb + 10f))
            val c = Chunk(box)
            band(c.curbBase, i0, count, h + curb)
            band(c.asphalt, i0, count, h)
            for (k in 0 until count) {
                val i = track.wrap(i0 + k)
                if ((i / 2) % 2 == 0) {
                    val j = track.wrap(i + 1)
                    quad(c.curbRed, i, j, h, h + curb)
                    quad(c.curbRed, i, j, -h, -h - curb)
                }
            }
            for (side in floatArrayOf(1f, -1f)) {
                val off = (h - 10f) * side
                c.edges.moveTo(px(i0, off), py(i0, off))
                for (k in 1..count) { val i = track.wrap(i0 + k); c.edges.lineTo(px(i, off), py(i, off)) }
            }
            chunks.add(c)
            i0 += size
        }
        // Start-/Ziellinie: Schachbrett quer über die Fahrbahn
        val cols = 10
        val sq = track.width / cols
        val tx = track.ny[0]
        val ty = -track.nx[0]
        val nx = track.nx[0]
        val ny = track.ny[0]
        val cx = track.xs[0]
        val cy = track.ys[0]
        fun corner(path: Path, across: Float, along: Float, first: Boolean) {
            val x = cx + nx * (across - h) + tx * along
            val y = cy + ny * (across - h) + ty * along
            if (first) path.moveTo(x, y) else path.lineTo(x, y)
        }
        corner(startWhitePath, 0f, -sq, true)
        corner(startWhitePath, track.width, -sq, false)
        corner(startWhitePath, track.width, sq, false)
        corner(startWhitePath, 0f, sq, false)
        startWhitePath.close()
        for (r in 0 until 2) for (c in 0 until cols) {
            if ((r + c) % 2 != 0) continue
            val a = c * sq
            val l = (r - 1) * sq
            corner(startBlackPath, a, l, true)
            corner(startBlackPath, a + sq, l, false)
            corner(startBlackPath, a + sq, l + sq, false)
            corner(startBlackPath, a, l + sq, false)
            startBlackPath.close()
        }
    }

    private fun buildTrees() {
        val r = Random(7)
        val margin = 700f
        var tries = 0
        while (treeX.size < 320 && tries < 4000) {
            tries++
            val x = track.minX - margin + r.nextFloat() * (track.maxX - track.minX + 2 * margin)
            val y = track.minY - margin + r.nextFloat() * (track.maxY - track.minY + 2 * margin)
            val rad = 28f + r.nextFloat() * 30f
            val i = track.nearestAll(x, y)
            if (track.distTo(x, y, i) < track.half + 40f + rad) continue
            treeX.add(x); treeY.add(y); treeR.add(rad)
        }
    }

    private fun reset() {
        cars.clear()
        val grid = listOf(
            Triple(Car("Rot", Color.rgb(229, 72, 77), false, 0.97f), 10, 48f),
            Triple(Car("Blau", Color.rgb(62, 123, 250), false, 0.94f), 10, -48f),
            Triple(Car("Lila", Color.rgb(160, 108, 245), false, 0.91f), 24, 48f),
            Triple(Car("Du", Color.rgb(255, 210, 63), true, 1f), 24, -48f)
        )
        for ((car, back, off) in grid) {
            val i = track.wrap(-back)
            car.x = px(i, off)
            car.y = py(i, off)
            car.heading = track.heading(i)
            car.idx = i
            car.progress = -back.toFloat()
            car.offset = off * 0.6f
            car.offsetTarget = car.offset
            car.offsetTimer = 1f + rnd.nextFloat() * 2f
            cars.add(car)
            if (car.isPlayer) player = car
        }
        raceTime = 0f
        finishCounter = 0
        doneTimer = 0f
        goFlash = 0f
        camX = player.x
        camY = player.y
    }

    // ---------------------------------------------------------------- Spiellogik

    private fun startRace() {
        reset()
        state = State.COUNTDOWN
        countdown = 3f
    }

    private fun update(dt: Float) {
        time += dt
        when (state) {
            State.TITLE -> Unit
            State.COUNTDOWN -> {
                val before = countdown
                countdown -= dt
                if (floor(before) != floor(countdown)) buzz()
                if (countdown <= 0f) {
                    state = State.RACE
                    goFlash = 1f
                }
            }
            State.RACE, State.DONE -> {
                raceTime += dt
                goFlash -= dt
                for (c in cars) {
                    if (c.isPlayer && !c.finished) playerControl(c, dt) else ai(c, dt)
                    physics(c, dt)
                }
                collide()
                if (state == State.RACE && player.finished) {
                    state = State.DONE
                    doneTimer = 0f
                    buzz()
                }
                if (state == State.DONE) doneTimer += dt
            }
        }
        // Kamera folgt dem Spieler mit etwas Vorausschau
        val tx = player.x + player.vx * 0.3f
        val ty = player.y + player.vy * 0.3f
        val k = min(1f, dt * 5f)
        camX += (tx - camX) * k
        camY += (ty - camY) * k
        if (height > 0) {
            val base = height / 950f
            val target = base * (1f - 0.14f * min(1f, player.speed / 900f))
            zoom += (target - zoom) * min(1f, dt * 2f)
        }
    }

    private fun playerControl(c: Car, dt: Float) {
        val target = (if (pressed[btnRight]) 1f else 0f) - (if (pressed[btnLeft]) 1f else 0f)
        c.steer += (target - c.steer) * min(1f, dt * 14f)
        c.throttle = pressed[btnGas]
        c.wantNitro = pressed[btnNitro]
        c.skillNow = 1f
    }

    private fun ai(c: Car, dt: Float) {
        c.offsetTimer -= dt
        if (c.offsetTimer <= 0f) {
            c.offsetTarget = rnd.nextFloat() * 110f - 55f
            c.offsetTimer = 2f + rnd.nextFloat() * 3f
        }
        c.offset += (c.offsetTarget - c.offset) * min(1f, dt * 0.8f)

        val sp = c.speed
        val look = 8 + (sp / 600f * 16f).toInt()
        val ti = track.wrap(c.idx + look)
        val tx = px(ti, c.offset)
        val ty = py(ti, c.offset)
        val diff = angleDiff(atan2(ty - c.y, tx - c.x), c.heading)
        c.steer = (diff * 3f).coerceIn(-1f, 1f)

        // Kurven voraus erkennen und rechtzeitig vom Gas gehen
        val a0 = track.heading(c.idx)
        val bend = abs(angleDiff(track.heading(track.wrap(c.idx + 28)), a0))
        val bendFar = abs(angleDiff(track.heading(track.wrap(c.idx + 55)), a0))
        val limit = when {
            bend > 1.6f -> 330f
            bend > 1.0f -> 430f
            bend > 0.6f -> 520f
            else -> 9999f
        } * c.skill
        c.throttle = (sp < limit && abs(diff) < 0.7f) || sp < 200f

        // Gummiband: Computer fährt etwas langsamer, wenn er weit vorne liegt
        c.skillNow = if (!c.isPlayer && !player.finished) {
            c.skill * (1f + ((player.progress - c.progress) / track.n * 0.2f).coerceIn(-0.07f, 0.07f))
        } else c.skill

        c.wantNitro = !c.isPlayer && c.nitro > 0f && bend < 0.25f && bendFar < 0.35f &&
            abs(diff) < 0.12f && sp > 380f
    }

    private fun physics(c: Car, dt: Float) {
        // Position auf der Strecke
        var ni = track.nearest(c.x, c.y, c.idx)
        if (track.distTo(c.x, c.y, ni) > track.half + 300f) ni = track.nearestAll(c.x, c.y)
        var d = ni - c.idx
        if (d > track.n / 2) d -= track.n
        if (d < -track.n / 2) d += track.n
        // Abkürzungen übers Gras zählen nicht
        if (abs(d) <= 60) c.progress += d
        c.idx = ni
        c.onTrack = track.distTo(c.x, c.y, ni) <= track.half + 10f

        val nitroOn = c.wantNitro && c.nitro > 0f
        c.nitroActive = nitroOn
        if (nitroOn) c.nitro = max(0f, c.nitro - 34f * dt)
        val throttle = c.throttle || nitroOn

        var maxV = (if (nitroOn) 900f else 600f) * c.skillNow
        if (!c.onTrack) maxV *= 0.42f
        val accel = if (nitroOn) 1100f else 470f

        val fx = cos(c.heading)
        val fy = sin(c.heading)
        if (throttle) {
            c.vx += fx * accel * dt
            c.vy += fy * accel * dt
        }
        val cur = hypot(c.vx, c.vy)
        var sp = cur
        if (!throttle) sp = max(0f, sp - 280f * dt)
        if (sp > maxV) sp = max(maxV, sp - (if (c.onTrack) 500f else 1300f) * dt)
        if (cur > 0.001f) {
            c.vx = c.vx / cur * sp
            c.vy = c.vy / cur * sp
        }

        // Lenken (im Stand dreht sich nichts)
        val fwd = c.vx * fx + c.vy * fy
        val turnScale = min(1f, abs(fwd) / 120f)
        c.heading += c.steer * 2.8f * turnScale * sign(fwd) * dt
        if (c.heading > PI) c.heading -= (2 * PI).toFloat()
        if (c.heading < -PI) c.heading += (2 * PI).toFloat()

        // Haftung: Geschwindigkeit dreht in Fahrtrichtung, mit etwas Rutschen
        val gx = cos(c.heading)
        val gy = sin(c.heading)
        val dir = if (fwd >= 0f) 1f else -1f
        val grip = min(1f, (if (c.onTrack) 7.5f else 4.5f) * (if (nitroOn) 0.75f else 1f) * dt)
        c.vx += (gx * sp * dir - c.vx) * grip
        c.vy += (gy * sp * dir - c.vy) * grip

        c.x += c.vx * dt
        c.y += c.vy * dt

        // Runden und Ziel
        val ld = floor(c.progress / track.n).toInt()
        if (ld > c.lapsDone) {
            c.lapsDone = ld
            if (ld in 1 until laps) {
                c.nitro = min(100f, c.nitro + 35f)
                if (c.isPlayer) buzz()
            }
        }
        if (!c.finished && c.progress >= laps * track.n) {
            c.finished = true
            c.finishTime = raceTime
            c.finishOrder = ++finishCounter
        }
    }

    private fun collide() {
        val r = 21f
        for (a in 0 until cars.size) for (b in a + 1 until cars.size) {
            val c1 = cars[a]
            val c2 = cars[b]
            val dx = c2.x - c1.x
            val dy = c2.y - c1.y
            val d = sqrt(dx * dx + dy * dy)
            if (d >= 2 * r || d < 0.001f) continue
            val nx = dx / d
            val ny = dy / d
            val push = (2 * r - d) / 2f
            c1.x -= nx * push; c1.y -= ny * push
            c2.x += nx * push; c2.y += ny * push
            val vn = (c1.vx - c2.vx) * nx + (c1.vy - c2.vy) * ny
            if (vn > 0f) {
                val j = vn * 0.75f
                c1.vx -= j * nx; c1.vy -= j * ny
                c2.vx += j * nx; c2.vy += j * ny
                if (c1.isPlayer || c2.isPlayer) buzz()
            }
        }
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return d
    }

    private var lastBuzz = 0f
    private fun buzz() {
        if (time - lastBuzz < 0.15f) return
        lastBuzz = time
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // ---------------------------------------------------------------- Ranking

    private fun standings(): List<Car> = cars.sortedWith(
        compareBy<Car>({ if (it.finished) 0 else 1 }, { if (it.finished) it.finishOrder.toFloat() else -it.progress })
    )

    private fun fmtTime(t: Float): String {
        val tenths = (t * 10f).toInt()
        val m = tenths / 600
        val s = (tenths / 10) % 60
        val z = tenths % 10
        return "$m:${s.toString().padStart(2, '0')},$z"
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val c = insets.displayCutout
        safeL = (c?.safeInsetLeft ?: 0).toFloat()
        safeR = (c?.safeInsetRight ?: 0).toFloat()
        layoutButtons(width, height)
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        zoom = h / 950f
        layoutButtons(w, h)
        // Minikarte oben rechts
        miniH = h * 0.26f
        val tw = track.maxX - track.minX
        val th = track.maxY - track.minY
        miniScale = miniH / th
        miniW = tw * miniScale
        miniX = w - safeR - 16 * dp - miniW
        miniY = 16 * dp
        miniPath.reset()
        for (i in 0 until track.n) {
            val x = miniX + (track.xs[i] - track.minX) * miniScale
            val y = miniY + (track.ys[i] - track.minY) * miniScale
            if (i == 0) miniPath.moveTo(x, y) else miniPath.lineTo(x, y)
        }
        miniPath.close()
    }

    private fun layoutButtons(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val r = h * 0.11f
        val pad = 18 * dp
        val left = safeL + pad
        val right = w - safeR - pad
        bx[btnLeft] = left + r; by[btnLeft] = h - pad - r; br[btnLeft] = r
        bx[btnRight] = left + r * 3.4f; by[btnRight] = h - pad - r; br[btnRight] = r
        bx[btnGas] = right - r * 1.2f; by[btnGas] = h - pad - r * 1.2f; br[btnGas] = r * 1.2f
        bx[btnNitro] = right - r * 3.9f; by[btnNitro] = h - pad - r * 0.9f; br[btnNitro] = r * 0.9f
        miniX = w - safeR - 16 * dp - miniW
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        var dt = if (lastNanos == 0L) 0f else (now - lastNanos) / 1e9f
        lastNanos = now
        dt = min(dt, 0.05f)
        var rem = dt
        while (rem > 0f) {
            val s = min(rem, 1f / 120f)
            update(s)
            rem -= s
        }
        drawWorld(canvas)
        drawHud(canvas)
        postInvalidateOnAnimation()
    }

    private fun drawWorld(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(grass)
        canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.scale(zoom, zoom)
        canvas.translate(-camX, -camY)
        val hw = w / 2f / zoom
        val hh = h / 2f / zoom
        val vl = camX - hw
        val vr = camX + hw
        val vt = camY - hh
        val vb = camY + hh

        // gemähte Rasenstreifen
        val band = 180f
        var gx = floor(vl / band) * band
        while (gx < vr) {
            if ((floor(gx / band).toInt() and 1) == 0) canvas.drawRect(gx, vt, gx + band, vb, grassLight)
            gx += band
        }

        val visible = chunks.filter { it.box.intersects(vl, vt, vr, vb) }
        for (c in visible) canvas.drawPath(c.curbBase, curbWhite)
        for (c in visible) canvas.drawPath(c.curbRed, curbRed)
        for (c in visible) canvas.drawPath(c.asphalt, asphalt)
        for (c in visible) canvas.drawPath(c.edges, edgeLine)
        canvas.drawPath(startWhitePath, white)
        canvas.drawPath(startBlackPath, black)

        // Bäume
        for (i in treeX.indices) {
            val x = treeX[i]
            val y = treeY[i]
            val r = treeR[i]
            if (x + r < vl || x - r > vr || y + r < vt || y - r > vb) continue
            canvas.drawCircle(x + 10f, y + 14f, r, shadow)
            canvas.drawCircle(x, y, r, treeDark)
            canvas.drawCircle(x - r * 0.22f, y - r * 0.22f, r * 0.62f, treeLight)
        }

        // Markierung unter dem eigenen Wagen vor dem Start
        if (state == State.TITLE || state == State.COUNTDOWN) {
            val pulse = 0.5f + 0.5f * sin(time * 6f)
            playerRing.strokeWidth = 4f
            playerRing.alpha = (120 + 120 * pulse).toInt()
            canvas.drawCircle(player.x, player.y, 40f + 6f * pulse, playerRing)
        }

        for (c in cars) drawCar(canvas, c)
        canvas.restore()
    }

    private fun drawCar(canvas: Canvas, c: Car) {
        val deg = Math.toDegrees(c.heading.toDouble()).toFloat()
        canvas.save()
        canvas.translate(c.x + 5f, c.y + 7f)
        canvas.rotate(deg)
        rect.set(-23f, -13f, 23f, 13f)
        canvas.drawRoundRect(rect, 7f, 7f, shadow)
        canvas.restore()

        canvas.save()
        canvas.translate(c.x, c.y)
        canvas.rotate(deg)

        // Nitro-Flamme
        if (c.nitroActive) {
            val l = 22f + 10f * abs(sin(time * 40f + c.x))
            tmpPath.reset()
            tmpPath.moveTo(-21f, -7f); tmpPath.lineTo(-21f - l, 0f); tmpPath.lineTo(-21f, 7f); tmpPath.close()
            canvas.drawPath(tmpPath, flameOuter)
            tmpPath.reset()
            tmpPath.moveTo(-21f, -4f); tmpPath.lineTo(-21f - l * 0.6f, 0f); tmpPath.lineTo(-21f, 4f); tmpPath.close()
            canvas.drawPath(tmpPath, flameInner)
        }

        // Räder
        for (sx in floatArrayOf(-15f, 10f)) for (sy in floatArrayOf(-15f, 10f)) {
            rect.set(sx, sy, sx + 9f, sy + 5f)
            canvas.drawRoundRect(rect, 2f, 2f, tyre)
        }
        // Karosserie
        body.color = c.color
        rect.set(-22f, -10f, 22f, 10f)
        canvas.drawRoundRect(rect, 7f, 7f, body)
        // Frontflügel und Heckflügel
        rect.set(18f, -13f, 24f, 13f)
        canvas.drawRoundRect(rect, 2f, 2f, body)
        rect.set(-24f, -12f, -19f, 12f)
        canvas.drawRoundRect(rect, 2f, 2f, tyre)
        // Cockpit
        rect.set(-6f, -6f, 8f, 6f)
        canvas.drawRoundRect(rect, 5f, 5f, glass)
        canvas.restore()
    }

    private fun drawHud(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 18 * dp
        val left = safeL + pad

        if (state != State.TITLE) {
            // Runde und Platz
            val lap = (player.lapsDone + 1).coerceIn(1, laps)
            val place = standings().indexOf(player) + 1
            text.textAlign = Paint.Align.LEFT
            text.textSize = 14 * dp
            text.color = Color.argb(190, 255, 255, 255)
            canvas.drawText("RUNDE", left, pad + 14 * dp, text)
            canvas.drawText("PLATZ", left + 96 * dp, pad + 14 * dp, text)
            text.textSize = 34 * dp
            text.color = Color.WHITE
            canvas.drawText("$lap/$laps", left, pad + 48 * dp, text)
            canvas.drawText("$place/${cars.size}", left + 96 * dp, pad + 48 * dp, text)

            // Zeit und Tempo oben in der Mitte
            text.textAlign = Paint.Align.CENTER
            text.textSize = 26 * dp
            val t = if (player.finished) player.finishTime else raceTime
            canvas.drawText(fmtTime(t), w / 2f, pad + 30 * dp, text)
            text.textSize = 16 * dp
            text.color = Color.argb(200, 255, 255, 255)
            canvas.drawText("${(player.speed * 0.36f).toInt()} km/h", w / 2f, pad + 52 * dp, text)
        }

        drawMinimap(canvas)

        if (state == State.COUNTDOWN || state == State.RACE) drawButtons(canvas)

        when (state) {
            State.TITLE -> drawTitle(canvas, w, h)
            State.COUNTDOWN -> {
                val num = countdown.toInt() + 1
                val frac = countdown - floor(countdown)
                text.textAlign = Paint.Align.CENTER
                text.textSize = (90 + 50 * frac) * dp
                text.color = Color.WHITE
                canvas.drawText("$num", w / 2f, h / 2f + text.textSize * 0.35f, text)
            }
            State.RACE -> if (goFlash > 0f) {
                text.textAlign = Paint.Align.CENTER
                text.textSize = 96 * dp
                text.color = Color.argb((255 * min(1f, goFlash * 2f)).toInt(), 255, 210, 63)
                canvas.drawText("LOS!", w / 2f, h / 2f + 34 * dp, text)
            }
            State.DONE -> drawResults(canvas, w, h)
        }
    }

    private fun drawMinimap(canvas: Canvas) {
        outline.strokeWidth = 6 * dp
        outline.color = Color.argb(120, 0, 0, 0)
        canvas.drawPath(miniPath, outline)
        outline.strokeWidth = 3 * dp
        outline.color = Color.argb(200, 230, 230, 230)
        canvas.drawPath(miniPath, outline)
        for (c in cars) {
            fill.color = c.color
            val x = miniX + (c.x - track.minX) * miniScale
            val y = miniY + (c.y - track.minY) * miniScale
            canvas.drawCircle(x, y, (if (c.isPlayer) 5f else 4f) * dp, fill)
        }
    }

    private fun drawButtons(canvas: Canvas) {
        for (b in 0 until 4) {
            fill.color = if (pressed[b]) Color.argb(90, 255, 255, 255) else Color.argb(70, 0, 0, 0)
            canvas.drawCircle(bx[b], by[b], br[b], fill)
            outline.strokeWidth = 3 * dp
            outline.color = Color.argb(if (pressed[b]) 230 else 160, 255, 255, 255)
            canvas.drawCircle(bx[b], by[b], br[b], outline)
        }
        // Pfeile als Outlines
        outline.strokeWidth = 4 * dp
        outline.color = Color.WHITE
        for ((b, s) in listOf(btnLeft to -1f, btnRight to 1f)) {
            val a = br[b] * 0.32f
            tmpPath.reset()
            tmpPath.moveTo(bx[b] - a * 0.6f * s, by[b] - a)
            tmpPath.lineTo(bx[b] + a * 0.8f * s, by[b])
            tmpPath.lineTo(bx[b] - a * 0.6f * s, by[b] + a)
            canvas.drawPath(tmpPath, outline)
        }
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = br[btnGas] * 0.38f
        canvas.drawText("GAS", bx[btnGas], by[btnGas] + text.textSize * 0.36f, text)
        text.textSize = br[btnNitro] * 0.34f
        text.color = if (player.nitro > 0f) Color.WHITE else Color.argb(110, 255, 255, 255)
        canvas.drawText("NITRO", bx[btnNitro], by[btnNitro] + text.textSize * 0.36f, text)

        // Nitro-Vorrat als Ring um den Knopf
        val r = br[btnNitro] + 7 * dp
        rect.set(bx[btnNitro] - r, by[btnNitro] - r, bx[btnNitro] + r, by[btnNitro] + r)
        outline.strokeWidth = 5 * dp
        outline.color = Color.argb(80, 255, 255, 255)
        canvas.drawArc(rect, -90f, 360f, false, outline)
        outline.color = Color.rgb(255, 140, 40)
        canvas.drawArc(rect, -90f, 360f * player.nitro / 100f, false, outline)
    }

    private fun drawTitle(canvas: Canvas, w: Float, h: Float) {
        canvas.drawColor(Color.argb(150, 8, 8, 10))
        text.textAlign = Paint.Align.CENTER
        text.textSize = 96 * dp
        text.color = Color.rgb(255, 210, 63)
        canvas.drawText("NITRO", w / 2f, h * 0.38f, text)
        text.textSize = 18 * dp
        text.color = Color.argb(220, 255, 255, 255)
        canvas.drawText("3 Runden · 4 Wagen · du startest von hinten", w / 2f, h * 0.38f + 34 * dp, text)

        val bw = 200 * dp
        val bh = 58 * dp
        menuBtn.set(w / 2f - bw / 2f, h * 0.58f, w / 2f + bw / 2f, h * 0.58f + bh)
        fill.color = Color.argb(60, 255, 255, 255)
        canvas.drawRoundRect(menuBtn, bh / 2f, bh / 2f, fill)
        outline.strokeWidth = 3 * dp
        outline.color = Color.WHITE
        canvas.drawRoundRect(menuBtn, bh / 2f, bh / 2f, outline)
        text.textSize = 26 * dp
        text.color = Color.WHITE
        canvas.drawText("Start", w / 2f, menuBtn.centerY() + 9 * dp, text)

        text.textSize = 14 * dp
        text.color = Color.argb(170, 255, 255, 255)
        canvas.drawText("Links lenken  ·  rechts Gas und Nitro", w / 2f, h - 28 * dp, text)
    }

    private fun drawResults(canvas: Canvas, w: Float, h: Float) {
        if (doneTimer < 1.2f) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 80 * dp
            text.color = Color.rgb(255, 210, 63)
            canvas.drawText("ZIEL!", w / 2f, h / 2f + 28 * dp, text)
            return
        }
        canvas.drawColor(Color.argb(170, 8, 8, 10))
        val list = standings()
        val place = player.finishOrder
        text.textAlign = Paint.Align.CENTER
        text.textSize = 40 * dp
        text.color = Color.rgb(255, 210, 63)
        val title = if (place == 1) "Platz 1 – Sieg!" else "Platz $place"
        canvas.drawText(title, w / 2f, h * 0.2f, text)

        val rowH = 34 * dp
        val top = h * 0.2f + 30 * dp
        val colL = w / 2f - 150 * dp
        val colR = w / 2f + 150 * dp
        for ((i, c) in list.withIndex()) {
            val y = top + rowH * (i + 1)
            fill.color = c.color
            canvas.drawCircle(colL + 46 * dp, y - 8 * dp, 7 * dp, fill)
            text.textSize = 22 * dp
            text.color = if (c.isPlayer) Color.rgb(255, 210, 63) else Color.WHITE
            text.textAlign = Paint.Align.LEFT
            canvas.drawText("${i + 1}.", colL, y, text)
            canvas.drawText(c.name, colL + 64 * dp, y, text)
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(if (c.finished) fmtTime(c.finishTime) else "–", colR, y, text)
        }

        val bw = 200 * dp
        val bh = 58 * dp
        val btnTop = min(h - bh - 20 * dp, top + rowH * (list.size + 1))
        menuBtn.set(w / 2f - bw / 2f, btnTop, w / 2f + bw / 2f, btnTop + bh)
        fill.color = Color.argb(60, 255, 255, 255)
        canvas.drawRoundRect(menuBtn, bh / 2f, bh / 2f, fill)
        outline.strokeWidth = 3 * dp
        outline.color = Color.WHITE
        canvas.drawRoundRect(menuBtn, bh / 2f, bh / 2f, outline)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 26 * dp
        text.color = Color.WHITE
        canvas.drawText("Nochmal", w / 2f, menuBtn.centerY() + 9 * dp, text)
    }

    // ---------------------------------------------------------------- Touch

    private fun hit(x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (b in 0 until 4) {
            val d = hypot(x - bx[b], y - by[b])
            if (d < br[b] * 1.4f && d / br[b] < bestD) {
                bestD = d / br[b]
                best = b
            }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            val x = e.getX(i)
            val y = e.getY(i)
            val inMenu = menuBtn.contains(x, y)
            if (state == State.TITLE && inMenu) startRace()
            else if (state == State.DONE && doneTimer >= 1.2f && inMenu) startRace()
        }
        pressed.fill(false)
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until e.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                val b = hit(e.getX(i), e.getY(i))
                if (b >= 0) pressed[b] = true
            }
        }
        return true
    }
}
