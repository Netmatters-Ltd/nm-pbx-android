# NM PBX Rebranding Status (AI generated)

This document tracks the progress of rebranding the Linphone Android app to NM PBX according to the brand guidelines in `brand/brand.md`.

## ✅ Completed Tasks

### 1. Font Replacement (Noto Sans → Poppins)

**Status:** ✅ Complete

**Changes Made:**
- Copied Poppins font files from `brand/Poppins/` to `app/src/main/res/font/`:
  - `poppins_light.ttf` (300 weight)
  - `poppins_regular.ttf` (400 weight)
  - `poppins_medium.ttf` (500 weight)
  - `poppins_semi_bold.ttf` (600 weight)
  - `poppins_bold.ttf` (700 weight)
  - `poppins_extra_bold.ttf` (800 weight)

- Created font family XML definitions:
  - `app/src/main/res/font/poppins.xml`
  - `app/src/main/res/font/poppins_300.xml`
  - `app/src/main/res/font/poppins_500.xml`
  - `app/src/main/res/font/poppins_600.xml`
  - `app/src/main/res/font/poppins_700.xml`
  - `app/src/main/res/font/poppins_800.xml`

- Updated all font references in `app/src/main/res/values/styles.xml` to use Poppins instead of Noto Sans

- Updated `app/src/main/java/org/linphone/ui/NotoSansFont.kt` enum to reference Poppins font resources

**Note:** The class is still named `NotoSansFont` for backward compatibility with the codebase. Consider renaming to `PoppinsFont` in a future refactor if needed.

---

### 2. Color Theme Replacement (Orange → NM PBX Green)

**Status:** ✅ Complete

**Changes Made:**
- Updated `app/src/main/res/values/colors.xml` with NM PBX brand colors:
  - `orange_main_100`: `#FFEACB` → `#C8F5D6` (light green tint)
  - `orange_main_100_alpha_50`: `#80FFEACB` → `#80C8F5D6` (50% alpha light green)
  - `orange_main_300`: `#FFB266` → `#77D99A` (medium green)
  - `orange_main_500`: `#FF5E00` → `#25af4b` (primary NM PBX green)
  - `orange_main_700`: `#B72D00` → `#1A7D35` (dark green)

