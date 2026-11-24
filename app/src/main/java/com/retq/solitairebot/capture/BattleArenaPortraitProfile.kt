package com.retq.solitairebot.capture

/**
 * Screenshot-derived calibration profile for the supplied Battle Arena portrait sample.
 * Reference image: 540x1170 px. Coordinates are normalized to the full screenshot,
 * so they scale with resolution when the aspect ratio and UI layout remain unchanged.
 * These are crop/search regions, not proof that a card has been recognized.
 */
object BattleArenaPortraitProfile {
    const val REFERENCE_WIDTH = 540
    const val REFERENCE_HEIGHT = 1170
    const val PROFILE_ID = "battle-arena-portrait-sample-540x1170"

    val foundations = listOf(
        NormalizedBox(18f/540f, 375f/1170f, 85f/540f, 471f/1170f), // clubs
        NormalizedBox(89f/540f, 375f/1170f, 157f/540f, 471f/1170f), // spades
        NormalizedBox(162f/540f, 375f/1170f, 230f/540f, 471f/1170f), // hearts
        NormalizedBox(235f/540f, 375f/1170f, 302f/540f, 471f/1170f)  // diamonds
    )

    val waste = NormalizedBox(333f/540f, 374f/1170f, 447f/540f, 472f/1170f)
    val stock = NormalizedBox(451f/540f, 374f/1170f, 519f/540f, 472f/1170f)

    /** Full column search regions. Extended to include the lower tableau visible in the
     * supplied clean gameplay reference; still only search bounds, never card evidence. */
    val tableauColumns = listOf(
        NormalizedBox(18f/540f, 494f/1170f, 86f/540f, 970f/1170f),
        NormalizedBox(89f/540f, 494f/1170f, 159f/540f, 970f/1170f),
        NormalizedBox(161f/540f, 494f/1170f, 231f/540f, 970f/1170f),
        NormalizedBox(235f/540f, 494f/1170f, 304f/540f, 970f/1170f),
        NormalizedBox(307f/540f, 494f/1170f, 376f/540f, 970f/1170f),
        NormalizedBox(379f/540f, 494f/1170f, 448f/540f, 970f/1170f),
        NormalizedBox(451f/540f, 494f/1170f, 520f/540f, 970f/1170f)
    )

    val boardLayout = NormalizedBoardLayout(
        stock = stock,
        waste = waste,
        foundations = foundations,
        tableauColumns = tableauColumns
    )

    /** Visible face-card labels manually read from this one sample; use only as a
     * fixture for recognition tests, never as live game state. */
    val sampleVisibleTableauCards = listOf("AS", "AH", "KS", "2S", "QH", "JH", "QS")
}
