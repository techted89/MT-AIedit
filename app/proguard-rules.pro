# MT Plugin SDK official template default rules
-keepattributes SourceFile,LineNumberTable,*Annotation*

# Keep plugin public interface classes (framework instantiates via reflection)
-keep class com.mtplugin.aieditor.AiEditToolMenu { *; }
-keep class com.mtplugin.aieditor.settings.AiEditPreference { *; }

# OkHttp (built into SDK, conservatively keep)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# bin.mt.json (built into SDK JSON library)
-dontwarn bin.mt.json.**
-keep class bin.mt.json.** { *; }