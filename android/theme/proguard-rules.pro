# Drosh - module ProGuard rules for :design-system.
# Only rules that affect THIS module's release build belong here.
#
# Cross-module consumer rules belong in consumer-rules.pro for this module.

# DroshColors.kt defines top-level Color val properties accessed via static getters
# (DroshColorsKt). R8 full mode strips these as "unused" when they are only
# referenced through Compose @Composable code.
-keep class dev.drosh.design.system.DroshColorsKt { *; }
-keep class dev.drosh.design.system.OutfitFontFamilyKt { *; }
-keep class dev.drosh.design.system.** { *; }
-dontwarn dev.drosh.design.system.**
