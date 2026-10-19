# Drosh - consumer ProGuard rules for :terminal.
# These rules are applied automatically to any module that depends on :terminal.
#
# Keep rules added per-phase as the termux-view JNI surface is finalized.
# Native methods on termux-view classes must be kept (see app/proguard-rules.pro).
