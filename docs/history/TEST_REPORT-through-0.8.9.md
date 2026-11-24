# 0.8.9 - verification addendum (2026-11-21)

The original gesture service, package name, accessibility permission, MediaProjection
capture, and AndroidManifest were NOT modified. This release modifies the core
strategy and Android integration, and adds an optional local HUD digit reader.

## Checks completed on final intended source

- Kotlin core/calibration/auto-guard smoke: 41 checks PASS.
- New core smart-strategy, verified -15 recycle penalty, bounded beam and endgame
  logic, 52-card knowledge and clock safety: 19 checks PASS.
- Production control regression against Android test doubles: 26 scenarios PASS.
- Existing strategy regression: 7 groups PASS; 12 independent 52-card mock deals.
- Controlled A/B simulation on SAME 12 distinct seeds, 95-step cap per deal:
  Original candidate ordering: 620 actions; aggregate heuristic score 1790;
  21 recyclings, 0 wins; median plan 0ms, p95 approx 4ms.
  New SmartDecision ranking: 625 actions; aggregate heuristic score 1820;
  20 recyclings, 0 wins; median plan 0ms, p95 approx 3ms.
  The simulator's 'score' is an INTERNAL HEURISTIC (not the actual game's
  point system) and this experiment does not demonstrate a complete win.
- Production CardVision + AutomaticRegionReader source compile with Android
  boundary doubles: PASS.
- Stock/waste occupancy pixel regression: PASS.
- Optional green-HUD reader source compile + synthetic score=240 / time=03:47:
  PASS. A separate offline prototype matched three user-provided screen crops
  (240 / 03:47, 260 / 01:41, 0 / 04:48). These are NOT an independent phone OCR
  generalization benchmark; glyphs 5 and 9 are only tentative approximations.
- Automatic layout/glyph runtime regression: NOT RUN. Their scripts require an
  external org.json JAR (`JSON_JAR`) not installed in this execution environment.
  The previous version's historical reports are retained separately and should
  not be mistaken for tests of 0.8.9.
- Gradle assembleDebug, real APK install, 5-minute Android rounds: NOT RUN.
  Android SDK / Gradle distribution not installed in this sandbox. The user must
  build/test on Android Studio and inspect logs before claiming final success.

## Safety and completion semantics

No new physical gesture type. Two independently acquired post-gesture frames are
still required to confirm each move. Timeouts, window switching, permission loss,
repeated identical edges and unreadable screens still prevent blind taps. The
5-minute goal is a target, not a promise to tap in an unsolvable or unknown state.
Diagnostics include move signatures/status and numeric score observations, but
never screenshots, passwords or wallet details.


---

# Verification addendum for SolitaireBot 0.8.8 (2026-11-21)

This is an incremental modification to the EXISTING 0.8.7 Android app. The
Android accessibility click/drag implementation, application ID, MediaProjection
capture mechanism, and modules are retained. Changes primarily improve decision
strategy, no-progress handling, stock recycling and scheduling.

## Evidence and diagnosis

Reviewed the user's approximately 298-second external phone video. At roughly
110 seconds of video time, the game displayed about 03:08 remaining and score
500; further manual interaction was visible. The video supports that early
correct moves occurred followed by a premature lull, not an exact single root
cause confirmed by Android device logs. The code review found multiple capable
contributors: 30-second unstable-state automatic shutdown, cumulative failure
counter, unproductive repeated tableau rearrangements, weak recycle detection,
and expensive repeated planning.

## Re-run tests on the exact final source

- Kotlin core and calibration smoke: 41/41 assertions passed.
- Production control-state regression using Android boundary stubs: 26/26
  scenarios passed, including cancellation, stale callbacks, failed actions,
  session stop, vision misses, unknown waste, verified draw and recycle.
- Strategy regression: 7/7 groups passed; bounded three-ply lookahead,
  exposed-back simulation, safe foundation, reveal, late-round behavior,
  anti-shuffle and verified-progress stock cycles.
- Offline 12 distinct 52-card deals: 624 legal model actions in total;
  aggregate heuristic score 1820; 0 complete wins under the simulator's
  110-step/deal cap. This is NOT real gameplay points or live-device results.
  An earlier within-task scoring variant on the SAME 12 deals generated
  1189 actions, heuristic score 1585, illustrating fewer unproductive moves;
  this is NOT a comparison with the on-device 0.8.7 binary.
