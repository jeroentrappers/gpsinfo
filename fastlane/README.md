fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android bump

```sh
[bundle exec] fastlane android bump
```

Bump versionCode (+ optional versionName) and scaffold changelog files.

  fastlane bump                        # versionCode + 1

  fastlane bump version:2.2.0          # versionCode + 1, set versionName

  fastlane bump code:10 version:2.2.0  # set versionCode explicitly

### android verify

```sh
[bundle exec] fastlane android verify
```

Run unit tests

### android bundle

```sh
[bundle exec] fastlane android bundle
```

Build a signed AAB ready for Play upload

### android screenshots

```sh
[bundle exec] fastlane android screenshots
```

Generate Play Store screenshots across all supported locales.

Requires an Android emulator already running (adb devices).

Output: fastlane/metadata/android/<locale>/images/phoneScreenshots/

### android deploy_internal

```sh
[bundle exec] fastlane android deploy_internal
```

Upload AAB + metadata to internal testing track.

Use this for every release before promoting to production.

### android deploy_production

```sh
[bundle exec] fastlane android deploy_production
```

Promote the current internal release to production.

Runs the standard staged rollout (10% by default).

### android metadata_only

```sh
[bundle exec] fastlane android metadata_only
```

Upload only the listing metadata + screenshots (no AAB).

Useful for translation tweaks between builds.

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
