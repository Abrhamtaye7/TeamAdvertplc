# ChessCam

An Android app that watches a real chess board through the phone camera, follows the game move by move, and on your turn has Stockfish say the best move and draw it as an arrow.

Everything runs on the phone: no internet, no account, no API costs.

## Install

1. Get the APK:
   - **From GitHub:** open the repository's **Actions** tab, then the latest **Build APK** run, and download the `ChessCam-apk` artifact (a zip containing the APK).
   - **Or build it yourself** (see below).
2. Copy the APK to the phone and open it. Android will ask you to allow installing apps from that source.
3. You need a 64-bit ARM phone (almost every phone from the last several years) running Android 8.0 or newer.

## Use

1. **Mount the phone** on a stand or tripod looking down at the board. The closer to straight down, the better. Make sure the whole board is in view and the light is even.
2. **Place the corners.** Drag the four yellow dots onto the outer corners of the 64 squares. The magnifier helps with precision. Check the labels: **a1** must be the corner on White's left. If the labels are on the wrong corners, tap **Rotate a1**.
3. Tap **I play White / Black** to choose your side, then **Start game** with all pieces on their starting squares.
4. **Play normally.** After each move, take your hand off the board. About half a second later the app registers the move:
   - It says the opponent's move ("Black played Knight B8 to C6").
   - On your turn it says Stockfish's best move ("Your move: Bishop F1 to B5") and draws it on the small board, with the evaluation.
   - If you play something other than the suggestion, it says what it saw ("You played …"), so a misread is noticed straight away.
5. **When it gets something wrong:**
   - **Undo** takes back the last move and pauses. Make the real board match the diagram, then tap **Resume**.
   - **Tap a move on the small board** (piece, then destination) to enter it yourself.
   - **Re-sync** tells the app the real board is correct as it is now (after a bump, a lighting change or moved corners).
   - **Repeat** says the last line again.
6. **More** has Adjust corners, New game, Settings, Share PGN and help.

### Overlay

**Overlay** keeps ChessCam following the game while you use another app.

- **Permission.** The first time, Android asks you to allow **Display over other apps** for ChessCam. Then ChessCam goes to the background and a see-through board covers the screen. It doesn't take touches, so the app underneath works normally.
- **Squares:**
  - **Yellow:** the piece to move.
  - **Green:** where it goes.
  - **Faint blue:** the opponent's last move, so you can check it was read correctly.
  - The overlay board is drawn from your side.
- **The frame around the screen shows the status:**
  - **Green:** your move is shown.
  - **Amber:** thinking.
  - **Grey:** waiting for the opponent.
  - **Red:** lost track of the board (use **Re-sync**).
  - **Purple:** the game is over.
- **Controls.** The notification has **Undo**, **Re-sync / Resume** and **Stop**. Opening ChessCam again ends the overlay.
- **Voice.** It is off while the overlay is on. Settings can turn it back on and change how strongly the colors are drawn.
- **Android indicators.** While the overlay runs, Android shows the notification and the green camera-in-use dot. Apps can't hide these.

The game is saved as you go, so closing the app doesn't lose it. **Share PGN** exports the game for any chess app.

**Settings:**
- Engine think time (0.5–5 s)
- Voice on/off
- Whether to say the moves the camera sees
- The red overlay of changed squares
- Detection sensitivity
- A custom starting position (FEN)

## How it works

- **Camera.** CameraX delivers about 15 frames a second. The four corners define a perspective mapping (homography) from the picture to the board. Each square is summarised as a 4×4 grid of average colours, sampled away from the square's edges.
- **Move detection** (`vision/MoveDetector.kt`). It never tries to recognise pieces; it is the same idea as the original tool:
  - It waits until the picture is still (no hand over the board).
  - It compares the board with a reference picture of the last confirmed position and measures how much each square changed, relative to the camera noise.
  - It scores every legal move by how well the squares it would change match the squares that did change. Castling, en passant and promotions are handled.
  - A move is accepted only if every square it touches really changed, nothing else clearly changed, it clearly beats the alternatives, and the same answer holds for 3 frames in a row.
  - If both players move before the board is still, it tries pairs of moves.
  - If nothing fits for a few seconds it says "Lost track of the board."
- **Camera angle.** Seen at an angle, an upright piece also covers part of the square behind it. The detector works out from the corner positions which square is "behind" each square on camera. It counts the camera-side part of each square less, and treats a moved piece's "lean" into the next square as expected.
- **App structure.** `GameSession` holds the game, tracker, engine and speech, so it keeps running when the screen is closed. `MainActivity` and `OverlayService` are two views onto it. The service is a camera foreground service that draws `ui/OverlayIndicatorView` as a `TYPE_APPLICATION_OVERLAY` window.
- **Engine.** Stockfish 19 is compiled for ARM64 with the NDK and shipped inside the APK as `libstockfish.so`, so Android installs it where apps are allowed to run executables. The app talks to it over UCI (`engine/UciEngine.kt`) with `go movetime`.
- **Chess rules.** `chess/` has its own move generator, verified with standard perft counts in the unit tests. It covers SAN, FEN, check, mate, stalemate, repetition, the fifty-move rule and insufficient material.

## Build

Requirements: JDK 17+, the Android SDK (platform 35) and NDK r27c or newer, plus `git` and `curl`.

```sh
export ANDROID_HOME=/path/to/Android/Sdk
scripts/build-stockfish.sh            # clones Stockfish 19, downloads its network, builds app/src/main/jniLibs/arm64-v8a/libstockfish.so
./gradlew testReleaseUnitTest         # unit tests: chess rules, move detection on synthetic camera frames, UCI
./gradlew assembleRelease             # app/build/outputs/apk/release/app-release.apk
```

The GitHub Actions workflow in `.github/workflows/android.yml` does the same on every push and uploads the APK.

Every build is signed with the shared, non-secret `app/debug.keystore`, so any build can update an installed copy. Sign with your own key before publishing the app anywhere.

### Coding agent tools

`.mcp.json` registers [REA](https://github.com/morluto/rea) (`rea-agents@6.8.0`), a reverse-engineering MCP server, for Claude Code. `.claude/skills/reverse-engineer-anything/` holds the matching skill. Claude Code asks once before it starts a server from `.mcp.json`; approve `rea` when asked. To update both, run `npx rea-agents@latest setup --client claude_code`, then copy `~/.claude/skills/reverse-engineer-anything` here and set the same version in `.mcp.json`.

## Limitations

- **Testing so far:** the detector has only been tested on synthetic camera frames (perspective board, pieces that lean, pixel noise, a moving hand), not yet on a real board.
- **Hard cases:** low contrast (for example cream pieces on cream squares) and a low camera angle make it harder. Expect to tune the sensitivity and corner placement on a real board, and use Undo or tap-to-enter when it misses.
- **Start position:** it needs to know the starting position: either the normal one or a FEN set in Settings.
- **Promotions:** a promotion is assumed to be to a queen unless Stockfish suggested another piece. Enter it by hand to choose.
- **APK size:** the APK is large (about 80 MB) because Stockfish 19's neural network is embedded.

## Fair play

Using engine help during a game against someone who hasn't agreed to it is cheating, and it is banned in rated and club play. ChessCam is meant for training, analysis and friendly games where everyone knows the phone is helping.

## Licence notes

The APK includes Stockfish, which is free software under the GPL v3. Its source is at https://github.com/official-stockfish/Stockfish (tag `sf_19`), and `scripts/build-stockfish.sh` reproduces the exact binary build.
