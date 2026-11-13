# Danbooru Asset Casino

Android app: scan your unposted Danbooru uploads (media assets), then let the casino pick one at random
to upload or share. Needs a Danbooru login + API key. Optional: import your Danbooru Asset Organizer (DAO)
backup to filter by artist / copyright / character.

Every push to `main` builds a debug APK on GitHub Actions and attaches it to a release
(`build-<run number>`). Builds are signed with the fixed key in `app/debug.keystore`, so a new build
installs over the old one and keeps your database.
