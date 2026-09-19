# دليل تسليم مشروع «المحدِّث» للأندرويد — لإكمال العمل في بيئة أخرى

آخر إصدار: ٠٫١٥٫٢ (رقم البناء ٣١) — «المحدِّث» (org.murabbie.muhaddith) — ١١ سبتمبر ٢٠٢٦. المؤلف: صُهَيب سلام.

## ١) ما في هذا التسليم

| الملف | المحتوى |
|---|---|
| `muhaddith-android-project.tar.gz` | مشروع الأندرويد كاملًا (يتضمن مفتاح التوقيع `app/muhaddith-release.jks`): الشيفرة، الاختبارات، أدوات بناء الحزم (`tools/`)، أدوات البحث الدلالي (`semantic/`)، مفتاح التوقيع، `README.md` (سجلّ الإصدارات) وهذا الدليل |
| `muhaddith-sources.tar.gz.001…` | نصوص المصادر المنزَّلة (OpenITI، وOCR المكتبة الوقفية، وكتب الألباني، وواجهات hadith-api، وفهرس الوقفية `hf_index.tsv`) والنواتج الوسيطة في `ahkam/work/` — تُجمع الأجزاء ثم تُفكّ: `cat muhaddith-sources.tar.gz.* \| tar xz` |
| `muhaddith_*.gz.NNN` | حزم البيانات الجاهزة (المتون التسعة والكاملة، الأحكام، الشروح ٢، النموذج، المتّجهات) — كل جزء ≤ ٢٩ م.ب |
| `manifest.json`، `SHA256SUMS.txt` | بيان الحزم وبصمات كل الملفات |
| `nas_publish.py`، `publish.bat/.sh` | أداة النشر إلى NAS |

غير مضمَّن لكبره وهو ملكك: `clusters.db` (٦٫٧ ج.ب) و`words.db` (٦٤ م.ب) من قاعدة «المحدِّث» الأصلية (كانا على R2 وأُزيلا منه). يلزمان فقط لإعادة بناء حزمة المتون أو فهرس المطابقة.

## ٢) بيئة البناء

- JDK ٢١، Gradle ٨٫١٤٫٣ (الـ wrapper مضمَّن: `./gradlew`)، AGP ٨٫٧٫٣، Kotlin ٢٫٠٫٢١، Compose (Material3)، compileSdk/targetSdk ٣٥، minSdk ٢٦.
- Android SDK: platforms;android-35 وbuild-tools ٣٥ وcmdline-tools. ضع مسار SDK في `local.properties` (`sdk.dir=…`) أو `ANDROID_HOME`.
- Python ٣٫١١ للأدوات؛ يلزم `numpy` لفهرس المطابقة، و`torch`+`onnxruntime` لتصدير النموذج فقط.
- التوقيع: `app/muhaddith-release.jks`، الاسم المستعار `muhaddith`، كلمة المرور (في `keystore.properties` — خارج المستودع) (للمخزن والمفتاح). **لا تغيّر المفتاح**: التحديث من داخل التطبيق يعتمد على ثبات التوقيع واسم الحزمة `org.murabbie.muhaddith`.

```bash
./gradlew :app:testReleaseUnitTest            # ٥٤ اختبارًا
./gradlew :app:assembleRelease                # app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a}-release.apk
```
عند كل إصدار: ارفع `versionCode` و`versionName` في `app/build.gradle.kts`، وسمِّ الملفات `muhaddith-<الإصدار>-arm64.apk` و`-arm32.apk` (أداة النشر تقرأ الاسم بهذا النمط).

## ٣) بنية التطبيق (أهم الملفات)

