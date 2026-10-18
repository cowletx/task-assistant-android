# 混淆规则。当前 debug/release 都关闭了 minify，这里保留最小必要规则，
# 避免以后打开混淆时把 Room / Compose 需要的成员裁掉。
#
# Room 生成代码通过反射访问实体与 DAO，需保留其成员名。
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Compose 运行时依赖函数名与元数据
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# Kotlin 协程
-dontwarn kotlinx.coroutines.**

# 本项目的数据模型（Room 实体 + core 领域模型）保持可读，便于排查
-keep class com.example.taskassistant.data.db.** { *; }
-keep class com.example.taskassistant.core.** { *; }
