<!-- Emberfall master roadmap, owner's document dated 2026-11-10.
     NOTE: the verbatim paste did not persist in the sandbox; this edition records
     the restructured roadmap (phase and milestone structure, statuses, design
     principles) as received in conversation. The owner's original wording should
     replace it when re-pasted. The superseded previous edition (old phase
     numbering, 552 lines) is kept as docs/EMBERFALL_ROADMAP_v1.md.
     App keeps the name Greenhollow; Emberfall is the codename.
     Status: Phases 1 and 2 completed and validated (foundation hardening included).
     Phase 3 Milestone 1 (autonomous NPCs, daily schedules, needs, tiered
     simulation, save format v3) implemented and covered by 56 passing JVM unit
     tests; debug and release builds succeed. Device validation outstanding.
     Later milestones and phases not started. -->

# Emberfall — Master Development Roadmap & Creative Direction

*Owner's master document, 2026-11-10. The app keeps the name **Greenhollow**; Emberfall is the project codename. This file is the project's authoritative roadmap copy.*

## Creative direction

The vision is unchanged at heart: an open-world fantasy RPG and living-world simulation where the world would carry on if the player never arrived. Inhabitants have their own routines, ambitions, relationships, problems, and histories; systems interact without requiring player involvement.

Six inspirations shape the creative direction — as design references, never templates to reproduce:

- **Fable** — atmosphere, personality, whimsy with occasional darkness.
- **Dragon Quest** — warmth and welcoming charm.
- **The Bard's Tale** — humour and irreverence.
- **Skyrim** — depth, exploration, the pull of the next hill.
- **Oblivion** — systemic schedules and a simulated populace.
- **The Sims** — needs-driven autonomy.

The game keeps its own identity, history, setting, visual language, and mechanics.

## Phase structure

### Phase 1 — Foundation <status>Completed and validated.</status>

Content packs, saves, modular systems, diagnostics. Hardened 2026-11-10: dependency-ordered import with full pre-install validation, save recovery/rotation, newer-save protection, persistent-id canonicalization, NPC id stability — covered by automated tests.

### Phase 2 — Core RPG systems <status>Completed and validated.</status>

Attributes, skills-by-doing, levelling, expanded equipment slots and item properties, data-driven branching dialogue, shared interaction framework, character customisation, journal and discovery tracking.

### Phase 3 — Living-world simulation <status>Milestones 1 and 2 implemented; device validation outstanding.</status>

Make the inhabitants independent participants in the world. Five milestones:

- **Milestone 1 — Autonomous NPCs, daily schedules & needs.** *Implemented.* Persistent needs (hunger, fatigue/rest, social, safety), data-driven schedule templates per occupation, priority decisions (danger > critical needs > schedule > routine), three simulation tiers with bounded off-screen catch-up, travel watchdog, save format v3 with v2→v3 migration, world-simulation diagnostics, dialogue conditions for activity and need, 56 passing JVM tests, debug and release builds green.
- **Milestone 2 — Relationships, memory, and social behaviour.** *Implemented.* Directional eight-dimension relationships keyed "holder>other" (familiarity, friendship, trust, respect, affection, fear, resentment, hostility), grown by personality-scaled rules per event type; structured memory records with pure-function fading, protected significant memories, a per-person cap and duplicate suppression; NPC–NPC encounters (conversation, quarrel, shared work) with per-pair cooldowns; gossip as travelling stories — each holder's confidence and distortion per hop, deterministic wording tiers, original event always identifiable; the player participates under id "player" and only learns what they are told, with the reliability shown in the journal; dialogue conditions rel, rel_memory and gossip (with a core pack example); new journal surfaces (village talk, your standing, shared history); save format v4 with a v3→v4 migration that turns old bonds and affinity into real relationship records; social-world diagnostics in Settings and the shared report; 74 passing JVM tests (18 new), debug and release builds green. Device behaviour unverified.
- **Milestone 3 — Households and family.** Not started.

**Interlude — dialogue voice (between milestones 2 and 3).** *Implemented.* Every spoken line is voiced in old-school RPG blips, ported from the project's own vocalizer design: each letter always sings the same pentatonic note, vowels carry formant-filtered tones, plosives click, fricatives hiss, questions lift at the end. Four voices (sprite, hero, giant, robot) chosen from the person, not a dice roll: a race may declare a voice for all its folk in its pack definition (the Ashen Elves chirp, the Stoneborn rumble; an unknown word is reported and falls back to age — the young are sprites, the old giants), while character shapes the sound — bullies grind like robots, the fearful chirp, courage lifts the pitch, aggression weighs it down, the sociable talk faster — plus a small per-person pitch so no two villagers sound alike. A Settings › Sound toggle turns voices off. Synthesis and the voice chooser are pure Kotlin (9 new JVM tests), played through an AudioTrack that cancels the previous line and silences on dismiss; 83 tests now run and debug and release builds are green. The sound has not been heard on a device.
- **Milestone 4 — Professions and economy.** Not started.
- **Milestone 5 — Settlement simulation.** Not started.

### Phase 4 — Player lifestyle <status>Not started.</status>

The player's own life in the world — living in it, not just adventuring through it.

### Phase 5 — Factions <status>Not started.</status>

Faction structure, standing, and world-level social narrative.

### Phase 6 — Creature ecosystems <status>Not started.</status>

Creatures as populations within the living world, not spawned encounters.

### Phase 7 — Exploration and lore <status>Not started.</status>

New regions, discovery, and the world's forgotten histories.

### Phase 8 — Atmosphere and presentation <status>Not started.</status>

The distinctive identity: art, sound, and presentation pass.

### Phase 9 — Long-term simulation and optimization <status>Not started.</status>

Sustained long-play performance and persistence on Android.

### Phase 10 — Expansion <status>Not started.</status>

Post-core expansion of the experience.

---

*Note: milestone and phase briefs beyond Milestone 1 live in the owner's master document; this edition preserves their structure and status. Development rules throughout: inspect before changing, extend existing systems, preserve save and pack compatibility, verify with tests and both build configurations, and never count a successful build as done — device behaviour must be confirmed by play.*