**Note:** Color resource names still contain "orange" for backward compatibility with the codebase. The actual color values now use the NM PBX green palette. The secondary brand color (#0e2826) and gradient end color (#a4cd3a) from the brand guidelines are available but not yet integrated into specific UI elements.

**Automatic Updates:**
These color changes automatically propagate to:
- Splash screen logo (`linphone_splashscreen.xml`)
- Launcher icon backgrounds (`ic_launcher.xml`, `ic_launcher_round.xml`)
- All theme attributes mapped to these colors in `themes.xml`

---

### 3. App Name Update (Linphone → NM PBX)

**Status:** ✅ Complete

**Changes Made:**
- Updated `app/src/main/res/values/strings.xml`:
  - Changed app name entity: `<!ENTITY appName "Linphone">` → `<!ENTITY appName "NM PBX">`
  - This automatically updates the `app_name` string resource used throughout the app

---

### 4. Company Branding Update (Belledonne Communications → Netmatters)

**Status:** ✅ Complete

**Changes Made:**
- Updated `app/src/main/res/values/strings.xml`:
  - Copyright notice: `© Belledonne Communications 2010-2025` → `© Netmatters 2010-2025`
  - Support email: `linphone-android@belledonne-communications.com` → `support@netmatters.co.uk`
  - Contact URL: `https://linphone.org/contact` → `https://www.netmatters.co.uk/contact-us`
  - All website URLs now point to: `https://www.netmatters.co.uk/`
  - Updated URLs for:
    - Download URL
    - User guide URL
    - Privacy policy URL
    - Terms and conditions URL
    - Web platform registration URL
    - Forgotten password URL
    - Translation/Weblate URL
    - Open source licenses URL
    - End-to-end encryption info link

---

## 🔲 Pending Manual Tasks

### 5. Logo and Icon Replacement

**Status:** 🔲 Requires Manual Completion

**What Needs to Be Done:**

#### A. Launcher Icons (High Priority)

The launcher icons are currently Linphone-branded and need to be replaced with NM PBX icons using the SVG files from `brand/` directory.

**Files to Replace:**

1. **Adaptive Icon Foregrounds** - Replace these in ALL density folders:
   - `app/src/main/res/mipmap-mdpi/linphone_launcher_icon_foreground.png`
   - `app/src/main/res/mipmap-hdpi/linphone_launcher_icon_foreground.png`
   - `app/src/main/res/mipmap-xhdpi/linphone_launcher_icon_foreground.png`
   - `app/src/main/res/mipmap-xxhdpi/linphone_launcher_icon_foreground.png`
   - `app/src/main/res/mipmap-xxxhdpi/linphone_launcher_icon_foreground.png`

2. **Round Icons** - Replace these in ALL density folders:
   - `app/src/main/res/mipmap-mdpi/ic_launcher_round.png`
   - `app/src/main/res/mipmap-hdpi/ic_launcher_round.png`
   - `app/src/main/res/mipmap-xhdpi/ic_launcher_round.png`
   - `app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png`
   - `app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png`

3. **Square Icons** - Replace these in ALL density folders:
   - `app/src/main/res/mipmap-mdpi/ic_launcher.png`
   - `app/src/main/res/mipmap-hdpi/ic_launcher.png`
   - `app/src/main/res/mipmap-xhdpi/ic_launcher.png`
   - `app/src/main/res/mipmap-xxhdpi/ic_launcher.png`
   - `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`

**Recommended Approach:**

Use `brand/NMPBX_X_Isolated.svg` (or `NMPBX_X_Isolated_Small.svg` for smaller sizes) as the source. Convert to PNG at the following sizes:

- **mdpi**: 48x48 dp (48px)
- **hdpi**: 72x72 dp (72px)
- **xhdpi**: 96x96 dp (96px)
- **xxhdpi**: 144x144 dp (144px)
- **xxxhdpi**: 192x192 dp (192px)

For adaptive icon foregrounds, use larger canvas (108x108 dp) with proper padding:
- **mdpi**: 108x108 px
- **hdpi**: 162x162 px
- **xhdpi**: 216x216 px
- **xxhdpi**: 324x324 px
- **xxxhdpi**: 432x432 px

**Tools:**
- Use Android Asset Studio: https://romannurik.github.io/AndroidAssetStudio/icons-launcher.html
- Or use Inkscape/ImageMagick to batch convert SVG to PNG at various densities
- Or use Android Studio's Image Asset tool (File → New → Image Asset)

#### B. In-App Logo Drawables (Medium Priority)

Replace the following vector drawable XML files with NM PBX equivalents:

1. **Splash Screen Logo** (High Priority)
   - File: `app/src/main/res/drawable/linphone_splashscreen.xml`
   - Source: Convert `brand/NMPBX_X_Isolated.svg` to Android VectorDrawable XML
   - Note: Currently uses Linphone logo with green color. Needs complete path replacement.

2. **Welcome/Assistant Logos**
   - `app/src/main/res/drawable/welcome_linphone_logo.xml`
   - `app/src/main/res/drawable/assistant_logo.xml`
   - `app/src/main/res/drawable/confirm_sms_code_logo.xml`
   - Source: Use `brand/NMPBX_NMPBX_Main_Logo.svg` or `brand/NMPBX_NMPBX_White_Logo.svg` depending on background

3. **Notification Icon**
   - `app/src/main/res/drawable/linphone_notification.xml`
   - Source: Use `brand/NMPBX_X_Isolated_Small.svg` (simple monochrome version)

4. **Profile/Feature Logos**
   - `app/src/main/res/drawable/chat_ephemeral_logo.xml`
   - `app/src/main/res/drawable/profile_interop_logo.xml`
   - `app/src/main/res/drawable/profile_secure_logo.xml`
   - Decision needed: Replace with NM PBX branding or keep as feature-specific icons?

**Recommended Approach:**

Converting SVG to Android VectorDrawable XML can be done using:
1. Android Studio's Vector Asset tool (File → New → Vector Asset → Local file)
2. Online converters like https://svg2vector.com/
3. Manual conversion for complex gradients (the NM PBX logo uses gradients which may need simplification)

**Note on Gradients:** Android VectorDrawables support gradients but with some limitations. The NM PBX logo uses linear gradients from #25af4b to #a4cd3a which can be represented in Android XML format.

---

### 6. Localized String Files

**Status:** 🔲 Requires Manual Review

**What Needs to Be Done:**

The app has localized string files for multiple languages. While we updated the main `values/strings.xml`, the following language-specific files may contain hardcoded references to "Linphone" or "Belledonne Communications":

- `app/src/main/res/values-ca/strings.xml` (Catalan)
- `app/src/main/res/values-cs/strings.xml` (Czech)
- `app/src/main/res/values-de/strings.xml` (German)
- `app/src/main/res/values-es/strings.xml` (Spanish)
- `app/src/main/res/values-eu/strings.xml` (Basque)
- `app/src/main/res/values-fi/strings.xml` (Finnish)
- `app/src/main/res/values-fr/strings.xml` (French)
- `app/src/main/res/values-hu/strings.xml` (Hungarian)
- `app/src/main/res/values-mk/strings.xml` (Macedonian)
- `app/src/main/res/values-nl/strings.xml` (Dutch)
- `app/src/main/res/values-pl/strings.xml` (Polish)
- `app/src/main/res/values-pt/strings.xml` (Portuguese)
- `app/src/main/res/values-pt-rBR/strings.xml` (Brazilian Portuguese)
- `app/src/main/res/values-ru/strings.xml` (Russian)
- `app/src/main/res/values-sk/strings.xml` (Slovak)
- `app/src/main/res/values-uk/strings.xml` (Ukrainian)
- `app/src/main/res/values-zh-rCN/strings.xml` (Chinese Simplified)

**Recommended Approach:**
1. Search for "Linphone" and "Belledonne" in all `values-*/strings.xml` files
2. Update any hardcoded references to use "NM PBX" and "Netmatters" equivalents
3. For copyright notices, maintain English text or translate appropriately

**Command to Find References:**
```bash
grep -r "Linphone\|Belledonne" app/src/main/res/values-*/strings.xml
```

---

### 7. Package Name and Application ID

**Status:** 🔲 Decision Needed

**Current State:**
- Package name: `org.linphone`
- Defined in `app/build.gradle.kts`

**Considerations:**
- Changing the package name is a BREAKING change that affects:
  - App identification on Google Play Store (would be treated as a new app)
  - Deep links and URI schemes
  - SharedPreferences and data storage paths
  - Background services and notifications
  - Third-party integrations

**Recommendation:**
- **DO NOT CHANGE** unless this is a complete fork/new app deployment
- If you DO need to change it:
  1. Update `packageName` and `namespace` in `app/build.gradle.kts`
  2. Update all package declarations in Kotlin/Java source files
  3. Update AndroidManifest.xml component declarations
  4. Update URI schemes in AndroidManifest.xml if keeping linphone-specific schemes
  5. Consider data migration strategy for existing users

---

### 8. Copyright Headers in Source Files

**Status:** 🔲 Optional

**What It Is:**
Most source files (.kt, .java) contain copyright headers referencing Belledonne Communications:

```kotlin
/*
 * Copyright (c) 2010-2024 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * ...
 */
```

**Considerations:**
- These headers reflect the original authorship and GPL licensing
- Legally, you should preserve original copyright notices when forking GPL code
- You CAN add your own copyright line below the original:
  ```
  * Copyright (c) 2010-2024 Belledonne Communications SARL.
  * Copyright (c) 2025 Netmatters
  ```

**Recommendation:**
- **Preserve original copyright headers** to comply with GPL v3.0 license terms
- Only update if you have made substantial modifications to individual files
- If updating, add Netmatters as a secondary copyright holder, don't replace

---

## 📝 Summary of Changes

### Files Modified:
1. `app/src/main/res/font/` - Added 6 Poppins TTF files and 6 XML family definitions
2. `app/src/main/res/values/styles.xml` - Updated all font family references
3. `app/src/main/res/values/colors.xml` - Updated orange color palette to NM PBX green
4. `app/src/main/res/values/strings.xml` - Updated app name, company info, and URLs
5. `app/src/main/java/org/linphone/ui/NotoSansFont.kt` - Updated font resource references

### Files That Auto-Update Due to Resource Changes:
- `app/src/main/res/drawable/linphone_splashscreen.xml` (uses @color/orange_main_500)
- `app/src/main/res/mipmap-anydpi/ic_launcher.xml` (uses @color/orange_main_500)
- `app/src/main/res/mipmap-anydpi/ic_launcher_round.xml` (uses @color/orange_main_500)
- `app/src/main/res/values/themes.xml` (maps orange colors to theme attributes)

---

## 🚀 Next Steps

### Immediate Actions Required:

1. **Generate Launcher Icons** (CRITICAL)
   - Convert `brand/NMPBX_X_Isolated.svg` to PNG at 5 densities
   - Replace all launcher icon files in mipmap-* folders
   - Test on device to verify icon appears correctly

2. **Convert Splash Screen Logo** (HIGH PRIORITY)
   - Convert `brand/NMPBX_X_Isolated.svg` to Android VectorDrawable XML
   - Replace `app/src/main/res/drawable/linphone_splashscreen.xml`
   - Test app startup to verify logo displays correctly

3. **Review Localized Strings** (MEDIUM PRIORITY)
   - Check all `values-*/strings.xml` for hardcoded "Linphone"/"Belledonne" references
   - Update as needed

4. **Replace In-App Logos** (MEDIUM PRIORITY)
   - Convert `brand/NMPBX_NMPBX_*.svg` logos to VectorDrawable XML
   - Replace welcome/assistant screen logos

### Testing Checklist:

After completing manual steps:

- [ ] App launches with new splash screen logo
- [ ] App icon shows correctly in launcher
- [ ] App name displays as "NM PBX" in launcher and app bar
- [ ] All text uses Poppins font family
- [ ] Primary color is green (#25af4b) throughout the app
- [ ] Contact information shows Netmatters details
- [ ] URLs open correct Netmatters pages
- [ ] Welcome/assistant screens show NM PBX branding
- [ ] Notifications use NM PBX icon
- [ ] About screen shows Netmatters copyright

### Build and Deploy:

1. Clean and rebuild the project:
   ```bash
   ./gradlew clean
   ./gradlew assembleDebug
   ```

2. Test on device:
   ```bash
   ./gradlew installDebug
   ```

3. Check for any resource errors in Android Studio

4. Generate signed release build when ready for distribution

---

## 📞 Contact for Questions

**Brand Assets Location:** `brand/` directory in project root
**Brand Guidelines:** `brand/brand.md`

For technical questions about the rebranding implementation, refer to this document.

---

*Document created: 2026-01-16*
*Last updated: 2026-01-16*
