# Drosh - module ProGuard rules for :terminal.
# Only rules that affect THIS module's release build belong here.
#
# Cross-module consumer rules belong in consumer-rules.pro for this module.

# Hilt-injected classes — only referenced through generated Dagger code,
# so R8 cannot trace reachability. Keep all terminal module classes in release.
-keep class dev.drosh.terminal.** { *; }
-keep class com.termux.** { *; }
-dontwarn dev.drosh.terminal.**
-dontwarn com.termux.**
