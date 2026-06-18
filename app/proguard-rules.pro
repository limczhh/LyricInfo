-dontwarn io.github.libxposed.annotation.**
-dontwarn androidx.annotation.NonNull
-dontwarn androidx.annotation.Nullable
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class org.luckypray.dexkit.** { *; }