- `data/MuhaddithDatabase.kt`: فتح المتون + حزمتي الأحكام والشروح (مخطط الأحكام ٢، مخطط الشروح ٥ (مع `lemma` لشرح الألفاظ) مع `kind` و`fine_id` وجدولي `sh_fine` و`sh_point_sources`)؛ `isValid*`/`isOutdated*`.
- `data/DataPackManager.kt`: التنزيل من الخادم (بيان `manifest.json`)، الاستيراد من الهاتف، التثبيت والتوجيه بحسب `meta.dataset`، تنزيل APK التحديث، السجل، بصمة المثبَّت `pack_sha_<id>`.
- `data/PackSource.kt`: مصادر التنزيل: مجلد ويب عادي أو مشاركة سينولوجي (`/sharing/<id>` ← كعكة ← `/fsdownload/<id>/<file>` مع Range).
- `data/PackFetcher.kt`: أجزاء + استئناف + sha256. `data/KnownPacks.kt`: ثوابت الحزم للاستيراد من الهاتف (تُحدَّث آليًّا بـ `pack_db.py`).
- `ui/MuhaddithViewModel.kt`: الحالة كلها؛ طابور التنزيل، `downloadPacks`، `semanticPacks/semanticProblem`، `computeDataUpdates/autoMaintain` (تحديث البيانات)، `checkForUpdate/downloadUpdate/installUpdate` (تحديث التطبيق).
- `ui/DataScreen.kt` (إدارة الحزم وحالة البيانات)، `ui/SettingsScreen.kt` (بطاقة التحديثات، نطاق الكتب، `BookGroups`)، `ui/ShuruhScreens.kt` (الاستنباطات والآثار والمصادر المرقَّمة)، `export/DocExport.kt` (Word/PDF).
- الافتراضي لمصدر التنزيل: `DataPackManager.NAS_BASE_URL` = رابط مشاركة ملف البيان على NAS (`http://nas.fitrahmedia.nl:5000/sharing/4ojRClfLk/`).

## ٤) بناء الحزم

كل الأوامر من مجلد `tools/ahkam` (المسارات كما كانت في بيئة البناء: `/home/claude/ahkam` للمصادر و`/home/claude/muhaddith-data` للنواتج؛ عدّلها في `run_stage*.sh` و`expand_shuruh.sh`).

1. المتون: `python3 tools/build_corpus.py --src clusters.db --words words.db --out muhaddith_corpus_full.db` (و`--max-priority 8 --dataset tisa` للتسعة). التقسيم: `gzip -9 -n` ثم `split -b 29m -d -a 3 --numeric-suffixes=1`، وحدّث `KnownPacks.kt` و`manifest.json` (الحجم والبصمة والأجزاء).
2. فهرس المطابقة (مرة واحدة بعد أي بناء للمتون): `index_corpus.py --src clusters.db --out work` → `sh_hash.npy`/`sh_hid.npy` (غير مضمَّنين لكبرهما؛ يُعاد بناؤهما في دقائق).
3. الأحكام: `run_stage4.sh` (استخراج أحكام المتون + بلوغ المرام + hadith-api + الألباني + بناء `muhaddith_ahkam.db`) ثم `pack_ahkam.py`.
4. الشروح: أولًا (مرة بعد كل بناء للمتون) `fine_clusters.py --corpus muhaddith_corpus_full.db --out work/fine.tsv` (المجموعات الدقيقة للعناقيد الضخمة، ≈ دقيقتان)، ثم `shuruh.py --data openiti/data --ocr shuruh_ocr --work work --corpus muhaddith_corpus_full.db --out muhaddith_shuruh.db` ثم `pack_db.py --db muhaddith_shuruh.db --id shuruh --const SHURUH --name "…" --out parts --manifest manifest.json --known …/KnownPacks.kt`. (≈ ٤ دقائق)
5. البحث الدلالي: `semantic/export_bge.py` (تصدير BGE-M3 إلى ONNX int8 بمفردات مقلَّمة)، `semantic/convert_bge_vectors.py` أو `embed_corpus.py` (المتّجهات)، `semantic/pack_semantic.py` (حاوية MSBP ومتّجهات MSBV v2 + التقسيم وتحديث الثوابت). المتّجهات مرتبطة بمعرّفات `hadiths.id` في حزمة المتون؛ إعادة بناء المتون لا تغيّر الترتيب إن كان `clusters.db` نفسه.

قاعدة ثابتة: الأجزاء ≤ ٢٩ م.ب، والأسماء بنمط `muhaddith_<id>.(db|bin).gz.NNN` (يتعرّفها `KnownPacks` والتطبيق).

## ٥) النشر على NAS والتحديث عند المستخدمين

