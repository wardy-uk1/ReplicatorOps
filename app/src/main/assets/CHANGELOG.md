# Replicator Ops – Captain's Log

Each entry is shown once in the app after updating, and becomes the GitHub release notes.
Add a new entry as `## <version>` followed by `- ` bullet lines. The stardate (release date, yyyyDD.MM)
is stamped automatically when GitHub publishes the release.

## 0.3.6
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
