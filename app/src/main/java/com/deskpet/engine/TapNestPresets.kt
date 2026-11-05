package com.deskpet.engine

/** Small, reusable keyframe clips for user-drawn layered pets.
 * No fixed art assets: callers bind their own layer IDs to each preset. */
object TapNestPresets {
    private fun frame(ms: Long, pose: TapNestPose, ease: TapNestEase = TapNestEase.SMOOTH) =
        TapNestKeyframe(ms, pose, ease)

    fun blink(eyes: String) = TapNestClip("blink", 280, tracks = listOf(
        TapNestTrack(eyes, listOf(
            frame(0, TapNestPose()),
            frame(110, TapNestPose(scaleY = 0.08f), TapNestEase.HOLD),
            frame(170, TapNestPose(scaleY = 0.08f)),
            frame(280, TapNestPose())
        ))
    ))

    fun nod(head: String) = TapNestClip("nod", 560, tracks = listOf(
        TapNestTrack(head, listOf(
            frame(0, TapNestPose()),
            frame(180, TapNestPose(angle = 10f, y = 3f)),
            frame(370, TapNestPose(angle = -3f, y = -1f)),
            frame(560, TapNestPose())
        ))
    ))

    fun bounce(body: String) = TapNestClip("bounce", 550, tracks = listOf(
        TapNestTrack(body, listOf(
            frame(0, TapNestPose()),
            frame(100, TapNestPose(scaleX = 1.07f, scaleY = 0.92f, y = 5f)),
            frame(240, TapNestPose(scaleX = 0.96f, scaleY = 1.05f, y = -16f)),
            frame(420, TapNestPose(scaleX = 1.02f, scaleY = 0.97f, y = 2f)),
            frame(550, TapNestPose())
        ))
    ))

    fun wag(tail: String) = TapNestClip("wag", 800, loop = true, tracks = listOf(
        TapNestTrack(tail, listOf(
            frame(0, TapNestPose(angle = -15f)),
            frame(400, TapNestPose(angle = 15f)),
            frame(800, TapNestPose(angle = -15f))
        ))
    ))

    fun earTwitch(left: String, right: String) = TapNestClip(
        "ears", 460, tracks = listOf(
            TapNestTrack(left, listOf(
                frame(0, TapNestPose()),
                frame(110, TapNestPose(angle = -12f)),
                frame(250, TapNestPose(angle = 5f)),
                frame(460, TapNestPose())
            )),
            TapNestTrack(right, listOf(
                frame(0, TapNestPose()),
                frame(110, TapNestPose(angle = 12f)),
                frame(250, TapNestPose(angle = -5f)),
                frame(460, TapNestPose())
            ))
        )
    )

    fun talk(mouth: String) = TapNestClip("talk", 480, loop = true, tracks = listOf(
        TapNestTrack(mouth, listOf(
            frame(0, TapNestPose(scaleY = 0.8f)),
            frame(120, TapNestPose(scaleY = 1.18f)),
            frame(240, TapNestPose(scaleY = 0.8f)),
            frame(360, TapNestPose(scaleY = 1.2f)),
            frame(480, TapNestPose(scaleY = 0.8f))
        ))
    )
}