`python3 tools/nas_publish.py --dir <مجلد الملفات> --notes "ما الجديد"` يرفع ما تغيّر إلى `/downloads/muhaddith`، وينشئ رابط مشاركة لكل ملف، ويكتب `manifest.json` بعناوين الأجزاء الكاملة وكتلة `app` (إصدار التطبيق وروابط APK وبصماتها)، ويرفعه. التطبيق يفحص البيان عند كل تشغيل: إصدار أحدث ← بطاقة تحديث التطبيق؛ حزمة أحكام/شروح ناقصة أو أقدم (بتاريخ `built`) ← تُنزَّل تلقائيًّا. بيانات NAS في `nas_publish.py`: الاتصال الافتراضي بواجهة DSM عبر `https://files.murabbie.org/webapi` (شهادة موثوقة، بلا حدّ للحجم)، والبديل `NAS_SCHEME=http NAS_HOST=nas.fitrahmedia.nl:5000`؛ عناوين الأجزاء في البيان تبقى على `http://nas.fitrahmedia.nl:5000` (غيّرها بـ `NAS_PUBLIC=https://files.murabbie.org` إن أردت TLS للمستخدمين — روابط المشاركة نفسها تعمل على المضيفين). ملاحظة: جلسة المشاركة (`sharing_sid`) مقيّدة بعنوان IP العميل، فمن بيئة تتبدّل عناوين خروجها تفشل بعض التنزيلات بـ ٤٠٤ — ليست مشكلة على الهاتف. لا تُلغِ مشاركة المجلد `ehniFEwJg`.

ثبت بالتجربة على DSM ٧: مشاركة المجلد لا تصلح للتنزيل الآلي (خطأ ١٥٠/٤٠٧)، ومشاركة الملف الواحد تعمل (كعكة `sharing_sid` لكل مشاركة، ثم `/fsdownload/<id>/<name>` بدعم Range).

## ٦) ما بقي (بالترتيب المقترح)

1. ~~رفع حزمة الشروح ٢ والتطبيق ٠٫١٠٫١ إلى NAS~~ — نُفِّذ في ١١ سبتمبر ٢٠٢٦ عبر البوابة `https://files.murabbie.org/webapi` (٦٥ ملفًا، بيان ٠٫١٠٫١، وحُذفت ملفات ٠٫٩٫٠). أداة النشر تقارن MD5 للملفات المتساوية الحجم (أجزاء الحزم كلها ٢٩ م.ب بالضبط) فلا تُخطئ التخطّي.
2. ~~توسيع الشروح بالمكتبة الوقفية~~ (نُفِّذ في ٠٫١٠٫٣: ١٣٥ كتابًا): `python3 tools/ahkam/fetch_hf.py --index ahkam/hf_index.tsv --out ahkam/shuruh_ocr` (١٠٢ كتاب معرَّفة في `HF_BOOKS`: المصنّفان، وكتب الآثار، وكبار الشروح) ثم `expand_shuruh.sh`. `shuruh.py` يلتقط الكتب من `shuruh_ocr/hf_books.json` تلقائيًّا؛ الآثار تُستخرج من كتب النوع `athar` بصيغة الرواية (`ATHAR_RE` + جدول `EARLY_NAMES`).
3. تحديث `NAS_BASE_URL` إن غُيّرت مشاركة البيان، أو إعداد بوابة HTTPS (Web Station) كما في تعليمات NAS.
4. تحسينات معروفة: التصنيف الموضوعي للاستنباطات آلي تقريبي؛ OCR الشروح المعاصرة فيه أخطاء؛ ابن الصلاح والمزي بلا أحكام مفردة؛ حواشي المشكاة والظلال للألباني غير مربوطة.

## ٧) الاختبار الفعلي على الهاتف

لم يُختبر على هاتف حقيقي: تنزيل الحزم من NAS من داخل التطبيق، ونافذة مثبّت التحديث. جرّبهما أولًا، وإن ظهر خطأ فسجلّ «التنزيل والتثبيت» في إدارة الحزم يحمل نصّه.

## ٨) النشر على Google Play

