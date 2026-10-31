# Keep Pindrop's own code and the QR scanner exactly as they are.
-keep class com.pindrop.app.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.**