- Production card-vision source type check with Android test stubs: passed.
- Waste/card-face pixel occupancy smoke cases: passed.
- No direct Android APK build via Gradle: no Android SDK / Gradle installation
  in this environment. The user should build it with Android Studio.
- No 5-minute multi-round *on-device* test was performed. Physical gesture
  performance, true real-game points, and full-round reliability remain
  unverified until testing on the user's phone.

Source logs included in docs/verification-0.8.8/. Re-run scripts are in tests/.

## Explicit limits

The app cannot see identities of facedown cards before they are turned over.
When no newly verifiable move exists, it safely watches instead of generating
blind touches. No arbitrary Undo gesture is added. The HUD score is not OCR'd;
move ranking uses expected tactical benefit, not a readout of actual score.
Time-remaining urgency is computed from the user-marked round timer, otherwise
estimated from automatic-mode start, not direct OCR of the game clock.
A guarantee to play/win continuously for five minutes on all deals is not
technically supportable from these tests.

---

# تقرير التحقق — 0.8.7 (إضافات)

راجع `CHANGES_0.8.7.md` لتفسير الفيديو وصورتَي المستخدم، والإصلاحات وحدودها.

- Kotlin core smoke: **41/41**.
- Production control regression with Android test doubles: **26/26**؛ أضيفت اختبارات السحب عند عدم قراءة ورقة مروحة السحب، وتغيّر بصمتها، واستمرار المراقبة عند عدم وجود نقلة، وإعادة تدوير المخزون بشروط إيجابية.
- AutomaticRegionReader pixel occupancy regression: **4 حالات** (وجود وجه ورقة فاتح؛ رفض الفراغ الأخضر؛ رفض الظهر الأزرق؛ رفض شريط رفيع).
- Production CardVision + AutomaticRegionReader: compiled using local Kotlin and Android test doubles.
- تم فحص ملف الفيديو والصورتين المرفقة؛ *لم تتم تجربة APK على هاتف المستخدم في هذه البيئة ولم ينفذ Gradle assembleDebug هنا*. الأرقام السابقة ليست اختبارات أداء، ولا دليلًا على أن البرنامج يستطيع الفوز بكل جولة.

---

# إضافة التحقق للإصدار 0.8.6

**تنبيه:** جرى اختبار 17 سيناريو لوحة اصطناعية، و22 سيناريو تحكم، و41 فحص قواعد، مع تشغيل القارئ الفعلي على الصورة التي أرسلها المستخدم. لم يُختبر APK على هاتف فعلي ولم يُشغّل Gradle كاملًا هنا. القوالب الإضافية مشتقة من صورة الاختبار نفسها، لذلك لا تثبت التعميم على جميع أوراق اللعب. للمعلومات التفصيلية انظر `CHANGES_0.8.6.md`.

---

# تقرير التحقق — SolitaireBot 0.8.4

التاريخ: 21 نوفمبر 2026. هذا التقرير يميز بين فحص المصدر والاختبارات المصوّرة المصطنعة وتجربة الهاتف الفعلية.

## النتيجة

أُضيف مسار تهيئة وتشغيل تلقائي يبدأ من أول لقطة ويحافظ على دورة الالتقاط والقراءة والتخطيط واللمس والتحقق. توجد اختبارات فعلية للقواعد والتحكم والتعرف المصوّر، وتُرفق سجلاتها في `docs/verification-0.8.4/`.

**لم تُختبر هذه النسخة على هاتف المستخدم أو على لقطة فعلية واضحة من لعبة المستخدم.** لا تعني الاختبارات التالية أن كل خطوط الأوراق أو كل واجهات الألعاب مدعومة، ولا أنها تثبت الفوز بكل توزيع.

## فحوص الترجمة والموارد

