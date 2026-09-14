# Drosh - module ProGuard rules for :agent.
# Only rules that affect THIS module's release build belong here.
#
# Cross-module consumer rules belong in consumer-rules.pro for this module.

# Hilt-injected classes — only referenced through generated Dagger code,
# so R8 cannot trace reachability. Keep all agent module classes in release.
-keep class dev.drosh.agent.** { *; }
-dontwarn dev.drosh.agent.**
