# AB File Pro - R8 rules for the release/qa builds (P16).
# Keep readable stack traces in Play Console; upload mapping.txt with every release.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# PDFBox-Android: loads fonts/resources and filters by name at runtime.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter

# Tesseract4Android + Leptonica: JNI looks up Java fields/methods by name.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# OpenCV Java bindings are called from native code.
-keep class org.opencv.** { *; }

# Keep every JNI entry point.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Optional desktop/JDK classes referenced by third-party libraries but never used on Android.
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
