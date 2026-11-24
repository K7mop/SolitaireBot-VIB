# التغييرات من 0.8.0 إلى 0.8.1

هذه مراجعة لنفس مشروع SolitaireBot-Ready ووحدتي app وcore، بنفس معرّف التطبيق. واجهة التشغيل الحالية للمراقبة والتقاط صورة الشاشة ومعاينتها فقط.

## ملفات أصلية عُدّلت

- `OPEN-IN-STUDIO.cmd`
- `README.md`
- `START-HERE-AR.txt`
- `TEST_REPORT.md`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/retq/solitairebot/capture/ScreenCaptureService.kt`
- `app/src/main/java/com/retq/solitairebot/control/FloatingControls.kt`
- `app/src/main/java/com/retq/solitairebot/ui/MainActivity.kt`
- `core/build.gradle.kts`

## ملفات أُضيفت

- `app/src/main/java/com/retq/solitairebot/capture/CaptureRequestGate.kt`
- `app/src/main/java/com/retq/solitairebot/storage/CaptureMonitorStore.kt`
- `app/src/test/java/com/retq/solitairebot/capture/CaptureRequestGateTest.kt`
- `docs/verification-0.8.1/TEST-com.retq.solitairebot.capture.CaptureRequestGateTest.xml`
- `docs/verification-0.8.1/TEST-com.retq.solitairebot.core.CoreRulesTest.xml`
- `docs/verification-0.8.1/TEST-com.retq.solitairebot.core.VisualSafetyTest.xml`
- `docs/verification-0.8.1/lint-results-debug.xml`
- `CHANGES_0.8.1.md` (هذا الملف).

## تغيير نقاط التشغيل

أزيل تسجيل أنشطة محرر اللوحة والمعايرة والتعليم وخدمة اللمس من AndroidManifest في هذه المراجعة، مع إبقاء ملفاتها الأصلية داخل المصدر. لا تستدعي واجهة المراقبة أو خدمة الالتقاط محرك التعرف أو اقتراح النقلات أو تنفيذ اللمس.

بقي اسم التطبيق ومعرّف الحزمة واسم ملف الصورة والإعدادات السابقة. لم تُعدّل مصادر المحرك أو التعرف أو AppStore. ارتفع رقم البناء من800 إلى801 والإصدار من0.8.0 إلى0.8.1.

## ما لم يدخل الحزمة

لا تتضمن ZIP ملف APK أو مخرجات بناء أو مفاتيح توقيع أو local.properties أو بيانات شبكة خاصة ببيئة التحقق. أدوات Gradle Wrapper وملفات المشروع اللازمة موجودة.
