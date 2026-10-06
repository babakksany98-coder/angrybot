# ساخت APK بدون Android Studio

این پروژه برای ساخت APK با GitHub Actions آماده شده است.

## مراحل

1. در GitHub یک Repository جدید بساز.
2. **محتویات همین پوشه** را داخل Repository آپلود کن؛ یعنی فایل‌هایی مثل `settings.gradle` و پوشه `app` باید در ریشه Repository باشند.
3. وارد تب **Actions** شو.
4. Workflow با نام **Build AngryBot APK** را انتخاب کن.
5. روی **Run workflow** بزن.
6. بعد از تمام شدن Build، وارد اجرای موفق Workflow شو.
7. در بخش **Artifacts** فایل `AngryBot-debug-apk` را دانلود کن.
8. داخل فایل دانلودشده، `app-debug.apk` قرار دارد و روی گوشی قابل نصب است.

## Gemini

اگر Gemini را می‌خواهی، در Repository از مسیر:
`Settings > Secrets and variables > Actions`
یک Secret با نام `GEMINI_API_KEY` بساز و کلید Gemini را داخل آن قرار بده.

اگر Secret را نگذاری، APK همچنان ساخته می‌شود و فقط Gemini Assist در برنامه API Key نخواهد داشت.

## نکته

این نسخه برای Android 13 به بالا تنظیم شده است (`minSdk 30`).
