# Drosh - module ProGuard rules for :core.
# Only rules that affect THIS module's release build belong here.
#
# Cross-module consumer rules belong in consumer-rules.pro for this module.

# Keep all core module classes in release.
-keep class com.iris.irisshell.core.** { *; }
-dontwarn com.iris.irisshell.core.**
