# Drosh - consumer ProGuard rules for :ssh.
# These rules are applied automatically to any module that depends on :ssh.
#
# Keep rules will be added per-phase as SSHJ surface is exercised at runtime.
# BouncyCastle reflection-bound classes are kept at the app level (see app/proguard-rules.pro).
