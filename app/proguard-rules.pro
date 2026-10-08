# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# OpenCV 原生库保留规则（release 未开启 minify，可留空；开启时需保留 native 方法）
-keep class org.opencv.** { *; }
