# Verification method for0.8.4

Production: all42 Kotlin sources compiled together using Kotlin2.2.21, Android35 stubs and JVMtarget17. Tests: coreJUnit5 plus appJUnit4; a separate app-only compilation used the declared JUnit4.13.2 dependency to catch accidental undeclared test APIs. The final aggregate run passed84tests. Additional shell regression suites exercise production core/control/vision with explicitly documented Android boundary doubles.

AAPT2 compiled resources and linked a manifest derived from namespace/applicationId, min/targetSDK and version values declared in Gradle. Bundled assets were passed through AAPT2 `-A`. GeneratedR.java compiled against Android35. D8 translated production classes and the Kotlinstdlib into DEX. Resource+DEX container metadata was inspected as an unsigned local compile artifact.

No Gradle assemble, Android lint, emulator, or physical-device execution was performed in this environment. Source ZIP is the deliverable; Android Studio must perform its own Gradle build/signing. All new production file hashes match the final translation run. See production-sources.json, checks.json, and individual logs.

The bundle contains generated test images only; they are not screenshots from the target game. Recognition abstentions are documented explicitly in TEST_REPORT.md.
