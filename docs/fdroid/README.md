# F-Droid submission

GPSinfo's source-available + AGPL-3.0 posture qualifies it for the
[F-Droid](https://f-droid.org) catalogue. This folder documents the
submission and keeps the metadata YAML versioned alongside the rest
of the project.

## Files

- [`be.appmire.gpsinfo.yml`](be.appmire.gpsinfo.yml) — F-Droid
  metadata, to be copied into `fdroiddata/metadata/` when filing the
  catalogue merge request.

## Submitting

1. **Tag the release.** F-Droid pulls from a git tag — the YAML's
   `Builds.commit: v2.0.0` references it. Run:

   ```sh
   git tag -s v2.0.0 -m "First public dual-licensed release"
   git push origin v2.0.0
   ```

2. **Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata)** on
   gitlab.com and clone your fork.

3. **Copy this YAML** to `metadata/be.appmire.gpsinfo.yml` in the
   fork. Make any edits that the F-Droid linter requests
   (`fdroid lint metadata/be.appmire.gpsinfo.yml`).

4. **Build locally** to verify the recipe works on F-Droid's build
   environment:

   ```sh
   fdroid build --verbose --on-server be.appmire.gpsinfo
   ```

5. **Open a merge request** against `fdroid/fdroiddata`. The
   maintainers usually respond within 1–4 weeks. Be ready to answer
   questions about:

   - the AGPL licence + dual-licensing setup (see `LICENSE` and
     `COMMERCIAL-LICENSE.md`)
   - the `NonFreeAddOn` anti-feature declaration for Android Auto
   - reproducible-build behaviour

6. **On merge**, F-Droid's build farm picks up the metadata, fetches
   the tag, builds, signs with the F-Droid key, and publishes. Users
   on F-Droid will get auto-updates whenever `UpdateCheckMode: Tags`
   sees a new `vX.Y.Z` tag.

## Signing

F-Droid signs APKs with its own key; the resulting APK is **not
upgradable from** a Play-Store-signed APK of the same app
(applicationId is the same, but signatures differ). Users have to
pick a lane:

- **Play Store version** — signed by Appmire; updates from Play.
- **F-Droid version** — signed by F-Droid; updates from F-Droid.

Switching requires an uninstall + reinstall (which wipes the app's
on-disk state — preferences, trails, paired HR monitor). This is
upstream behaviour we can't change short of going through F-Droid's
[reproducible-build verification](https://f-droid.org/docs/Reproducible_Builds/)
programme, which lets F-Droid use Appmire's signing key instead of
its own after byte-identical verification. That's a follow-on once
the catalogue listing is established.

## Anti-features

We declare exactly one: `NonFreeAddOn` for Android Auto. Everything
else passes F-Droid's hygiene checks cleanly — no analytics, no
trackers, no Play Services, no proprietary SDKs in the dependency
tree.

## Per-release changelogs

F-Droid reads release notes from `fastlane/metadata/android/<locale>/
changelogs/<versionCode>.txt`. We already maintain release notes in
12 locales under `docs/play-store/<locale>/release-notes.txt`. When
publishing, copy each to:

```
fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt
```

The Play Store locale codes match F-Droid's. Same content; F-Droid
just looks in a different place.
