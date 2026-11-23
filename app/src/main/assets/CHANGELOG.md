# Replicator Ops – Captain's Log

Each entry is shown once in the app after updating, and becomes the GitHub release notes.
Add a new entry as `## <version>` followed by `- ` bullet lines. The stardate (release date, yyyyDD.MM)
is stamped automatically when GitHub publishes the release.

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
