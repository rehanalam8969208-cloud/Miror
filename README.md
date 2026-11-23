# Mirror Note Android app

A small native Android notes app. Each saved note becomes a UTF-8 `.txt` file inside:

`Internal storage/Mirror Note/<Note title>.txt`

## Build APK on GitHub

1. Create a new GitHub repository.
2. Upload the contents of this project (keep the folder structure).
3. Open **Actions** → **Build Mirror Note APK** → **Run workflow**.
4. When the workflow finishes, open its run and download the `MirrorNote-debug-apk` artifact.
5. Extract the artifact ZIP; it contains `app-debug.apk`.

## Storage permission behavior

On Android 11+, the app opens Android's special **Allow access to manage all files** settings page. Turn it on, then return to the app. The app creates `Mirror Note` in the shared internal-storage root.

This is broad storage access. It is included because the requested behavior is to create a folder at the shared-storage root. Google Play restricts this permission; a Play Store release may need a different storage design or policy approval. For personal testing/sideloading, install the APK and grant access only if you are comfortable with the permission.

## Notes

- The filename is based on the title, with unsafe filename characters replaced by underscores.
- Saving another note with the same title replaces that `.txt` file.
- Tap a saved filename to load it back into the editor.
- This starter project does not include cloud sync or encryption.
