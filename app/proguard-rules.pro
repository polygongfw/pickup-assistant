# 保留 org.json（MTOP 响应解析靠它）
-keep class org.json.** { *; }

# WorkManager 反射实例化 Worker
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.coldbrew.pickupassistant.work.** { *; }

# ViewBinding 生成的类
-keep class com.coldbrew.pickupassistant.databinding.** { *; }

-dontwarn org.jetbrains.annotations.**
