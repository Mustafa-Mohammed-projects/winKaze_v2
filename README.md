# WinKaze
Windows ISO -> USB flash drive (OTG) for Android, arm64-v8a.

Modes
- Windows Setup (UEFI): MBR + FAT32, copies ISO files, auto-splits install.wim (> 4 GB) into install.swm parts. UEFI boot only.
- Raw image (DD): byte-for-byte write, for Linux/hybrid ISOs.

Build
1. Replace `app/src/main/res/drawable-nodpi/presplash.png` with your image.
2. Push to GitHub -> Actions -> "Build WinKaze" -> download artifact `WinKaze-arm64-v8a`.
