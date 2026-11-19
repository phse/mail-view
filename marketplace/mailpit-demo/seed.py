#!/usr/bin/env python3
"""Sends neutral demo mails (example.com) to the Mailpit demo container, oldest first."""
import smtplib
import time
import urllib.request
from email.message import EmailMessage

SHOP = 'Riverside Makers <shop@example.com>'
mails = [
    ('Welcome to Riverside Makers', 'Alex Morgan <alex@example.org>', False, 'account',
     '<h1>Welcome, Alex!</h1><p>Your account is ready. Confirm your address to start ordering.</p>'),
    ('Confirm your email address', 'Alex Morgan <alex@example.org>', False, 'account',
     '<p>Please confirm your address: <a href="https://example.com/confirm">Confirm</a></p>'),
    ('Order #1042 received', 'Alex Morgan <alex@example.org>', False, 'order',
     '<h2>Thanks for your order</h2><p>3D printer filament, 2 x PLA white, 1 x PETG black.</p>'),
    ('Invoice INV-2026-0117', 'Alex Morgan <alex@example.org>', True, 'order, invoice',
     '<p>Hi Alex, please find your invoice attached.</p>'),
    ('Your order #1042 has shipped', 'Alex Morgan <alex@example.org>', False, 'order',
     '<h2>On its way</h2><p>Tracking number: 1Z999AA10123456784</p>'),
    ('Workshop reminder: Laser cutting basics', 'Sam Lee <sam@example.net>', False, 'workshop',
     '<p>See you Saturday at 10:00 in the workshop.</p>'),
    ('Reset your password', 'Sam Lee <sam@example.net>', False, 'account',
     '<p>Someone asked to reset your password. <a href="https://example.com/reset">Reset password</a></p>'),
]
s = smtplib.SMTP('localhost', 11030)
for subject, to, attach, tags, html in mails:
    m = EmailMessage()
    m['From'] = SHOP
    m['To'] = to
    m['Subject'] = subject
    # Mailpit takes the tags from this header.
    m['X-Tags'] = tags
    m.set_content('This mail has an HTML version.')
    m.add_alternative(f'<div style="font-family:sans-serif">{html}<p>Riverside Makers</p></div>', subtype='html')
    if attach:
        m.add_attachment(b'%PDF-1.4\n% demo invoice\n', maintype='application', subtype='pdf', filename='INV-2026-0117.pdf')
    s.send_message(m)
    time.sleep(1.1)
s.quit()
# The older mails count as read, the last three stay unread.
import json
ids = [m['ID'] for m in json.load(urllib.request.urlopen('http://localhost:18030/api/v1/messages'))['messages']][3:]
req = urllib.request.Request('http://localhost:18030/api/v1/messages', method='PUT',
                             data=json.dumps({'IDs': ids, 'Read': True}).encode(), headers={'Content-Type': 'application/json'})
urllib.request.urlopen(req)
print(f'{len(mails)} demo mails sent')
