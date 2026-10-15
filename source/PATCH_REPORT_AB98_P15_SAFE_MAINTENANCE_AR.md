# AB File Pro V2 — AB98 P15

إصدار صيانة مبني على P14 المعتمد (Commit ea1c2b9ffd94d69761e816f6b71ba8660b39706b). Version Code: 167.
Version Name: 2.0.0-alpha98-p15-safe-maintenance.

## حالة الإصدار

**نجحت كل الاختبارات: 84 Android + 19 Scanner + 29 JVM = 132 PASS**، دون أي FAIL أو SKIP.

| البند | القيمة |
| --- | --- |
| تشغيل GitHub | https://github.com/aboshra67-debug/ab-file-pro-build/actions/runs/38687817811 |
| الفرع | `fix/p15-safe-maintenance-20261015`، والمصدر تحت `p15-source/` |
| Commit | `dbbf8613bef3dd9d36ca40a87c33bc5997196157` |
| APK | `AB98_P15_SAFE_MAINTENANCE_TEST.apk`، والحجم 131992320 بايت |
| SHA-256 للـAPK | `6d036aeeb279f6085af3b50b887fa37451b6366ff416e09541972d23a8d93044` |
| Package / Label | `com.abfilepro.app.p15mainttrial` / `AB File Pro P15` |
| ABI | `arm64-v8a` فقط |
| التوقيع | Android Debug، وv2 verified، وبصمة الشهادة SHA-256: `ce22c436b72022cebb5de876ee283a2d6986d47adebaa3224353bd4a80b123af` |
| فحص التشغيل | ثُبّت APK الهاتف نفسه على المحاكي API30: ظهرت الواجهة ولم يحدث crash |
| المحاذاة | ZIP وELF لصفحات 16KB ناجحة (فحص ساكن) |

بصمة شهادة P15 تختلف عن P14، لأن runner جديدًا ولّد مفتاح Debug جديدًا. لذلك P15 ليس تحديثًا لـP14، ويُثبّت بجواره بـPackage مستقل.

بيانات التشغيل الأصلية في `P15_VERIFICATION/`. نُقلت ملفات تشغيل CI إلى الفرع `artifacts/p15-verified-20261015` بالـworkflow المساعد `p15-fetch-artifacts.yml`، لأن جلسة العمل لا تصل إلى خادم تخزين Actions. بصمة APK بعد إعادة تجميع أجزائه مطابقة لبصمة التشغيل.

## التعديل: SCAN-04 — حفظ Batch كصور JPG

**المشكلة (مؤكدة في كود P14):** كانت كل صورة تُنشر في مجلد الحفظ قبل التي تليها. إذا فشلت صفحة أو أُلغي الحفظ، تعود المراجعة كاملة وتبقى الصور المكتملة في المجلد، فتكرر إعادة المحاولة هذه الصور.

**الإصلاح:** الحفظ أصبح «كل الصفحات أو لا شيء» لكل محاولة:

- كل صفحة ما زالت تُكتب إلى ملف `.part` مخفي، ثم يُنفذ fsync، ثم تُنشر، كما في Alpha41.
- تسجل المحاولة هوية كل صورة نشرتها: المسار والحجم ووقت التعديل.
- عند الفشل أو الإلغاء تحذف المحاولة صورها التي لم تتغير فقط.
- لا يُلمس أي ملف كان موجودًا قبل المحاولة، ولا أي ملف تعدّل بعد نشره.
- صفحات المراجعة لا تتغير، فيمكن إعادة المحاولة دون تكرار الصفحات.
- يُتحقق من رأس JPG لكل صفحة قبل اعتماد الدفعة، وليس للصفحة الأولى فقط.
- ملف `.part` المخفي يُحذف عند فشل صفحة. هذا يشمل حفظ المستند الفردي كـJPG لأنه يستخدم الدالة نفسها.

| الملف | الدوال | التغيير |
| --- | --- | --- |
| `app/src/main/java/com/abfilepro/app/core/ScanProcessor.kt` | `saveSinglePageJpeg` و`saveBatchJpegs` (جديدة) و`rollbackPublishedBatchImages` (جديدة) | حذف ملف `.part` عند الفشل، ونشر الدفعة كاملة أو التراجع عنها |
| `app/src/main/java/com/abfilepro/app/ui/screens/ScanScreen.kt` | فرع `BATCH` + `JPG` في الحفظ | يستدعي `saveBatchJpegs` بدل الحلقة القديمة؛ الأسماء ونتيجة النجاح والبقاء داخل Scanner كما هي |

