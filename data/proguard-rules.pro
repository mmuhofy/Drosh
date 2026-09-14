# Drosh - module ProGuard rules for :data.
# Only rules that affect THIS module's release build belong here.
#
# Cross-module consumer rules belong in consumer-rules.pro for this module.

# Hilt-injected classes — only referenced through generated Dagger code,
# so R8 cannot trace reachability. Keep all data module classes in release.
-keep class com.iris.irisshell.data.** { *; }
-dontwarn com.iris.irisshell.data.**
