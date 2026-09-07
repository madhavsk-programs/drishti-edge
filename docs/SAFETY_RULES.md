# Safety rules

The user cannot check the phone's work. Every rule here follows from that.

These are carried from the parent project's `DECISIONS.md` §3 and are reproduced
standalone so they can be read in thirty seconds at hour 22 of a build, when
someone proposes a shortcut that quietly trades against one of them.

---

## Never

- **Never say a road, crossing, or path is safe.** Report what is detected.
  Never certify. A `traffic light` detection means a traffic light is present —
  it never means "cross now."
- **Never state distance in metres, feet, or any absolute unit.** A single camera
  cannot measure it. Use only `FAR`, `MEDIUM`, `NEAR`, `IMMEDIATE`, `UNKNOWN`.
- **Never invent a direction** when evidence is weak or contradictory. The correct
  output is `PAUSE_UNCLEAR`, spoken as an admission of uncertainty.
- **Never present the system as a replacement** for a white cane, a guide dog,
  mobility training, or human judgement.
- **Never store frames.** Ring buffer, overwritten. Nothing to disk in the walking
  path.
- **Never perform facial recognition, identity tracking, or route-history
  logging.**
- **Never test blindfolded.** Controlled environments, sighted tester, evaluating
  whether the guidance *would have been* correct.

## Always

- **Prefer `STOP` or `PAUSE_UNCLEAR` over a confident wrong answer.** For this
  user, a confident wrong answer is the most dangerous possible output.
- **Carry every state in more than one channel** — a word, an icon shape, and a
  haptic pattern. A blind user receives nothing from colour.
- **Let safety guidance preempt everything.** It interrupts a scene answer
  mid-sentence. It is never queued behind anything.
- **Announce degradation out loud.** A silently degraded assistant is worse than
  an honestly absent one, because the user's own safety decisions depend on
  knowing what the system can currently see.
- **Duck the user's audio, never stop it.** Blind users very often have music or
  a podcast running.

---

## The two tests most likely to be skipped

They are also the two that matter most, because they verify the system admits
uncertainty rather than manufacturing confidence.

| Test | Pass condition |
|---|---|
| Cover the camera | `PAUSE_UNCLEAR`. Never a confident direction. |
| Smear the lens / low light | `PAUSE_UNCLEAR`, degradation announced aloud. |

A system that returns `CLEAR` when it can see nothing has inverted its own
purpose. It will walk someone into a wall while sounding certain.

---

## The rule behind the rules

Uncertainty and danger are not the same thing, and conflating them is the most
common way to get this wrong.

- High **danger** → `STOP`. Something is there.
- High **uncertainty** → `PAUSE_UNCLEAR`. We do not know what is there.

Mapping uncertainty onto danger makes the system cry wolf until the user stops
listening. Mapping uncertainty onto *clear* walks them into things. Keep them
separate, give them distinct haptic patterns, and let the user's own judgement
fill the gap the system honestly reports.
