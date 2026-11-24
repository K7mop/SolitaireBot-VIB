# Final 0.8.10 validation provenance

The final source snapshot was compiled independently, with separate core and Android app Kotlin modules. All JUnit tests, Android resource compilation/linking, generated resource Java and D8 were run again against the final LiveSession age fix. Both standalone control regression and control latency suites were also rerun.

The preceding full-corner verification completed every functional/build test successfully. Its source snapshot gate correctly failed because the approved LiveSession change arrived during that run. That checkpoint and its failure remain preserved in `validation-fullcorner-before-age`; it is not presented as a passing final build.

The only production source change between those checkpoints is LiveSession.kt. All 16 assets/resource/manifest/Gradle/wrapper inputs match byte for byte. The 15 standalone suites reused in the final checks array do not compile LiveSession; their production source inputs are unchanged. Each reused result retains its original exit code, timing, log, and provenance marker. The provenance JSON records their log and current runner hashes.

The age fix requires both members of a stable visual pair to remain inside the existing 1,000 ms freshness budget. Two new JUnit scenarios test stale pairs before dispatch and after dispatch; the standalone controller suite adds the corresponding combined scenario. Existing positive fixtures were retimed to place the first observation within 500 ms of the second while preserving their action timestamps and assertions, so they continue to test their original success/duplicate/confidence behavior rather than an expired pair.

The single final vision benchmark uses desktop JVM Android boundary stubs and supplied screenshots, including same-source resampling. It is not a physical phone, MediaProjection, Accessibility gesture, Gradle assemble, or complete timed game trial. No device performance or score promise is inferred from it.
