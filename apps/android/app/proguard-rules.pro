# FamilyCircle R8 rules.
# kotlinx.serialization, OkHttp, Room, WorkManager and Navigation ship their own consumer rules.

# Release builds: drop debug logging entirely (Logger.d is the only debug-level entry point).
-assumenosideeffects class org.familycircle.android.util.Logger {
    public final void d(java.lang.String, kotlin.jvm.functions.Function0);
}
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Keep stack traces readable in bug reports without exposing more than class/line info.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
