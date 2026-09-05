# DigitorAndroid - keep rules intentionally minimal for the MVP.

# Paddle Lite's Java surface is bound to libpaddle_lite_jni by exact class/method signatures.
-keep class com.baidu.paddle.lite.** { *; }
