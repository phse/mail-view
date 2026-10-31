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

## Behaviour that isn't configurable

- The mail file is never written. Only the *Text* tab can change an `.eml`, after *Edit* (until the IDE restarts).
- mbox: only the headers are streamed, mails are extracted as `.eml` into a temp folder, and the mbox itself stays unchanged. Column order, widths and sorting are remembered IDE-wide, the last viewed mail per file.
- Exports never overwrite existing files (`name (2).pdf`).
