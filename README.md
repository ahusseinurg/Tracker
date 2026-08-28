# Phone Activity Prototype

A transparent, consent-based Android prototype for a device owner. It reads Android's system-maintained usage statistics; it does not run hidden surveillance or capture private content.

## Included

- Today's or the last 7 days' app usage
- Total foreground time and last-used time
- Screen activation and unlock counts where the device reports them
- On-device processing
- CSV export through Android's document picker
- A clear permission state and direct link to Usage Access settings
- Multiple user-selected phone folders with persistent read access
- In-app image viewing, video playback, and voice-note/audio playback
- Media filters for pictures, videos, audio, and documents
- Independent backup copies in a user-selected destination folder
- 4–8 digit PIN lock with salted PBKDF2 hashing
- Automatic relock when the app leaves the foreground
- Screenshot and recent-screen preview protection
- Dedicated WhatsApp Status category and status-media backup

## Connect phone and WhatsApp media

Open **Phone Media Archive** and choose **Add folder**. Connect Pictures, Movies, Music, Documents, or another folder Android allows you to select. For WhatsApp, select `Internal storage/Android/media/com.whatsapp/WhatsApp/Media`. Approve **Use this folder** each time. The app remembers these read-only permissions and can refresh the combined library after restarts.

Choose **Backup folder** separately, preferably a folder created for this purpose such as `Documents/Phone Archive Backup`, then tap **Back up now**. The app organizes independent copies into Statuses, Pictures, Videos, Audio, and Documents subfolders. Items marked **PROTECTED COPY** are independent of the originals. Removing an original from WhatsApp or another connected source does not remove its protected copy. The destination is user-selected shared storage, so uninstalling the app does not automatically erase it.

The **Statuses** filter identifies accessible media in WhatsApp status-cache or status-archive folders. Status pictures, videos, and audio are included when **Back up now** runs. Your own posted status is also preserved when its original media is in any connected source folder. Statuses that WhatsApp never downloaded or exposed as files cannot be copied.

## Build and install

1. Install the current Android Studio.
2. Open the `PhoneActivityPrototype` folder.
3. Let Android Studio install the requested SDK and sync Gradle.
4. Connect an Android phone with USB debugging enabled.
5. Choose the phone and click **Run**.
6. In the app, tap **Open usage access settings**, select **Phone Activity**, and enable usage access.

To create an APK, use **Build > Build Bundle(s) / APK(s) > Build APK(s)**. Android Studio places the debug APK under `app/build/outputs/apk/debug/`.

## Honest prototype boundaries

Android does not allow a normal Play Store app to collect every action on a phone. This build intentionally does not collect passwords, message contents, keystrokes, microphone recordings, call audio, photos, or private data inside other apps. Before commercial deployment, add organization enrollment, an administrator portal, retention rules, encryption, deletion controls, audit logs, privacy disclosures, and legal review.
