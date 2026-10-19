# Mail View

Plugin ID `app.heckit.ide.mailview` · Vendor heck\it ([heckit.app](https://heckit.app))

A plugin for JetBrains IDEs (PhpStorm, IntelliJ IDEA, … 2026.1 and later) that opens `.eml`
files the way a mail client shows them. Three tabs at the bottom of the editor, like *Text | Data*
for CSV files:

- **Mail** (default): subject, from, to, CC, BCC, reply-to and date, then the rendered message
  (HTML in an embedded browser, plain text otherwise). `cid:` images are embedded, remote content
  is blocked until *Load remote content* is clicked. Scripts never run. Links open in the system
  browser. Attachments: click to open with the system application, *Save…* to store a copy.
- **Content**: the decoded HTML or plain text part (switch at the top), read-only, highlighted.
- **Text**: the raw file in the regular editor, **read-only by default** (view and select only).
  *Edit* in the bar above unlocks it for this file until the IDE restarts; edits show up in the
  other tabs after a moment.

The plugin never writes to the `.eml` file itself; only the unlocked *Text* tab can change it.
UI in English and German.

## Build and run

Gradle needs a JDK 17+ ;
the toolchain (JDK 25) is downloaded via foojay if needed.

```
./gradlew runIde        # sandbox IDE with the plugin
./gradlew buildPlugin   # build/distributions/mail-view-<version>.zip
./gradlew verifyPlugin  # against the local IDE and verifyOldestVersion
./gradlew test -Psamples=<folder with .eml>   # parses real mails and prints their parts
```

Install a self-built ZIP: *Settings | Plugins | ⚙ | Install Plugin from Disk…*

`local.properties` (not checked in) can point to an installed IDE: `platformLocalPath=/Applications/PhpStorm.app`.

## How it works

| File | Contents |
| --- | --- |
| `EmlFileType.kt` | File type `.eml` (text, so the regular editor shows the raw data), icon |
| `MailParser.kt` | MIME parsing with Jakarta/Angus Mail (headers, HTML/text part, inline images, attachments) |
| `MailSource.kt` | Parses in the background and again after edits (unsaved document or file bytes) |
| `MailHtmlRenderer.kt` | HTML page of the *Mail* tab: header, message in a sandboxed iframe, CSP |
| `MailPreviewEditor.kt` | *Mail* tab (JCEF), remote content banner, attachments |
| `MailContentEditor.kt` | *Content* tab (read-only editor, HTML/Text switch) |
| `MailEditMode.kt` | Read-only by default, edit mode per file, bar above the *Text* tab |
| `MailEditorProviders.kt` | Registers both tabs before the regular editor |

`getContent()` of Jakarta Mail is avoided on purpose: it needs Jakarta Activation's mailcap
registry, which doesn't work reliably in a plugin class loader. Multiparts are read via
`MimePartDataSource`, text parts as decoded bytes with their charset.

## License

Apache License 2.0, see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
Copyright 2026 Peter Heck (heck\it). The name and logo are not covered by the license.
