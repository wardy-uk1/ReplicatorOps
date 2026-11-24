# Replicator Ops – Captain's Log

Each entry is shown once in the app after updating, and becomes the GitHub release notes.
Add a new entry as `## <version>` followed by `- ` bullet lines. The stardate (release date, yyyyDD.MM)
is stamped automatically when GitHub publishes the release.

## 2.1.0-beta.4
- Status: tap the print's picture to see it large, then pinch to zoom
- Thermal log keeps an hour. Drag sideways to scroll back through it, pick 5, 15, 30 or 60 minutes to see more at once, and double-tap to jump back to now. Times are marked along the bottom
- Bed mesh profiles: see every mesh saved on the printer and tap one to view its map. Load it, remove it, save the mesh in use under a name, or measure a new named profile (e.g. one per bed temperature or plate)

## 2.1.0-beta.3 · Stardate 202624.11
- No more "Couldn't list files: timed out" after sending a print. The printer is slow to answer while it reads a file that has just arrived, so the app now waits, tries again and refreshes quietly. Only the Refresh button reports a real failure
- Less work for the printer: the app only asks it to re-read a file once, for a file it has just sent, instead of for every file without a preview

## 2.1.0-beta.2 · Stardate 202624.11
- Load pattern: the file name stays on one line. Long names shrink to fit instead of leaving a letter or two on a second line

## 2.1.0-beta.1 · Stardate 202624.11
- Ship's computer on the Slicer: it checks each model on the phone (no internet) and suggests how to print it stronger or with more detail. Each suggestion says why, with Apply and Dismiss buttons
- Tell it what the print is for (Display, Everyday or Strong) and it tailors the suggestions
- It spots overhangs, thin walls, a small footprint on the bed, flat tops and coarse curves. It can turn the model a better way up and smooth coarse curves, and both changes show in the viewscreen
- New settings: thicken thin parts (holes stay the same size), adaptive layers and fuzzy skin

## 2.0.1 · Stardate 202623.11
- The screen stays on while the camera is showing, in the Visual Sensors window and full screen
- Patterns sliced on the phone keep their preview picture, print time and filament in Files. If the printer hasn't read them from the file, the app asks it to look again, and otherwise uses the details it saved when it sliced the file

## 2.0.0 · Stardate 202623.11
- New Slicer tab: slice STL and 3MF files (MakerWorld, Printables…) right on the phone, offline, with CuraEngine, the engine inside Ultimaker Cura. Then send them straight to the printer
- 3MF projects keep their plates, objects and settings from OrcaSlicer, QIDI Studio or Bambu Studio: layer height, walls, infill, supports, temperatures, speeds, seam and ironing
- Adjust filament, temperatures, layer height, walls, infill pattern, seam, ironing, supports (normal or organic tree) and brim before slicing, with a 3D viewscreen you can turn and zoom
- Sliced files use QIDI's own Q2C start and end routine, and show print time, filament, a preview and the current layer on the printer's screen
- "Pattern requires slicing" now offers Slice it here
- Files uploaded from the app show their size and details straight away
- Needs a 64-bit phone for slicing (nearly every phone from the last few years)

## 1.1.0-beta.8 · Stardate 202623.11
- New slicing engine: CuraEngine (the engine inside Ultimaker Cura) now runs natively on the phone in place of Kiri:Moto. It's much faster (seconds rather than minutes) and doesn't run short of memory on big models
- New settings: seam position, ironing for smooth tops, organic tree supports, and Cura's infill patterns including lightning
- Every Cura setting is worked out the same way Cura does it, starting from your choices and QIDI's Q2C start and end G-code
- Files from the phone show print time, filament and a preview on the printer's screen and in Files, and the printer tracks the current layer

## 1.1.0-beta.7 · Stardate 202623.11
- Found the real cause of the slicer sticking at about 71%: one of Kiri:Moto's helper workers could hit an error (usually memory, on big models) and never answer, so the slice waited forever. That step now carries on by itself when it happens
- Phones use fewer helper workers, which uses less memory
- If the slicer ever stops responding for a minute, it automatically tries again in a low-memory mode. If that still fails you get a clear message instead of an endless progress bar

## 1.1.0-beta.6 · Stardate 202623.11
- Slicer no longer stalls at about 71% when you change settings and slice again: every slice now starts on a fresh slicing engine
- Cancel stops the slicer straight away
- Patterns sliced on the phone show their size, estimated time, filament and preview picture in Files as soon as they're sent, and the app keeps checking until the printer has read the file itself

