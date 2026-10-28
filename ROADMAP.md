# Roadmap

Planned in iterations, one release per step.

1. **Outlook `.msg` files** - open .msg like .eml (header, rendered body, attachments).
   Own OLE2 reader (MS-CFB) and compressed-RTF decoder, no Apache POI; RTF bodies are converted
   to HTML with `rtf-to-html` (27 KB). Plugin stays below 1 MB.
   Still open: attached mails (a .msg inside a .msg), tests with the Apache POI sample files.
2. **Header view** - a raw *Headers* tab ships with step 1; next: all headers in a readable list,
   the Received chain as a timeline, SPF/DKIM/DMARC results highlighted.
3. **Small conveniences** - open mail source from the clipboard, drag attachments into the project,
   *Save to Project…* (0.7.0). Opening several mails via drag & drop already works through the IDE.
   Drag mails from Apple Mail into the project tree (0.8.0). Next: check Thunderbird and Outlook drops.

## Later

Maybe: MBOX and search across folders, PDF export.
