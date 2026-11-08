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
- Remembers where you were in each folder.

The `good` and `trash` folders are created inside the folder you're sorting.

## Install

Download `ImgSampahSorter.apk` from the [latest release](https://github.com/semputs/imgsampahsorter/releases/latest) on your phone and open it. Allow "install unknown apps" for your browser if asked.

Every push to `main` builds a new release automatically (GitHub Actions). New versions install over the old one.
