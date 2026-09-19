حزمة تسليم تطبيق «المحدِّث» ٠٫١٥٫٢ (رمز الإصدار ٣١) للمبرمج — ٢٠٢٦-٠٩-١٤

الملفات:
01  muhaddith-handover-01-project.zip        مشروع أندرويد كاملًا (muhaddith-android-project.tar.gz) + مفتاح التوقيع muhaddith-release.jks + HANDOVER.md + README.md + play-store/ + tools/ + manifest.json + SHA256SUMS-nas.txt
02  muhaddith-handover-02-aab-1of2.zip / -2of2.zip   ملف Android App Bundle للمتجر مقسومًا جزأين
03  muhaddith-handover-03-apk-arm64.zip      APK للهواتف ٦٤ بت (نكهة nas)
04  muhaddith-handover-04-apk-arm32.zip      APK للهواتف ٣٢ بت (نكهة nas)
05  muhaddith-handover-05-sources-01of17.zip … 17of17.zip   نصوص المصادر والنواتج الوسيطة لبناء حزم الأحكام والشروح (مجلد ahkam/)

الضمّ بعد فكّ كل zip:
  AAB:      cat muhaddith-0.15.2-play.aab.000 muhaddith-0.15.2-play.aab.001 > muhaddith-0.15.2-play.aab
            (ويندوز: copy /b muhaddith-0.15.2-play.aab.000+muhaddith-0.15.2-play.aab.001 muhaddith-0.15.2-play.aab)
            SHA-256: 7118b768a4e185b2224e26f0bc31615ca9dc910612b9d4b440ca44ae16d627bc
  المصادر:  cat muhaddith-sources.tar.gz.* | tar xz      → يُنشئ مجلد ahkam/ (يوضع بجوار مجلد المشروع، كما يشرح HANDOVER.md §٤)
  المشروع:  tar xzf muhaddith-android-project.tar.gz    → مجلد muhaddith-android/

مفتاح التوقيع: app/muhaddith-release.jks — الاسم المستعار muhaddith — كلمة مرور المخزن والمفتاح (في keystore.properties — خارج المستودع). سرّي: لا يُرفع إلى أي مكان عام.

حزم البيانات (٢٫٣ ج.ب: المتون والأحكام والشروح والنموذج الدلالي) ليست هنا؛ هي على الخادم:
  http://nas.fitrahmedia.nl:5000/sharing/ehniFEwJg → muhaddith/   (البيان: http://nas.fitrahmedia.nl:5000/sharing/4ojRClfLk/)
  ونسخة عامة من ملفات التطوير (بلا مفتاح التوقيع) في muhaddith/dev.

ابدأ بقراءة HANDOVER.md، وفيه فقرة ٨ عن النشر على Google Play.
