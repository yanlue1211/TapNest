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

    fun nod(head: String) = TapNestClip("nod", 680, tracks = listOf(
        TapNestTrack(head, listOf(
            frame(0, TapNestPose()),
            frame(120, TapNestPose(angle = 3f, y = 1f)),
            frame(270, TapNestPose(angle = 8f, y = 3f)),
            frame(440, TapNestPose(angle = -2.5f, y = -1f)),
            frame(560, TapNestPose(angle = -1f)),
            frame(680, TapNestPose())
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

    fun flap(id: String) = TapNestClip("wing_flap", 680, tracks = listOf(
        TapNestTrack(id, listOf(
            frame(0, TapNestPose()),
            frame(160, TapNestPose(angle = if (id.endsWith("left")) -15f else 15f)),
            frame(330, TapNestPose(angle = if (id.endsWith("left")) 7f else -7f)),
            frame(500, TapNestPose(angle = if (id.endsWith("left")) -5f else 5f)),
            frame(680, TapNestPose())
        ))
    ))

    fun twitch(id: String) = TapNestClip("twitch", 420, tracks = listOf(
        TapNestTrack(id, listOf(
            frame(0, TapNestPose()),
            frame(90, TapNestPose(angle = if (id.endsWith("right")) 9f else -9f)),
            frame(205, TapNestPose(angle = if (id.endsWith("right")) -4f else 4f)),
            frame(420, TapNestPose())
        ))
    ))

    /**
     * Short tap reaction for hats, bows, hair clips and other head accessories.
     * They lift off gently, hang for a beat, land with a tiny rebound, then rest.
     * All displacement is local to the part: the body/head keep their own motion.
     */
    fun hatPop(id: String) = TapNestClip("hat_pop", 680, tracks = listOf(
        TapNestTrack(id, listOf(
            frame(0, TapNestPose()),
            frame(85, TapNestPose(y = -9f, angle = -2f)),
            frame(195, TapNestPose(y = -26f, angle = 2f)),
            frame(285, TapNestPose(y = -29f, angle = 3f)),
            frame(445, TapNestPose(y = -6f, angle = -1.5f)),
            frame(525, TapNestPose(y = 3f, scaleY = 0.98f)),
            frame(595, TapNestPose(y = -2.5f)),
            frame(680, TapNestPose())
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
    ))

    /** Reactions are lightweight and apply to the body, so bound children move together. */
    fun companionAction(action: com.deskpet.data.CompanionAction, body: String): TapNestClip {
        val middle = when (action) {
            com.deskpet.data.CompanionAction.FEED ->
                TapNestPose(y = -5f, scaleX = 1.04f, scaleY = 0.97f)
            com.deskpet.data.CompanionAction.BATH ->
                TapNestPose(angle = -4f, y = 2f)
            com.deskpet.data.CompanionAction.PLAY ->
                TapNestPose(y = -13f, scaleX = 0.97f, scaleY = 1.04f)
        }
        return TapNestClip("companion_action", 950L, tracks = listOf(
            TapNestTrack(body, listOf(
                frame(0, TapNestPose()),
                frame(260, middle),
                frame(490, TapNestPose(angle = if (action == com.deskpet.data.CompanionAction.BATH) 4f else 0f)),
                frame(720, middle.copy(y = middle.y * 0.35f, angle = -middle.angle * 0.4f)),
                frame(950, TapNestPose())
            ))
        ))
    }

    /** A small alternating rock during a head-stroking gesture. */
    fun petHead(head: String) = TapNestClip("pet_head", 1450, tracks = listOf(
        TapNestTrack(head, listOf(
            frame(0, TapNestPose()),
            frame(180, TapNestPose(angle = -3f, y = 1f)),
            frame(360, TapNestPose(angle = -6f, y = 2f)),
            frame(590, TapNestPose(angle = 5f, y = -1f)),
            frame(790, TapNestPose(angle = -4f, y = 1f)),
            frame(1030, TapNestPose(angle = 3f)),
            frame(1210, TapNestPose(angle = -1f)),
            frame(1450, TapNestPose())
        ))
    ))
}