| الفحص | النتيجة |
|---|---|
| ملفات Kotlin الإنتاجية في app وcore | ترجمة 42 ملفًا باستخدام Kotlin2.2.21، JVM17، Android API35 |
| اختبارات JVM | 84 اختبارًا ناجحًا |
| اعتماديات اختبارات app | ترجمة باستخدام JUnit4.13.2 المصرح به في Gradle |
| Smoke للقواعد | 41 assertion ناجحًا |
| التحكم والتنفيذ الفعليان مع بدائل حدود Android | 19 سيناريو ناجحًا |
| التعرف السابق والقوالب اليدوية | 13 سيناريو ناجحًا |
| التعرف الآلي على لوحة RGB كاملة وانتقالاتها | 16 سيناريو ناجحًا |
| AAPT2 compile/link | نجح ربط الموارد وملف Manifest وإدراج الأصول المحلية |
| R.java | نجحت الترجمة |
| D8 | نجح تحويل المصدر وKotlinstdlib إلى DEX |
| حاوية APK محلية غير موقعة للفحص | قُرئت بيانات الحزمة والإصدار804/0.8.4 |
| Gradle assembleDebug / lint | لم يُشغّلا في بيئة التسليم |
| تثبيت وتشغيل على هاتف حقيقي | لم يُنفّذ |

حاوية APK المذكورة أداة تحقق داخلية غير موقعة، وليست الملف المرسل للمستخدم. التسليم هو مشروع المصدر ZIP لبنائه في Android Studio.

## نتيجة التعرف على الرموز — مع بيان الامتناع

استُخدم القارئ الإنتاجي والأصول نفسها داخل اختبارات RGB مولدة بخطوط وأحجام وإزاحات مختلفة. شملت المجموعة **780 صورة اختبار**:

| الفئة | النتيجة |
|---|---|
| 728 صورة ورقة صحيحة | 622 قراءة صحيحة، 106 حالات غير مؤكدة، صفر قراءة خاطئة ضمن هذه المجموعة |
| 28 صورة سلبية أو غير صالحة | رُفضت جميعها |
| 16 رمز أساس مصمت أو مجوف | قُرئت جميعها صحيحًا |
| 8 حالات رمز إعادة التدوير، موجبة وسلبية | صُنفت جميعها كما هو متوقع |

ليست الـ106 حالات أوراقًا مقروءة بنجاح. هي **امتناع مقصود عن التسمية** عندما لم تتجاوز الرتبة والنوع حد التشابه وهامش الفصل؛ تركزت في الأحجام الصغيرة وبعض الخطوط ذات الزوائد. درجة التشابه ليست احتمالًا إحصائيًا لصحة التعرف.

أمثلة النتائج داخل المجموعة: قُرئت الأوراق الـ52 كلها بخط DejaVu Sans بحجمي25و33، وNoto Sans Bold بحجمي25و33، وDejaVu Serif بحجم33. توجد التفاصيل لكل خط وحجم في `tests/automatic-vision/RESULTS.txt` وسجل التحقق النهائي.

هذه مجموعة اختبار مصطنعة، وبعض عائلات خطوطها ممثلة في قوالب التعرف، مع اختلاف الأحجام وطرق الرسم والإزاحة. **ليست قياسًا عامًا للدقة على صور ألعاب غير مرئية سابقًا.** لا يُستنتج منها ضمان توافق رسوم لعبة المستخدم.

أصول التعرف: 10,591 قالبًا محليًا للرتب والأنواع والرموز والحالات المضللة. يُحمل الأصل مرة واحدة في ذاكرة العملية. يتضمن الملف `OFFLINE_GLYPH_LICENSES.txt` مصادر الخطوط وإشعاراتها. هذا قارئ هندسي وقوالب أشكال، وليس نموذجًا عصبيًا عامًا مدربًا على جميع الألعاب.

## ما اختبرته سيناريوهات اللوحة الـ16

تشمل استخراج المناطق الـ13 من البكسلات، وقراءة سبع أوراق أولية دون تعليم يدوي، ومعرفة ترتيب الأنواع في الأساسات، ومطابقة خلفية اللعب وظهر المخزون، والتحقق من نقل آس إلى الأساس وظهور فراغ في العمود، واكتشاف ظهر مكشوف ثم التحقق من قلبه إلى ورقة مقروءة، والسحب من المخزون، وإثبات رمز إعادة التدوير، ورفض مخزون غير معروف.

تشمل أيضًا تبديل مواقع أنواع الأساسات، تخطيطًا بمقاس صورة مختلف، استبعاد شريط أزرار أسفل اللوحة، انتهاء أدلة جلسة سابقة، وإلغاء افتراضات العدد المخفي/إعادة التدوير بعد حركة غير مؤكدة. صور الشاشة الفارغة واللوحة المحجوبة والأوراق المبتورة ورمز الأساس المفقود لا تنتج معايرة قابلة للتنفيذ.

## ما اختبرته القواعد والتحكم

