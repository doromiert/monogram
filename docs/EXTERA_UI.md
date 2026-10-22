# Exteragram appearance reference

Reference artifact supplied by the user: `~/extera/exteraGram-full-universal-20261004.apk`. SHA-256: `1ed01526c0cb57bc798c29cc06ab4d01d30282ce2cc1b177f0222e906c22581f`.

Folder artwork: 30 `filter_*` PNG icons, four densities each, extracted from this APK’s Android resource table. Stable names and content digests are recorded in the ignored `.state/extera-folder-assets.json` analysis artifact. The account-local icon preference stores resource names rather than build-specific numeric Android IDs. The existing native folder picker, tab strip and lists are reused.

Ordinary JADX/decdump inspection recovered `com.exteragram.messenger.utils.ui.FolderIcons`: 24dp icons, a 4dp icon/title gap, and titles-only, icons-only or combined presentation. The supplied configuration uses Front, Rear and Ask round-video camera choices. The recorder’s Ask path opens a native anchored camera chooser before recording, and starts locked recording after selection. These are static observations, not runtime verification. Folder display-mode controls and the Ask entry flow are implemented in 0.2.142, including both recorder backends. Additional controls cover avatar corners, centered home title and compose-button visibility. This is a selected appearance subset, not complete Exteragram parity.

REA MCP/Android provider was unavailable in this session; the existing native SDK tools and JADX were used instead. No REA Evidence identifiers are claimed. JADX reported incomplete decompilation for unrelated portions of the large input-view class.

## 0.2.146 Appearance and App Navigation

The dedicated Appearance screen now exposes the remaining native controls and App Navigation, plus icon-pack editing and pills. See [EXTERA_APPEARANCE_PARITY.md](EXTERA_APPEARANCE_PARITY.md) for the source coverage, XMPP adaptations, defaults, verification and outstanding runtime comparisons. The 37-entry reference inventory includes preview cells; emoji status remains removed. This expands the earlier subset without claiming pixel-exact runtime parity.
