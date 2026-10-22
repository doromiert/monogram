# GitHub application updates

The regular Monogram APK uses Telegram's existing optional update sheet, progress bar and Android installer entry. Settings exposes Check for updates. Launch checks the latest stable GitHub release from doromiert/monogram at every process startup and then once per day while the process remains alive. Checks do not download APKs automatically.

Release tags must be plain vMAJOR.MINOR.PATCH. The arm64 build selects monogram-VERSION-arm64.apk. Other architectures require equivalently named compatible assets. Draft/prerelease releases and incompatible assets are ignored. GitHub's SHA-256 asset digest is mandatory.

Downloads are bounded to the declared size (maximum 256 MiB), with HTTPS redirects restricted to GitHub's release hosts. Before installation is offered, Monogram verifies SHA-256, package name, version name, a newer Android version code and exactly the installed application's signing certificates. An APK signed with a different key is rejected. Downloads require user action; Android still asks for installation permission/confirmation. Partial downloads are cancelled rather than resumed. Completed downloads are kept for the current process and can be offered again through Settings; restart may require downloading again.

GitHub API errors are reported for manual checks. No GitHub token is needed for public releases. API reference: https://docs.github.com/en/rest/releases/releases .
