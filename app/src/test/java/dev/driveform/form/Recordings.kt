package dev.driveform.form

import org.junit.Assert.assertEquals

/** 에뮬레이터 Virtual Console에서 녹화한 주행(signal-probe)을 검출기에 그대로 재생합니다. */
object Recordings {
    fun replayAll(name: String): List<MoveForm> {
        val moves = mutableListOf<MoveForm>()
        val detector = MoveDetector(
            onMove = { moves += it },
            onMoveUpdated = { updated -> moves.replaceAll { if (it.id == updated.id) updated else it } },
        )
        var last = 0L
        javaClass.classLoader!!.getResourceAsStream("$name.csv")!!.bufferedReader().readLines().drop(1).forEach { line ->
            val (ms, signal, value) = line.split(",", limit = 3).let { Triple(it[0].toLong(), it[1], it[2]) }
            last = ms
            when (signal) {
                "speed" -> detector.onSpeed(ms, value.toFloat())
                "steering" -> detector.onSteering(ms, value.toFloat())
                "brake" -> detector.onBrake(ms, value == "true")
                "turn" -> detector.onTurnSignal(ms, when (value) { "LEFT" -> Side.LEFT; "RIGHT" -> Side.RIGHT; else -> null })
            }
        }
        // 녹화는 도로에서 한 동작이라, 끝나면 다시 도로 속도로 달려 나간 것으로 봅니다(느리게 한 유턴은 이때 회전으로 나옵니다).
        // 달려 나가며 생긴 출발은 녹화 밖이라 빼고, 녹화 안에서 시작한 동작만 돌려줍니다.
        detector.onSteering(last, 0f)
        for (i in 1..40) detector.onSpeed(last + i * 100L, i / 3.6f)
        return moves.filter { it.startMs <= last }
    }

    /**
     * 녹화 하나에는 핸들 동작(회전·유턴·차선 변경)이 하나만 들어 있습니다. 출발·정지는 따로 봅니다.
     * 다른 종류로 잘못 잡히면 개수가 달라져 실패합니다.
     */
    fun replay(name: String): MoveForm {
        val steer = replayAll(name).filter { !it.kind.isSpeedOnly }
        assertEquals(1, steer.size)
        return steer.single()
    }

    fun replayOne(name: String, kind: MoveKind): MoveForm {
        val found = replayAll(name).filter { it.kind == kind }
        assertEquals(1, found.size)
        return found.single()
    }
}
