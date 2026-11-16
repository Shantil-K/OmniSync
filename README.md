# OmniSync

Universal two-way sync for Android with a root mode. Built to sync Samsung Notes (the ported app, which
can't sign in to Samsung Cloud) between devices, and general folders such as KeePass databases, through
any WebDAV server or synced folder you control. No vendor cloud, no account, no tracking.

**Download:** see [Releases](../../releases). Android 8.0+; root (KernelSU/Magisk) needed only for app-data profiles.

## Quick start
1. **Accounts → + Add WebDAV account**: URL + user + password (e.g. `https://cloud.example.com/remote.php/dav/files/me/`),
   or a local folder that another app syncs.
2. **+ Profile** and pick a preset: *Samsung Notes* (root), *KeePass*, or any folder.
   Choose the account and a remote folder; the same remote folder on every device = one synced set.
3. Tap **Sync now** once on the device that has the data, then on the others.
4. Optional: enable *App watcher* for the profile (Samsung Notes) so it syncs when you leave/open the app;
   add the Quick Settings tile for one-tap sync. Periodic background sync uses WorkManager.
5. **Accounts → Export (copy)** copies the whole setup to the clipboard; **Import (paste)** it on the next device (the export includes passwords – don't paste it anywhere public).

## Profiles / modes
| Mode | Use for | How it syncs |
|---|---|---|
| **Two-way folder** | KeePass `.kdbx`, documents, any folder | Per file. Only changed files move (SHA-256). Edited on two devices → both kept as `name (conflict <device> <time>).ext`. |
| **Samsung Notes merge** | `/data/data/com.samsung.android.app.notes` (root) | Per note three-way merge of `sdoc.db` + each note's `SDocData/*.sdocx` and `app_attach/<uuid>` files. Notes changed on different devices are all kept; the same note changed on both → newer edit wins (pre-merge backup kept). |
| **App data snapshot** | other apps' databases (root) | All changed files move together in one direction; conflicts → newest wins, loser backed up. |

## Clouds
* **WebDAV** – Nextcloud/ownCloud, Koofr (can also bridge Google Drive / OneDrive / Dropbox), pCloud, Box, InfiniCLOUD, NAS, `rclone serve webdav`, …
* **Folder** – any local path; pair it with FolderSync / Syncthing / Drive client to reach any other cloud.

Only changed files are transferred. A cloud-side `.omnisync/manifest.json` caches content hashes so devices
never download a file just to learn it hasn't changed; files edited by other clients (e.g. KeePass on a PC
over WebDAV) are detected through their ETag/size/mtime.

## Root mode
* Reads/writes any path through `su --mount-master` (an app's su otherwise can't see other apps' data).
* Force-stops the target app before touching its data and restores owner uid/gid + SELinux label.
* Never writes under a running app; if you reopen it mid-sync it is stopped and relaunched with the new data.
* **App watcher** (accessibility service, reads no screen content): pushes ~5 s after you leave a watched app and,
  when you open it, pulls/merges first and relaunches it. Re-enabled automatically via root after updates.
* Grants itself battery-optimisation exemption, background-run and HyperOS/MIUI Autostart.

## Automation (adb / Tasker via shell)
```
am broadcast -f 0x20 -a dev.omnisync.SYNC   -n dev.omnisync/.CommandReceiver [--es profile "Samsung Notes"] [--ez force true]
am broadcast -f 0x20 -a dev.omnisync.IMPORT -n dev.omnisync/.CommandReceiver --es json_b64 <base64 config>
```
(Receiver requires the DUMP permission, i.e. shell/system only.) Accounts screen → Export/Import copies the full
config (including passwords) through the clipboard to set up another device.

## Build
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
On HyperOS, `adb install` needs a Xiaomi account; install as root instead:
`adb push app-debug.apk /data/local/tmp/o.apk && adb shell su -c pm install -r /data/local/tmp/o.apk`.

## Safety
* Before every pull/merge into app data the previous local copy is saved under the app's
  `files/backups/<profile>/` (last 5 kept).
* A `.omnisync/lock` file on the remote stops two devices writing at once.
* Samsung Notes is force-stopped while its files are replaced, and owner/permissions/SELinux labels are
  restored afterwards.

## Limitations
* The Samsung Notes merge works on whole notes: editing the *same* note on two devices before syncing keeps
  the newer version (the other is in the backup folder).
* Tested on HyperOS / Android 16 with KernelSU and Samsung Notes 4.4.x (port). Other Notes versions may use a
  different database schema.
* Not affiliated with Samsung. This project contains no Samsung code or APKs.

## License
MIT – see [LICENSE](LICENSE).
