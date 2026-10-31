# termlib source in Haven

This directory is tracked as ordinary source files in the Haven repository.
Gradle builds it through `includeBuild("termlib")` in the root settings file.

## Source baseline

- Repository: https://github.com/GlassOnTin/termlib.git
- Commit: `5f9c2eb1eefafe55399e4484f211bf4b45598025`
- Imported on: 2026-10-31
- Licenses: Apache-2.0 in `LICENSE`; the bundled libvterm retains its MIT
  license in `lib/src/main/cpp/libvterm/LICENSE`. Existing copyright notices
  are preserved in the source files.

## Haven changes

- `Terminal.kt` provides custom, Android system, and hidden selection menu modes.
- `SystemSelectionToolbar.kt` connects Android text actions to terminal
  selection, copy, paste, and select-all operations. Haven enables the system
  menu for its active terminal page; the library default remains custom.
- `SystemSelectionClipboardTest.kt` covers copying selected terminal text to
  the Android clipboard and routing paste/dismiss actions.

## Updating

Compare new upstream changes with the baseline above and merge them into this
directory, preserving Haven's selection menu integration. Review and build
the resulting source, then update the baseline and local-change notes here.
Use Haven's root Gradle wrapper to build the app.
