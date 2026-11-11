# ImgSampah Sorter

Android app for sorting a folder of images fast with swipes.

| Gesture | Action |
|---|---|
| Swipe **down** | Move to `trash` folder (hidden from gallery, can be undone) |
| Swipe **up** | Move to `good` folder |
| Swipe **left / right** | Next / previous image |
| Tap | Hide / show buttons |

- **Undo** button puts back the last image you trashed or marked good (as many steps as you like).
- **Trash (N)** button at the top: delete everything in trash forever (asks to confirm), or restore all.
- Trash is kept until you empty it with the Trash button. Leaving the app closes the image viewer, and the recent-apps thumbnail stays blank.
- Pinch or double-tap to zoom; drag to look around while zoomed.
- Long-press an image made by ComfyUI → "Make new workflow from this image" (reads the workflow saved inside the PNG).
- Remembers where you were in each folder, and asks on opening whether to continue or start from 1.
- Tap the counter at the top (e.g. "12 / 300") to jump to any image number.

## Merge folders

On the start screen tap **🔀 Merge…**.

1. **Step 1:** tick ☐ Select on the folders to combine (e.g. several `good` folders), then tap **Next**.
2. **Step 2:** open the folder they should all go into, or tap **＋ New folder** to make one, then tap **✓ Merge into**.

 Files with the same name get `_1`, `_2`… added. Emptied folders are removed.

The `good` and `trash` folders are created inside the folder you're sorting.

## Install

Download `ImgSampahSorter.apk` from the [latest release](https://github.com/semputs/imgsampahsorter/releases/latest) on your phone and open it. Allow "install unknown apps" for your browser if asked.

Every push to `main` builds a new release automatically (GitHub Actions). New versions install over the old one.

## ComfyUI remote (PC + phone)

Control ComfyUI on your Windows PC from the app's **🎨 ComfyUI** screen: duplicate / rename / delete saved workflows, quick-edit values (prompts, seed, steps, size…) and generate, use the full ComfyUI node editor, re-save workflows from recent runs, stop / clear the queue. Images in the output subfolders you choose are **moved** to `Pictures/ComfyUI/` on the phone (deleted from the PC only after the phone has the whole file), ready for the swipe sorter.

### PC setup (once)

1. Install [Tailscale](https://tailscale.com/download) on the PC and phone, log in with the same account.
2. Close the Comfy Desktop app, open **PowerShell** and paste:

   ```powershell
   irm https://raw.githubusercontent.com/semputs/imgsampahsorter/main/pc/install.ps1 | iex
   ```

   It runs ComfyUI hidden in the background (no window / taskbar icon, starts at login), and prints an **Address** and **Code** to enter in the app. Run it again to update.

On the PC, use ComfyUI in the browser at http://127.0.0.1:8188 (desktop shortcut "ComfyUI (browser)") instead of the Comfy Desktop app.

Security: ComfyUI only listens on 127.0.0.1. The bridge (port 8190) only accepts Tailscale addresses that send the pairing code; a firewall rule allows port 8190 from Tailscale addresses only.

Remove: `irm https://raw.githubusercontent.com/semputs/imgsampahsorter/main/pc/uninstall.ps1 | iex`