### البناء
المشروع فيه نكهتان (`productFlavors`، البُعد `dist`):
- `nas`: التوزيع المباشر الحالي — تحديث ذاتي بتنزيل APK من NAS، وخيار «الوصول إلى كل الملفات» لفحص مجلدات الهاتف. `./gradlew :app:assembleNasRelease` → `app/build/outputs/apk/nas/release/app-nas-{arm64-v8a,armeabi-v7a}-release.apk`.
- `play`: نكهة المتجر — بلا `REQUEST_INSTALL_PACKAGES` ولا `MANAGE_EXTERNAL_STORAGE` (يحظرهما/يقيّدهما المتجر؛ محذوفتان في `app/src/play/AndroidManifest.xml`)، وشيفرة التحديث الذاتي معطَّلة بـ `BuildConfig.SELF_UPDATE=false` (تحديثات التطبيق من المتجر)، والاستيراد من الهاتف يبقى عبر منتقي المجلدات (SAF). `./gradlew :app:bundlePlayRelease` → `app/build/outputs/bundle/playRelease/app-play-release.aab` (موقَّع بمفتاح المشروع).
- الاختبارات: `./gradlew :app:testNasReleaseUnitTest`.

### التوقيع
- المفتاح: `app/muhaddith-release.jks` (PKCS12)، الاسم المستعار `muhaddith`، كلمة المرور للمخزن والمفتاح (في `keystore.properties` — خارج المستودع). **احفظه ولا تفقده**: هو مفتاح الرفع (Upload key). عند إنشاء التطبيق في Play Console اختر «Play App Signing» (يُنشئ Google مفتاح التوقيع النهائي ويبقى هذا مفتاح الرفع)؛ وإن فُقد مفتاح الرفع يمكن طلب إعادة تعيينه من Google.
- اسم الحزمة `org.murabbie.muhaddith` لا يتغيّر بعد أول رفع أبدًا.

### خطوات الرفع (Play Console)
1. أنشئ التطبيق: الاسم «المحدِّث»، اللغة الافتراضية العربية، تطبيق مجاني.
2. **App content**: سياسة الخصوصية (ارفع `play-store/privacy-policy.html` على https://murabbie.org وضع الرابط)، الإعلانات: لا، الوصول: بلا تسجيل، تقييم المحتوى (استبيان مرجع/تعليم)، الجمهور المستهدف، أمان البيانات: لا تُجمع بيانات (تفصيلها في `play-store/data-safety.md`).
3. **Store listing**: النصوص في `play-store/store-listing.md`، الأيقونة `icon-512.png`، الرسم الترويجي `feature-graphic-1024x500.png`، ولقطات شاشة من الهاتف (اثنتان فأكثر).
4. **Release**: Production (أو Internal testing أولًا) → ارفع `app-play-release.aab` → ملاحظات الإصدار → مراجعة → نشر. أول مراجعة قد تستغرق أيامًا.
5. كل إصدار لاحق: ارفع `versionCode` (لا بدّ أن يزيد) و`versionName` في `app/build.gradle.kts`، ثم `bundlePlayRelease` وارفع الـ AAB.

### ما ينبغي الانتباه له في المتجر
- حجم الحزم: الـ AAB ≈ ٤٣ م.ب (يحوي مكتبة ONNX Runtime وقاعدة البداية الصغيرة)؛ البيانات الكبيرة تُنزَّل من داخل التطبيق من خادم المطوّر (NAS)، وهذا جائز في المتجر ما دام لا يُنزَّل كودٌ تنفيذي. خادم البيانات: `http://nas.fitrahmedia.nl:5000/…` — يُستحسن نقله إلى HTTPS (`https://files.murabbie.org/sharing/<id>`، وروابط المشاركة تعمل عليه) أو إلى استضافة ثابتة، وتغيير `NAS_BASE_URL` في `DataPackManager.kt`؛ التطبيق يدعم HTTP وHTTPS معًا.
- targetSdk ٣٥ يوافق متطلّب المتجر الحالي؛ راجع المتطلّب السنوي (يرتفع كل أغسطس).
- إذن الإشعارات (`POST_NOTIFICATIONS`) والخدمة الأمامية من نوع `dataSync`: مسموحان لتنزيل البيانات؛ قد يطلب المتجر وصفًا موجزًا لسبب الخدمة الأمامية عند الرفع — السبب: إتمام تنزيل حزم البيانات الكبيرة في الخلفية.
- لا إعلانات ولا مشتريات ولا تسجيل دخول، فلا يلزم أكثر من ذلك.