## 1.1.0-beta.5 · Stardate 202623.11
- Viewscreen fixed: the 3D preview is now drawn by the app itself, so the model and the Q2C bed show on every phone (it was blank on some). Drag to turn and tilt, pinch to zoom, double-tap to centre
- The flashing "Awaiting pattern" / "Loading pattern" status is now part of the app, in the LCARS font
- Thumbnails for the printer's screen are drawn the same way, so they always show the model

## 1.1.0-beta.4 · Stardate 202623.11
- Viewscreen: "Awaiting pattern" now sits in the middle of the 3D preview and flashes like a starship display, in the LCARS font. "Loading pattern" flashes faster in blue
- Clear empties the viewscreen and shows "Awaiting pattern" again

## 1.1.0-beta.3 · Stardate 202623.11
- Slicer layout tidied: the 3D preview stays pinned at the top (on the left in landscape) while the settings scroll underneath
- Settings are grouped into fold-away sections, each with a one-line summary: Filament, Quality, Strength, Support & adhesion and Speed
- Smaller round − / + buttons, and the Slice / Send to printer bar stays at the bottom of the screen
- The preview always frames the model: centred and fitted when it loads, when you change plate and when the screen size changes. Double-tap to frame it again after zooming
- New settings: part fan, first layer height, infill and first-layer speeds, support overhang angle, 0.08 mm layers

## 1.1.0-beta.2 · Stardate 202623.11
- Slicer: shows the estimated print time and filament length after slicing

## 1.1.0-beta.1 · Stardate 202623.11
- Replicator Ops Beta: a separate app that installs next to the normal one
- New Slicer tab: slice STL and 3MF files (MakerWorld, Printables…) right on the phone, offline, with the Kiri:Moto engine
- 3MF projects keep their plates, objects and settings (layer height, walls, infill, supports, temperatures, speeds) from OrcaSlicer, QIDI Studio or Bambu Studio
- Tweak filament, temperatures, layer height, walls, infill, supports and brim before slicing; 3D preview you can turn and zoom
- Sliced files use QIDI's own Q2C start and end routine, include a thumbnail for the printer screen, and go straight to the printer with Send to printer
- "Pattern requires slicing" now offers Slice it here
- Starbase Uplink in the Beta follows beta releases

## 1.0.3 · Stardate 202623.11
- Filament check: pick from your spools. Tap Spools next to any filament to choose from Replicator stores, with grams left. Spools linked to a Box slot can be picked
- Filament check: use any filament you like. A different type or colour from the file is now just a note, not a warning. Only an empty slot stops you
- Each slot shows how many grams its spool has left, and single-filament prints warn if the spool may run out

## 1.0.2 · Stardate 202623.11
- Filament check before printing: tapping Replicate shows the filaments the file needs and lets you choose which QIDI Box slot feeds each one, like QIDI Studio. Empty or mismatched slots are flagged, so prints no longer stop with "Please load the filament to 1D first"
- The app suggests a slot for you: it keeps the printer's last choice if that slot is loaded with the right type, otherwise picks the closest colour of the same type
- Prints started from the replication queue choose their slots the same way

## 1.0.1 · Stardate 202623.11
- Printer messages: when the printer shows a pop-up (like "Please load the filament to 1D first"), the app shows it as a Red Alert in plain words ("Box 1 slot 4"), and Red Alert sends it to your phone
- Control: Bed ▲10 ▲1 ▼1 ▼10 buttons, like the printer's own screen
- Control: air system fans (auxiliary, chamber, exhaust) each get a slider
- "Motors off" is now called Unlock steppers, as on the printer

## 1.0.0 · Stardate 202623.11
- Replicator Ops 1.0: the first full release
- Now built as a proper release version instead of a test (debug) build: smaller, faster and less likely to be flagged by Play Protect. Your settings, spools and printers carry over
- In this release: live Status with camera, Control and QIDI Box, Ship systems with bed mesh and macros, Files with load/retrieve and the replication queue, Timelapse archive, Commands, Red Alert notifications with first-layer watch, Target lock, home screen widget, fleet registry, print costs, Sensor sweep and "Computer," voice commands
- Works on phones, foldables, tablets and Chromebooks, in portrait and landscape

