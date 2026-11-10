# MelKit 0.1.14 build compatibility — 10 November 2026

The user supplied the signed GitLab build log for `063cb9cc`. Original signing
certificate verification, all 483 Java checks, Firebase preparation and Google
Services processing succeeded. Compilation then failed in `compileReleaseKotlin`:
Google Mobile Ads `25.5.0` contains Kotlin metadata `2.3.0`, but the project's
Kotlin compiler `2.1.21` expects metadata `2.1.0`.

## Aligned build tools

| Component | Previous | Prepared retry |
| --- | --- | --- |
| Kotlin Android plugin | 2.1.21 | 2.3.10 |
| Kotlin Compose compiler plugin | 2.1.21 | 2.3.10 |
| Android Gradle plugin | 8.11.1 | 8.13.2 |
| Bundled R8 | From the previous AGP | 8.13.19 |
| Gradle wrapper / runner | 8.14.3 | 8.14.3 |
| Java / bytecode target | 17 | 17 |

Kotlin and its Compose compiler plugin use the same version. AGP 8.13.2 includes
R8 8.13.19 with Kotlin 2.3 support, which is needed by this minified Release
build in addition to updating the compiler. Gradle 8.14.3 meets AGP's 8.13 minimum;
the existing JDK 17 runner remains appropriate. Metadata checks and release
minification remain enabled.

AdMob/UMP versions, app ID, namespace, SDK levels, original certificate check,
Firebase secret restoration, ad placements, frequency limits and test-serving
default are unchanged. The prepared retry also includes the reviewed picker
fix: selecting files updates a count, while a separate scrollable dialog
provides review/removal instead of pushing the source list down. The subsequent
notification feedback is also included: transfer percentage and file index/count
are explicit localized content and expanded text, instead of relying on the
system progress bar to print a number. Initial progress and phase transitions
refresh the notification immediately; regular updates remain throttled to one
per second. Cancellation and foreground-service lifetime are unchanged.

## Verification boundary

The dependency versions and compatibility were checked against vendor release
notes and published artifacts. Source syntax, resources, translation placeholders
and Git diff checks are validation of the prepared source, not a successful
Android compilation. Android build execution remains in GitLab only. A passing
Gradle build, certificate-verified APK and device tests are still required.

## Primary references

- https://kotlinlang.org/docs/gradle-configure-project.html
- https://kotlinlang.org/docs/compose-compiler-migration-guide.html
- https://developer.android.com/build/releases/agp-8-13-0-release-notes
- https://developer.android.com/build/kotlin-support
