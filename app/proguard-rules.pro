# HJTK R8 규칙 — 보수적으로: 앱 코드는 이름만 줄이지 않고 그대로 두고(크래시 로그 판독 + JS 브리지),
# 라이브러리의 죽은 코드만 걷어낸다.

# 앱 코드: WebView JS 브리지(@JavascriptInterface), WorkManager Worker 생성자, 매니페스트 컴포넌트가 이름으로 찾힌다
-keep class com.ht.hjtk.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes JavascriptInterface, *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# JavaMail(android-mail): 프로토콜·핸들러를 mailcap/javamail.providers 파일에서 이름으로 불러온다
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-keep class org.apache.harmony.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn javax.naming.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**
-dontwarn org.apache.harmony.**

# WorkManager: Worker는 클래스 이름으로 다시 만들어진다
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
