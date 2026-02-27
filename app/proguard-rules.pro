-keep class com.gtno.fairer.vpn.** { *; }

# OkHttp — suppress warnings for platform-specific TLS providers that are
# absent on Android (Conscrypt, OpenJSSE). OkHttp's AAR includes consumer
# rules but these suppressions are needed in addition for minified builds.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
