# Photo Sweeper

אפליקציית אנדרואיד לניקוי הגלריה בסגנון טינדר: החלקה ימינה שומרת, שמאלה מעבירה לפח. הפח מתרוקן (מחיקה אמיתית מהטלפון) רק אחרי אישור, בדיאלוג אחד של המערכת לכל הקבוצה.

## מבנה
- `www/index.html` — כל ממשק האפליקציה (HTML/CSS/JS אחד, בלי bundler).
- `android/app/src/main/java/com/pinchosku/photosweeper/GalleryPlugin.java` — הפלאגין הנייטיבי: קריאת הגלריה דרך MediaStore, רשימת אלבומים, ומחיקה דרך `MediaStore.createDeleteRequest`.
- `android/` — פרויקט Android Studio מלא (נוצר ע"י Capacitor).

## בנייה (פעם ראשונה)
1. התקן [Android Studio](https://developer.android.com/studio) ו-Node.js 18+.
2. בתיקיית הפרויקט:
   ```bash
   npm install
   npx cap sync android
   npx cap open android
   ```
3. ב-Android Studio: חבר טלפון עם USB debugging (או פתח אמולטור) ולחץ Run ▶.
4. ל-APK להתקנה ידנית: Build → Build Bundle(s) / APK(s) → Build APK(s). הקובץ יושב ב-`android/app/build/outputs/apk/debug/app-debug.apk`.

## אחרי כל שינוי ב-www
```bash
npx cap sync android
```
ואז Run מחדש ב-Android Studio.

## בדיקה מהירה בדפדפן
אפשר לפתוח את `www/index.html` ישירות בדפדפן. במצב הזה האפליקציה עובדת עם "בחירת קבצים" ומחיקה אמיתית מושבתת (הדפדפן לא מאפשר).

## iOS
אותו קוד ווב מתאים גם ל-iOS (`npx cap add ios`), אבל צריך לכתוב פלאגין מקביל ב-Swift עם PhotoKit (`PHAssetChangeRequest.deleteAssets`), חשבון מפתח של Apple ומק לבנייה.

## הרשאות
- Android 13+: `READ_MEDIA_IMAGES`
- Android 10–12: `READ_EXTERNAL_STORAGE`
- Android 9 ומטה: גם `WRITE_EXTERNAL_STORAGE`
מחיקה ב-Android 11+ עוברת דרך דיאלוג אישור של המערכת (אין דרך לעקוף אותו, וזה טוב).

## לבנות APK בלי להתקין כלום (GitHub Actions)
1. צור ריפו חדש ב-GitHub והעלה אליו את תיקיית הפרויקט (בלי `node_modules`).
2. בלשונית **Actions** ← "Build Android APK" ← Run workflow (או פשוט דחוף commit).
3. אחרי כ-5 דקות, בתחתית הריצה יש **Artifacts** ← `PhotoSweeper-apk`. הורד, חלץ את `app-debug.apk`, שלח לטלפון (וואטסאפ/דרייב) והתקן.
בהתקנה הראשונה אנדרואיד יבקש לאשר "התקנה ממקורות לא ידועים" — זה תקין ל-APK שבנית בעצמך.
