# DigitorAndroid - keep rules intentionally minimal for the MVP.

# Paddle Lite's prebuilt JNI exports symbols bound to these exact Java class and native method
# names. Keep the package stable in minified release builds.
-keep class com.baidu.paddle.lite.** { *; }
-dontwarn com.baidu.paddle.lite.**
