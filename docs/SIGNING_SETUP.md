# إعداد التوقيع من الهاتف

مفتاح MelKit الأصلي أُنشئ مرة واحدة. البكج: `com.spinel.melkit`.
بصمة الشهادة العامة مثبتة في [signing-certificate.sha256](signing-certificate.sha256).

## سر واحد في الخدمتين

1. افتح الحزمة الخاصة `MelKit-signing-backup.zip` التي سلّمها المساعد.
2. افتح `MelKit-signing/MELKIT_SIGNING_BUNDLE.txt` وانسخ السطر كاملًا.
3. في GitHub، افتح مستودع MelKit ثم **Settings → Secrets and variables → Actions → New repository secret**.
   - **Name:** `MELKIT_SIGNING_BUNDLE`
   - **Secret:** السطر المنسوخ.
4. في GitLab، افتح مشروع melkit ثم **Settings → CI/CD → Variables → Add variable**.
   - **Type:** Variable.
   - **Key:** `MELKIT_SIGNING_BUNDLE`.
   - **Value:** السطر نفسه.
   - استخدم Masked، وأوقف Expand variable reference.
   - إن اخترت Protected، يجب أن يكون فرع `main` محميًا ليستطيع قراءته.
5. شغّل pipeline على `main` في GitLab. أو يُرسل تعديل تالٍ من GitHub بعد إضافة السر ليبدأ البناء تلقائيًا.

سر GitHub هو نسخة احتياطية للقيمة؛ بناء Android يستخدم سر GitLab.
سر `GITLAB_TOKEN` الذي أضفته سابقًا مخصص للمزامنة، وهو مختلف عن سر توقيع APK.

## ما تحتويه النسخة الاحتياطية

- ملف JKS الأصلي المشفر بكلمات المرور.
- ملف `signing-details.json` الذي يحتوي كلمات المرور واسم alias.
- ملف `MELKIT_SIGNING_BUNDLE.txt` الذي يجمع القيم اللازمة للبناء.

هذه الحزمة خاصة ولا تُرفع إلى مستودع المصدر أو مرفقات البناء العامة.
ترميز Base64 داخل ملف السر ليس تشفيرًا. تحفظ الخدمتان القيمة ضمن الأسرار.
لا تُعد توليد المفتاح عند فشل البناء؛ أصلح الإعدادات أو استرجع هذه النسخة.

## تحقق البناء

`ci/build-android.sh` يستخرج أعضاء محددين من الحزمة إلى مجلد مؤقت دون تنفيذ محتواها،
ويتحقق من البكج وبصمة شهادة المفتاح، ثم يتحقق من شهادة APK وبكجه وversionCode بعد البناء.
يُمسح المجلد المؤقت عند الخروج، ولا يدخل ضمن Artifacts أو cache.
البصمة العامة في المستودع هي المرجع؛ تغيير السر إلى مفتاح آخر يوقف البناء.

خلال P0 تُختبر نسختان بالمفتاح نفسه للتأكد من التثبيت فوق النسخة السابقة وحفظ اللغة المختارة.
