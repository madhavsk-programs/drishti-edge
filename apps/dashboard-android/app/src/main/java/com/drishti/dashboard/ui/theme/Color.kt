package com.drishti.dashboard.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The palette.
 *
 * Warm paper rather than white, warm near-black rather than #000, and eight
 * separate hues instead of one brand colour used at four opacities. A desk
 * screen is read by glance and by colour before it is read by word, so each
 * thing that means something different gets a hue of its own — and the four
 * statuses keep theirs everywhere they appear, on the wall, in the alert queue
 * and on a person's history.
 *
 * Every pairing here was picked to clear WCAG AA for body text: [Tone.ink] on
 * [Tone.fill], and every ink on [Canvas] and [Surface].
 */

val Canvas = Color(0xFFFBF7F2)
val Paper = Color(0xFFFFFFFF)
val Sunken = Color(0xFFF4EFE8)
val Hairline = Color(0xFFE8E0D5)
val HairlineStrong = Color(0xFFD6CBBA)

val InkStrong = Color(0xFF23201C)
val InkBody = Color(0xFF3C362F)
val InkMuted = Color(0xFF7A7168)
val InkFaint = Color(0xFFA39a8F)

/**
 * A colour family: a tint to sit a card on, a border, a readable ink, and a
 * saturated fill for the one element in that card that must be seen from
 * across a room.
 */
@Immutable
data class Tone(
    val fill: Color,
    val edge: Color,
    val ink: Color,
    val strong: Color,
    val onStrong: Color = Color.White,
)

/** Somebody is out, reporting, and nothing is wrong. */
val SafeTone = Tone(
    fill = Color(0xFFDFF3E8),
    edge = Color(0xFFA5DAC1),
    ink = Color(0xFF0A6247),
    strong = Color(0xFF12875F),
)

/** Look at this person soon. */
val AttentionTone = Tone(
    fill = Color(0xFFFDEFD3),
    edge = Color(0xFFEFC981),
    ink = Color(0xFF8A5105),
    strong = Color(0xFFC97A0C),
)

/** Someone needs help now. */
val HelpTone = Tone(
    fill = Color(0xFFFCE3E3),
    edge = Color(0xFFF0AAAD),
    ink = Color(0xFFA81F28),
    strong = Color(0xFFD3323B),
)

/** The phone stopped talking. Absence of news, not good news. */
val OfflineTone = Tone(
    fill = Color(0xFFEAE8F5),
    edge = Color(0xFFC3BDE0),
    ink = Color(0xFF4C4770),
    strong = Color(0xFF655F8C),
)

/** Section accents. Used for headings, tiles and category badges. */
val IndigoTone = Tone(
    fill = Color(0xFFE3E7FB),
    edge = Color(0xFFB8C1F0),
    ink = Color(0xFF2A3580),
    strong = Color(0xFF3D4EA8),
)

val TealTone = Tone(
    fill = Color(0xFFD9F0F2),
    edge = Color(0xFF9FD6DD),
    ink = Color(0xFF0A5A64),
    strong = Color(0xFF0F7B87),
)

val PlumTone = Tone(
    fill = Color(0xFFF8E4F4),
    edge = Color(0xFFE4B6DB),
    ink = Color(0xFF6F2A64),
    strong = Color(0xFF8B3A7E),
)

val ClayTone = Tone(
    fill = Color(0xFFFBE7DB),
    edge = Color(0xFFEEBC9F),
    ink = Color(0xFF8E3D1C),
    strong = Color(0xFFB4552F),
)

val SlateTone = Tone(
    fill = Sunken,
    edge = Hairline,
    ink = Color(0xFF564E45),
    strong = Color(0xFF6E655A),
)

/** The one bright note in the palette: the brand mark and the live pulse. */
val Gold = Color(0xFFF2A03D)

/**
 * Avatar tints. A person keeps the same one across every screen, chosen by a
 * stable hash of their id, so the block of colour beside a name becomes a
 * second way to recognise them at a glance.
 */
val AvatarTones = listOf(IndigoTone, TealTone, PlumTone, ClayTone, SafeTone, OfflineTone)

fun avatarToneFor(id: String): Tone {
    val index = (id.hashCode().toLong() and 0xFFFFFFFFL) % AvatarTones.size
    return AvatarTones[index.toInt()]
}
