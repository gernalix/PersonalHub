# Android manifest components and Room/KSP-generated access paths are retained by the Android
# Gradle plugin. Keep source-compatibility reflection targets explicit for the debug distributor.
-keep class com.gernalix.personalhub.DatabaseActivity { <init>(); }
-keep class com.gernalix.personalhub.DatabaseRestartActivity { <init>(); }
-keep class com.supercontacts.app.MainActivity { <init>(); }
-keep class com.example.multitimetracker.MainActivity { <init>(); }
-keep class com.gernalix.luoghi.MainActivity { <init>(); }
-keep class com.gernalix.sostanze.MainActivity { <init>(); }
-keep class com.wordpulse.app.MainActivity { <init>(); }

# Guava's optional J2ObjC annotations are not packaged for Android and have no runtime role.
-dontwarn com.google.j2objc.annotations.ReflectionSupport
-dontwarn com.google.j2objc.annotations.RetainedWith
