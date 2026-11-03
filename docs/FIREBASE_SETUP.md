# ربط DUGY بخادم Firebase

المراسلة في DUGY تحتاج إلى خادم يمرّر الرسائل المشفّرة بين الهواتف. نستخدم Firebase المجاني من Google.
الخادم لا يرى نص الرسائل؛ يرى أسماء المستخدمين ومن يراسل من ومتى.

## الخطوات (مرة واحدة)
1. افتح https://console.firebase.google.com وسجّل الدخول بحساب Google.
2. أنشئ مشروعًا جديدًا باسم `dugy`، وعطّل Google Analytics، ثم أكمل الإنشاء.
3. من القائمة: **Build ← Authentication ← Get started ← Sign-in method**، ثم فعّل **Email/Password** (الخيار الأول فقط) واحفظ.
4. من القائمة: **Build ← Firestore Database ← Create database**، واختر موقعًا قريبًا، ثم **Start in production mode**.
5. في Firestore افتح تبويب **Rules**، واحذف كل ما فيه، والصق محتوى الملف `firebase/firestore.rules` من هذا المستودع، ثم **Publish**.
6. من ⚙️ **Project settings ← Your apps** اضغط أيقونة Android:
   - Android package name: `app.dugy.messenger`
   - اضغط **Register app** ثم **Download google-services.json**.
7. ضع الملف في المستودع داخل المجلد `app/` باسم `google-services.json` تمامًا.

بعد ذلك يبني GitHub نسخة جديدة تعمل فيها المراسلة تلقائيًا.

## ملاحظات
- محتوى `google-services.json` ليس سرًا؛ الحماية الفعلية في قواعد Firestore (الخطوة 5). لا تتخطَّ تلك الخطوة.
- الخطة المجانية (Spark) تكفي للتجربة والاستخدام المحدود.
- لا يحتاج التطبيق إلى Cloud Functions ولا إلى خطة مدفوعة.
