## File types

| Type | Extensions | Tabs |
| --- | --- | --- |
| EML | `.eml`, `.emlx`, `.mht`, `.mhtml` | Mail, Content, Headers, Text |
| Outlook MSG | `.msg`, `.oft` | Mail, Content, Headers (binary, no Text tab) |
| Outlook TNEF | `.tnef`, `winmail.dat` | Mail, Content, Headers (binary, no Text tab) |
| MBOX | `.mbox`, `.mbx` | Mails (list + preview), Text |

The associations can be changed under *Settings | Editor | File Types*. Text files that contain a mail source show a *Show in Mail View* bar.

## Settings

**Settings | Tools | Mail View** (also in the right-click menus of the mail). IDE-wide, not per project.

| Option | Values | Default |
| --- | --- | --- |
| Open mails in | Last used / IDE theme / Light / Dark | Last used |
| Remember the last tab per file | on / off | off |
| Open mails on tab | Mail / Content / Headers (ignored if the last tab is remembered; mbox files always open on the list) | Mail |
| Show the plain text version instead of HTML | on / off (only for mails with both, can be switched per mail) | off |

**Remote content**

| Option | Values | Default |
| --- | --- | --- |
| Load images and other content from the web | Never (ask per mail) / From trusted senders / Always | Never |
| Trusted senders | addresses or domains, one per line or comma-separated (`news@example.com`, `example.com`) | empty |

*Always for example.com* in a mail's remote content bar adds the domain to the trusted senders and switches *Never* to *From trusted senders*. Scripts never run.

**Attachments**

| Option | Default |
| --- | --- |
| Show the attachment list expanded | off |
| Reveal attachments of unknown type in Finder/Explorer instead of opening them | off |

Archives and executables are always only revealed. Running them is possible from the context menu, after a warning.

**Drag & Drop from Apple Mail** (macOS only)

| Option | Values | Default |
| --- | --- | --- |
| Allow Dropping Mails from Apple Mail… | button: asks macOS for *Automation \| Mail* once | not allowed |

When allowed, mails dragged from Apple Mail onto a folder in the project view are saved there as `.eml`. Mail View reads them from Mail via JavaScript for Automation, because Apple Mail only hands out a file promise that Java can't take. Mail View never asks for the permission on its own. Once it is denied, it can only be changed in System Settings (*Privacy & Security | Automation | <IDE> | Mail*); the button then opens them.

## Mailpit

Tool window **Mailpit** (right side): one tab per [Mailpit](https://mailpit.axllent.org/) instance. `+` in the header adds one, the gear edits it, `−` removes it (the mails stay in Mailpit).

| Field | Values | Default |
| --- | --- | --- |
| Name | tab title | host name |
| Address | web UI of Mailpit, `http://` or `https://` | – |
| Show in all projects | on: stored IDE-wide / off: stored in the project | off |
| User, Password | Basic Auth (Mailpit `--ui-auth` or a proxy); stored in the IDE password safe, per address | empty (no login) |

- DDEV projects get a tab automatically: `http://<name>.<project_tld>:<mailpit_http_port>` from `.ddev/config.yaml` (defaults `ddev.site`, `8025`). Without any instance the tab shows `http://localhost:8025`.
- Single sign-on proxy (OIDC, e.g. Traefik) in front of Mailpit: *Sign in in browser…* opens the login in an embedded browser and keeps the session cookie (survives an IDE restart while the proxy accepts it). Passkeys work via QR code on the phone.
- The visible tab refreshes every 3 s, the others every minute. Unread mails count in the tab title, the sum in the tool window title.
- Search uses Mailpit's full-text search (subject, addresses, text; also `from:`, `subject:` …).
- Click opens a mail in a preview tab, double-click or Enter keeps it open and marks it read. Context menu: save as `.eml`, open in the web UI, mark as read/unread, delete.

## Behaviour that isn't configurable

- The mail file is never written. Only the *Text* tab can change an `.eml`, after *Edit* (until the IDE restarts).
- mbox: only the headers are streamed, mails are extracted as `.eml` into a temp folder, and the mbox itself stays unchanged. Column order, widths and sorting are remembered IDE-wide, the last viewed mail per file.
- Exports never overwrite existing files (`name (2).pdf`).
