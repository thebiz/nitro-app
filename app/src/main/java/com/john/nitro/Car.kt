package com.john.nitro

import kotlin.math.hypot

class Car(
    val name: String,
    val color: Int,
    val isPlayer: Boolean,
    val skill: Float
) {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var heading = 0f

    /** Index des nächsten Streckenpunkts und zurückgelegte Punkte seit der Startlinie. */
    var idx = 0
    var progress = 0f
    var lapsDone = -1

    var nitro = 100f
    var nitroActive = false
    var onTrack = true

    // Steuerung (vom Spieler oder vom Computer gesetzt)
    var steer = 0f
    var throttle = false
    var wantNitro = false
    var skillNow = 1f

    // Computer-Fahrer
    var offset = 0f
    var offsetTarget = 0f
    var offsetTimer = 0f

    var finished = false
    var finishTime = 0f
    var finishOrder = 0

    val speed: Float get() = hypot(vx, vy)
}
