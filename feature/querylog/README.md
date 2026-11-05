# feature/querylog

The statistics tab groups retained logs by Android UID. Tapping a row opens the
full retained history for that UID, including requests outside the chart's
selected time range. The unknown row shows events without a UID.

With query logging and HTTPS filtering enabled for the selected app, **Capture
request details** starts a preview session. The default duration is five minutes
and can be set to any whole number of minutes from 1 to 120. You can switch to the
selected app to reproduce traffic and then return to the request list. New
decrypted requests can show the method, path, raw URL parameters, and up to
4 KiB of a readable UTF-8 body. A blocked request can show its URL parameters
even when its body was never sent. Existing logs, DNS, and traffic that cannot
be decrypted do not have previews.

The latest 100 previews stay in memory and are cleared when capture ends.
Room continues to store only request metadata.
