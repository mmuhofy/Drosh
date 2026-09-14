# Drosh - consumer ProGuard rules for :data.
# These rules are applied automatically to any module that depends on :data.
#
# Keep rules will be added per-phase as libraries are integrated into this module.
# Phase-relevant libraries here: Room, DataStore, OkHttp, SSHJ, Security Crypto.

# Hilt-injected classes in :data — R8 full mode strips them when they are
# only referenced through Hilt-generated code.
-keep class dev.drosh.data.** { *; }
-keep class * extends dev.drosh.domain.** { *; }
-dontwarn dev.drosh.data.**

# Security Crypto -> Tink pulls com.google.errorprone.annotations (CanIgnoreReturnValue)
# referenced transitively from com.google.crypto.tink.KeysetManager.
# These annotations are not shipped as runtime classes; suppress R8 missing-class errors.
-dontwarn com.google.errorprone.annotations.**