السلوك عند النجاح لم يتغير: أسماء الملفات `<الاسم>_<رقم الصفحة>_<الوقت>.jpg` نفسها، وBatch يبقى داخل Scanner بعد الحفظ. ولم تتغير وضعيات المسح الأخرى (PDF، المستند، البطاقة، الترجمة).

## الاختبارات الجديدة

الملف: `app/src/androidTest/java/com/abfilepro/app/core/BatchJpegSaveSafetyRegressionTest.kt`. تعمل الاختبارات على ملفات حقيقية داخل مجلد حفظ التطبيق. الجزء المحقون فقط هو نقطة الفشل أو الإلغاء المتعمد.

| الاختبار | ما يثبته |
| --- | --- |
| `successfulBatchPublishesEveryPageInOrder` | 3 صفحات تنتج 3 صور JPG مقروءة بالترتيب، ولا يوجد غيرها |
| `secondPageFailureRemovesOnlyThisAttemptsImages` | فشل حقيقي في الصفحة 2 (صورة تالفة): المجلد يعود كما كان، والملف السابق سليم بنفس البصمة، وصفحات المراجعة لم تتغير |
| `retryAfterFailureDoesNotDuplicatePages` | إعادة المحاولة بعد الفشل تنتج صورة واحدة لكل صفحة فقط |
| `cancellationDuringSaveRemovesPublishedImages` | إلغاء بعد نشر الصفحة 1: لا يبقى أي ناتج جزئي، والملف السابق محفوظ |
| `imageChangedAfterPublishIsKeptOnRollback` | صورة تعدّلت بعد نشرها لا تُحذف عند التراجع |

**تحقق إضافي قبل CI:** نُسخ منطق `saveBatchJpegs` و`rollbackPublishedBatchImages` حرفيًا إلى برنامج JVM مستقل، وجُمّع بـKotlin 2.0.21. نجحت خمس حالات: فشل الصفحة 2، وناتج لا يجتاز التحقق، والنجاح، والملف المعدّل، والإلغاء. هذا تحقق من المنطق فقط، وليس بديلًا عن اختبارات Android.

## الإصدار وأدوات التحقق

- `app/build.gradle.kts`: versionCode 167، وversionName `2.0.0-alpha98-p15-safe-maintenance`، وapplicationIdSuffix `.p15mainttrial`.
- `app/src/debug/AndroidManifest.xml`: اسم التطبيق `AB File Pro P15`.
- `VERSION`.
- `MaintenanceGrantReceiver.java` (ملف اختبار): أُضيف `p15mainttrial` إلى قائمة الحزم التجريبية المسموحة، مع بقاء P13 وP14.
- `tools/p15_ci/`: نسخة من `tools/p14_ci/` موجهة إلى P15، والمتوقع **84 Android** (79 من P14 + 5 جديدة) و**19 Scanner** و**29 JVM**، أي **132**. وتتحقق أيضًا من نجاح اختبارات SCAN-04 الخمسة بأسمائها، ولم يُخفض أي عدد.
- `tools/p15_ci/p15-inputs.sha256`: 248 مدخلًا، منها الـ247 من P14 مع 7 بصمات محدثة، ومدخل جديد لملف الاختبار.
- `.github/workflows/p15-standalone.yml`: يبني ويختبر من Safe Source كاملًا دون تنزيل P10.
- `.github/workflows/p14-standalone.yml`: صار يعمل يدويًا فقط، لأن بصمات P14 لم تعد تطابق المصدر. لم يُحذف.
- `tools/p14_ci/` وكل أدلة P12/P13/P14 محفوظة كما هي.

## التثبيت والتوقيع

Package الإصدار هو `com.abfilepro.app.p15mainttrial`، ويُثبت بجوار P14 والإصدارات السابقة دون حذف أي تطبيق، كما في نمط P13/P14. سيوقّع بشهادة Debug من runner جديد، أي أنه **ليس التوقيع الأصلي** ولا تحديثًا لـP14. لم يُنشأ أي مفتاح بديل.

## ما لم يُختبر أو بقي خارج النطاق

- لم يُختبر الإصدار على هاتف فعلي.
- ملاحظة خارج النطاق: في حفظ Batch كصور، يُرسل للمعرض (MediaScanner) أول صورة فقط، وهو سلوك موجود من قبل. لم يُعدّل، ويُقترح لإصلاح لاحق.
- ملاحظة خارج النطاق: `saveSinglePageJpeg` يحذف ملفًا موجودًا إذا تطابق اسمه بالضبط، شاملًا الاسم والملّي ثانية. الاحتمال ضعيف جدًا، ولم يُعدّل.
- باقي الملاحظات المفتوحة (SCAN-06 وST-08 وAS وFAM وDOC وSEC) وتجهيز Google Play لم تتغير.