## 0.8.4 · Stardate 202623.11
- Commands tab: the Speak and Type buttons now fit on small phones

## 0.8.3 · Stardate 202623.11
- New Commands tab: every "Computer," command with what to say. Tap one to run it (with a spoken reply); Abort asks first
- Speak a command or Type a command buttons at the top
- Your printer's macros (Subroutines) are listed there too, with favourites

## 0.8.2 · Stardate 202623.11
- Checked on nine screen sizes, from a 320 dp phone to a 10" tablet, foldable and Chromebook window
- Small phones now show two columns of files and timelapses
- The bed mesh map stays a sensible size on tablets and in landscape

## 0.8.1 · Stardate 202623.11
- "Computer," typed commands: on devices without a microphone or speech service (some Chromebooks and tablets), tapping the combadge opens a command box instead, with quick buttons for common commands. Long-press the combadge to type on any device

## 0.8.0 · Stardate 202623.11
- Fits every screen: the whole interface (LCARS frame, text and buttons) now scales to the screen. Small phones shrink slightly, tablets get a bigger frame and larger text, and turning a phone sideways keeps the same size
- Foldables, split screen and Chromebook/desktop windows: the app resizes live without restarting, so the camera and connections keep running when you fold, unfold or resize
- Can be installed on Chromebooks and tablets without a microphone or touchscreen

## 0.7.1 · Stardate 202623.11
- Landscape: the menu now scrolls (and its buttons shrink a little) when it doesn't fit, so every item can be reached
- The title is no longer cut off at the top with the new font
- Files and Timelapse show more columns when there's room (landscape and tablets)
- Long pop-ups (file details, timelapse player, Captain's Log and others) now scroll instead of running off the screen

## 0.7.0 · Stardate 202623.11
- Replication queue: Add to queue from any pattern's details, then reorder or remove in Files > Queue. When a print finishes, Red Alert says "clear the build plate" with a Next button, or tap Engage next on the Status screen
- Bed mesh: a 3D map of your bed's levelling on Ship systems (drag to turn and tilt), with variance and a Calibrate button
- Fleet registry: save several printers in Config, then tap the title bar on any screen to switch. Sensor sweep's Lock on adds the printer to the fleet
- Print costs: filament cost per print, in total and per print in the Mission log. Uses each spool's price (new Price field in Replicator stores) or a price per kg you set
- "Computer," voice commands: tap the combadge in the title bar and say "status report", "time remaining", "temperatures", "pause", "resume", "lights on" and more. Replies are spoken
- Launcher shortcuts (long-press the app icon): Status report, Computer and Pause
- New LCARS-style font (Antonio) throughout the app

## 0.6.1 · Stardate 202623.11
- Sensor sweep now finds printers on your Wi-Fi even while a VPN (Tailscale, Proton) is running on the phone
- Home screen widget: the 30-minute refresh now asks the printer instead of showing an old status
- Each menu item has its own colour (Timelapse and Config no longer repeat Status and Control)
- QIDI Box: the loaded slot is read more strictly, so "no slot loaded" can't show as slot 2
- Code tidy-up: unused code and old guesses at Box field names removed

## 0.6.0 · Stardate 202623.11
- Live camera: the Camera button now plays the printer's live video stream (falls back to snapshots if the stream isn't available). A LIVE marker shows which you're seeing
- Full screen camera with pinch to zoom
- Subroutines: your printer's Klipper macros on the Ship systems screen. Star favourites to keep them at the top; the full list folds away
- First-layer watch: Red Alert sends a camera snapshot when layer 2 starts, so you can check the first layer from anywhere (with a Pause button). Turn it on or off in Config > Red Alert

