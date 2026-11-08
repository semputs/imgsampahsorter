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
- When you leave a folder with images still in trash, it asks whether to delete them now or keep them for later.
- Pinch or double-tap to zoom; drag to look around while zoomed.
- Remembers where you were in each folder.

## Merge folders

On the start screen tap **🔀 Merge…**.

1. **Step 1:** tick ☐ Select on the folders to combine (e.g. several `good` folders), then tap **Next**.
2. **Step 2:** open the folder they should all go into, or tap **＋ New folder** to make one, then tap **✓ Merge into**.

 Files with the same name get `_1`, `_2`… added. Emptied folders are removed.

The `good` and `trash` folders are created inside the folder you're sorting.

## Install

Download `ImgSampahSorter.apk` from the [latest release](https://github.com/semputs/imgsampahsorter/releases/latest) on your phone and open it. Allow "install unknown apps" for your browser if asked.

Every push to `main` builds a new release automatically (GitHub Actions). New versions install over the old one.
