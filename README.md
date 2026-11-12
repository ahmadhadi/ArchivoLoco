# ArchivoLoco

Android app that saves Instagram content you have access to — an on-device port of
[Tatsh/instagram-archiver](https://github.com/Tatsh/instagram-archiver) (MIT).

## Download

Grab the latest APK from **[Releases](../../releases/latest)**. Every push to `main` builds a new one
via GitHub Actions. Enable "Install unknown apps" for your browser/file manager to install it.

## What it does

| Mode | What gets saved |
|---|---|
| **Profile** | Profile info + HD profile picture, every post (all carousel images and videos), current stories, and highlights |
| **My saved posts** | Everything in your Saved collection (all pages), optionally unsaving each post after it's archived |

Options: include comments, include reply comments (slow), re-download everything (ignore history).

You can also **share a profile link from the Instagram app → ArchivoLoco** to fill in the username.

Files go to `Download/ArchivoLoco/<username>/` (or `/saved/`):

```
<id>.jpg / <id>.mp4             best-quality media
<id>.json                       post node
<id>-media-info-0000.json       full media info
<id>-comments.json              comments (if enabled)
stories/  highlights/           story & highlight media
web_profile_info.json  profile_pic.jpg
failed.txt                      anything that couldn't be archived
```

A history database keeps re-runs incremental — only new posts are downloaded.

## How it works

| Python tool | ArchivoLoco |
|---|---|
| Reads cookies from your desktop browser | You log in once in an in-app Instagram web page; its session cookies are reused. The app never sees your password. |
| niquests + producer/consumer queues | OkHttp + coroutines, strictly one request at a time with a short pause between posts |
| yt-dlp for videos | Downloads the highest-resolution entry from `video_versions` directly |
| `.log.db` SQLite dedup | SQLite dedup log per output folder |
| Rich progress display | Live log + foreground notification with a Stop button |

It sends the same desktop Chrome user agent and `Sec-CH-UA` client hints as the original — Instagram's
edge rejects API calls when they disagree. If Instagram changes its GraphQL `doc_id`s or headers,
update `Constants.kt` (follow upstream's `constants.py` / `client.py`).

## Build locally

Requires JDK 17 and the Android SDK.

```
gradle :app:assembleRelease
```

### Stable signing (optional)

Without a signing key, CI signs with a throwaway debug key, so you must uninstall before installing
a newer build. To fix that, create a keystore and add repo secrets:

```
keytool -genkeypair -v -keystore archivoloco.keystore -alias archivoloco -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 archivoloco.keystore   # → secret ARCHIVOLOCO_KEYSTORE_B64
```

plus `ARCHIVOLOCO_KEYSTORE_PASSWORD`.

## Privacy & security

- **Talks only to Instagram.** Every request, including redirects, is checked against an allowlist:
  `*.instagram.com` and Meta's media CDNs (`*.cdninstagram.com`, `*.fbcdn.net`), HTTPS only. Anything
  else is blocked (`HostPolicy.kt`). No analytics, crash reporting, ads or telemetry SDKs.
- **Your session cookie goes only to `*.instagram.com`**, never to CDNs or anywhere else.
- **No background activity on its own.** Work runs only after you tap Start, shows a notification with
  Stop, ends when the job finishes, and stops if you swipe the app away. No boot receivers, alarms,
  scheduled jobs or auto-restart.
- **Login page is locked down:** no file access, no third-party cookies, and links outside
  Instagram/Facebook open in your normal browser.
- **Nothing leaves the phone in backups:** cloud backup and device-to-device transfer are disabled.
- **HTTPS only, system CAs only:** cleartext is banned and user-installed certificates are not trusted.
- **Build pipeline:** GitHub Actions pinned to exact commits, least-privilege token.

## Notes

- Only archives what your account can already see. Use it for your own backups and respect other people's content.
- Heavy use can trigger Instagram rate limits or checkpoints; the app deliberately goes slowly.
- Unofficial; not affiliated with Instagram or Meta.

## License

MIT. Based on instagram-archiver © Andrew Udvare.
