# Replicator Ops – Captain's Log

Each entry is shown once in the app after updating, and becomes the GitHub release notes.
Add a new entry as `## <version>` followed by `- ` bullet lines. The stardate (release date, yyyyDD.MM)
is stamped automatically when GitHub publishes the release.

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
