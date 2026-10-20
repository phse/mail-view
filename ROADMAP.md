# Roadmap

Planned in iterations, one release per step.

1. **Outlook `.msg` files** - open .msg like .eml (header, rendered body, attachments).
   Own OLE2 reader (MS-CFB) and compressed-RTF decoder, no Apache POI; RTF bodies are converted
   to HTML with `rtf-to-html` (27 KB). Plugin stays below 1 MB.
   Still open: attached mails (a .msg inside a .msg), tests with the Apache POI sample files.
2. **Header view** - a raw *Headers* tab ships with step 1; next: all headers in a readable list,
   the Received chain as a timeline, SPF/DKIM/DMARC results highlighted.
3. **Small conveniences** - save clipboard content as .eml, open several mails via drag & drop,
   save attachments directly into the project.

## Later

Maybe: MBOX and search across folders, PDF export.
