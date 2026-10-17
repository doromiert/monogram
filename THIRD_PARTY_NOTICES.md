# Third-party notices

## Monocles call signaling references

The XMPP call implementation adapts protocol mappings and call-state behavior from
[Monocles](https://codeberg.org/monocles/monocles_chat), revision
`129dbcc7cdc919c73a65e50ee121efecc36c5e7e`, distributed under the GNU General Public
License, version 3. The license is included in the application at
`assets/licenses/monocles-calls-GPL-3.0.txt` and in this repository at
[TMessagesProj/src/main/assets/licenses/monocles-calls-GPL-3.0.txt](TMessagesProj/src/main/assets/licenses/monocles-calls-GPL-3.0.txt).

Adapted files in `xmpp-core/src/main/java/com/negzero/monogram/xmpp/calls/`:

| Monogram | Upstream reference paths |
| --- | --- |
| `JingleCodec.java` | `src/main/java/eu/siacs/conversations/xmpp/jingle/stanzas/{Content,RtpDescription,IceUdpTransportInfo,Group}.java` |
| `JingleSdp.java` | `src/main/java/eu/siacs/conversations/xmpp/jingle/{SessionDescription,RtpContentMap}.java` |
| `XmppCalls.java` | `src/main/java/eu/siacs/conversations/xmpp/jingle/{JingleConnectionManager,JingleRtpConnection}.java` |
| `XmppIceServers.java` | `src/main/java/im/conversations/android/xmpp/model/disco/external/Services.java` |

Monogram changes use Smack XML and session APIs, bounded immutable protocol
snapshots, serialized signaling, account/session ownership checks, and the
existing Telegram Android WebRTC engine and call interface. These new files carry
`GPL-3.0-or-later` notices. Existing Telegram and bundled dependency notices remain
in their respective source files.