## 0.5.0 · Stardate 202623.11
- Home screen widget: status, progress bar, percentage, time left and file name. Stretch it to show more. Tap it to open the app. Live while Red Alert is on or the app is open, otherwise every 30 minutes
- Timelapse archive: a new menu item listing the printer's timelapse videos. Watch them in the app, save to your phone, share or delete
- Sensor sweep: finds printers on your Wi-Fi (QIDI's own discovery plus a network scan). Runs by itself on first launch, or tap Sensor sweep in Config, then Lock on

## 0.4.5 · Stardate 202623.11
- Config: Klipper restart and Firmware restart now sit one above the other, so their names fit
- Updated the About text

## 0.4.4 · Stardate 202623.11
- Target lock: lock on to several objects and cancel them all with one Fire
- Target lock: the bed map now draws each object's real shape, read from the first layers of the print file, instead of a dot
- Cleaner object names (no ".step" or ".stl" in the middle)
- Config: the QIDI Box raw data is now folded away behind a Show button

## 0.4.3 · Stardate 202623.11
- Load pattern: files retrieved from the printer can be loaded back. Some ".gcode.3mf" files on QIDI printers are plain G-code inside, and these were wrongly flagged "Pattern requires slicing"
- Sliced 3MF files are read more reliably

## 0.4.2 · Stardate 202623.11
- Filament bay: a slot's type now follows the printer. Setting it on the printer's screen or using Read tag replaces anything set in the app's Edit slot
- Fuller filament names (like "PLA Silk") are shown when the printer reports them

## 0.4.1 · Stardate 202623.11
- Klipper restart button in Config > Diagnostics, next to Firmware restart
- Both restarts ask before going ahead if a print is running

## 0.4.0 · Stardate 202623.11
- Red Alert: notifications when a print finishes, fails, is cancelled or pauses (filament runout), even with the app closed. Turn it on or off in Config > Red Alert
- Live progress notification while printing ("Replicating 42% · 51m left") with Pause and Resume buttons
- Red Alert restarts by itself after a reboot or a Starbase Uplink update
- Target lock: during a print, tap Target lock on the Status screen to cancel one object on the plate using a bed map or a list. The rest of the plate keeps printing
- New Ship systems screen in the menu: case light, spaghetti detection and its sensitivity, and the pre-flight foreign-object scan

## 0.3.8 · Stardate 202623.11
- Ship's chronometer: the bottom bar now shows the device time as "0811 hours" after the stardate, with a pulsing marker
- The stardate now rolls over at midnight without restarting the app
- Hint text (like the emergency stop note) is now easy to read in both LCARS styles

## 0.3.7 · Stardate 202623.11
- Retrieve pattern: download any file from the printer to your phone from File details
- Android's save screen lets you pick the folder (Downloads, Drive…) and the name

## 0.3.6 · Stardate 202623.11
- "Pattern requires slicing" now says where a file came from: PrusaSlicer (Printables), Bambu (MakerWorld), Cura or a plain model
- Long file names in Load pattern wrap onto several lines instead of sliding
- Safety: files sliced in Bambu Studio for Bambu printers (common on MakerWorld) are refused, with an explanation

## 0.3.5 · Stardate 202622.11
- Load pattern: send sliced .gcode and .gcode.3mf files to the printer from the Files screen
- Share → Replicator Ops (or Open with) from Files, Chrome, Drive, email and chat apps
- After uploading, the pattern's details open so you can Replicate it
- STL and unsliced 3MF files show "Pattern requires slicing" with what to do next

## 0.3.4 · Stardate 202622.11
- Pinch to zoom on model pictures in File details and on the camera view
- Drag to look around while zoomed; double-tap to zoom in on a spot or reset

## 0.3.3 · Stardate 202622.11
- Captain's Log now uses the same rounded panel as the camera view
- Text too long for its space slides across instead of being cut off
- Mission log counts your whole print history, not just the latest 500 jobs

## 0.3.2 · Stardate 202622.11
- Captain's Log: this "what's new" screen appears once after every upgrade
- Reopen it any time from Config → Starbase Uplink → Captain's log

## 0.3.1 · Stardate 202622.11
- IPv6 printer addresses: type them as they are, or as [address]:7125 with a port
- Clear message for link-local fe80:: addresses, which can't be used

## 0.3.0 · Stardate 202622.11
- Starbase Uplink: checks for upgrades on launch and from Config, then downloads and installs them
- Unload now clears the nozzle only (M603); the spool stays in its Box slot
- New Eject button runs a slot's filament all the way out of the Box
- Builds are signed with one fixed key so upgrades install over the top

## 0.2.1 · Stardate 202622.11
- Read tag warns when the selected Box slot is empty
- QIDI Box Load, Read tag and slot colours confirmed on a Q2C

## 0.2.0 · Stardate 202622.11
- First public build: Status, Control, QIDI Box filament bay, Replicator stores, Files, Mission log
- 24th and 25th Century LCARS styles with computer chirps
