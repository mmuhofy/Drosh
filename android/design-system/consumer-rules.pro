# Drosh - consumer ProGuard rules for :design-system.
# These rules are applied automatically to any module that depends on :design-system.
#
# Compose ships its own consumer rules. Nothing module-specific to add yet.

# DroshColors.kt defines top-level Color val properties accessed via static getters
# (DroshColorsKt). R8 full mode strips these as "unused" when they are only
# referenced through Compose @Composable code.
-keep class dev.drosh.design.system.DroshColorsKt { *; }