- Draw1 وDraw3 وآخر حزمة مخزون أصغر من3؛ لا تُخترع هويات الأوراق المخفية في التخطيط.
- لا يُقلب عمود لأن OCR فشل؛ يلزم ظهر ورقة ظاهر ومطابق، وثقة كافية، ونقطة لمس داخل العمود.
- لا يبدأ النقل إلى أساس أو عمود أو مخزون مجهول.
- تحتاج الحركة إلى صورتين مستقلتين مستقرّتين قبل الإرسال، ثم صورتين مستقلتين بعد انتهاء الإيماءة للتحقق.
- قبول أندرويد للإيماءة لا يسجل حركة ناجحة وحده؛ يلزم تغير الأوراق المتوقع.
- تغيّر إحداثيات الأوراق أو الظهر بعد اقتراح الحركة يمنع استخدام الموضع القديم حتى لو بقيت أسماء الأوراق نفسها.
- الإيقاف وتغيير جلسة الالتقاط وطلب لقطة أحدث تبطل نتائج القراءة المتأخرة؛ لا تعيد نتيجة OCR قديمة تشغيل المساعد.
- فشل حركة تلقائية يسمح بإعادة قراءة محكومة لاختيار حركة مختلفة. لا يُعاد نفس الانتقال من نفس الحالة، ويتوقف التشغيل بعد3 إخفاقات.
- تنفيذ النقلة اليدوية الواحدة لا يتحول إلى تشغيل تلقائي عند الفشل.
- callback قديم لإيماءة انتهت مهلة التحقق منها لا يُنهي الحركة الجديدة.
- تكرار حالة اللوحة ثلاث مرات وحد المحاولات120 وحد الوقت300ثانية يمنع الاستمرار بلا نهاية.

## حدود الاختبار والتوافق

أول لقطة يجب أن تظهر لوحة عمودية مدعومة: سبعة أعمدة منتظمة، وورقة فاتحة كاملة في كل عمود، وأربع أساسات أعلى اليسار بأنواع واضحة، ومخزون أعلى اليمين. القارئ يستهدف الرتبة فوق النوع في الزاوية العلوية اليسرى، والظهر الملون ذي النمط، والخلفية المتجانسة نسبيًا. الرموز الأفقية أو الأوراق المدورة أو الخطوط المزخرفة/الصغيرة جدًا أو الخانات بلا رموز قد تتوقف عند حالة غير معروفة.

وقت اللعبة الفعلي لا يُستنتج من وقت الصورة. التشغيل لا يبدأ رهانًا أو جولة في التطبيق الآخر. كشف الورق المقلوب يتم بعد أن يظهر وجهه؛ معرفة52هوية من صورة أولى تضم أوراقًا مخفية غير ممكنة.

حدود AndroidBitmap/Context/Handler/Accessibility في اختبارات JVM تستخدم بدائل اختبار محددة. لم تُقاس أذونات الجهاز، أو لمس شاشة Samsung، أو سلوك النافذة العائمة على الجهاز الحقيقي، أو زمن معالجة الصور عليه. يلزم تثبيت APK المبني محليًا واختبار جولة تجريبية لتأكيد تلك الجوانب.

## إعادة تشغيل الاختبارات

من Android Studio يمكن تشغيل اختبارات core وapp باعتماديات المشروع. لا تعتمد اختبارات صور RGB الإضافية على SDK هاتف؛ تحتاج JDK وKotlin compiler وorg.json لتنفيذ القارئ الإنتاجي مع بدائل Bitmap.

```sh
./gradlew :core:test :app:testDebugUnitTest
KOTLINC=/path/to/kotlinc sh tests/run-core-smoke.sh
KOTLINC=/path/to/kotlinc sh tests/run-control-regression.sh
JSON_JAR=/path/to/json.jar KOTLIN_BIN=/path/to/kotlinc sh tests/run-vision-regression.sh
JSON_JAR=/path/to/json.jar KOTLIN_BIN=/path/to/kotlinc sh tests/run-automatic-glyph-regression.sh
JSON_JAR=/path/to/json.jar KOTLIN_BIN=/path/to/kotlinc sh tests/run-automatic-board-regression.sh
```

سطر Gradle أعلاه تعليمات لإعادة الفحص في بيئة المستخدم، وليس ادعاءً أنه شُغّل هنا. سجلات الترجمة المستقلة والأوامر التي شُغّلت هنا مرفقة في `docs/verification-0.8.4/`.
