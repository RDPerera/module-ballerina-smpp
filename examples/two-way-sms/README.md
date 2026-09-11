# two-way-sms

A **balance-enquiry short code** — the classic telecom self-care flow: a subscriber
texts a keyword ("BAL") to a short code, and a service replies on the same session,
inline, with the answer.

This demonstrates the library's bidirectional surface: a handler declares an
`smpp:Caller` parameter and calls `caller->submit` to reply on the same SMSC session
the inbound message arrived on. It shows:

- `bindType: TRANSCEIVER` — a `RECEIVER` bind cannot transmit.
- `responseMode: ASYNC` — the documented recommendation for reply-style services:
  the inbound `deliver_sm` is acked immediately, so a submit round trip inside the
  handler can't outlive the SMSC's transaction timer and draw a duplicate
  redelivery.
- Setting `sourceAddress` (the short code, `TON_ABBREVIATED`) explicitly on every
  reply's `OutboundSms`, so every reply is seen as coming from the short code
  rather than the ESME's own bind identity.
- Keyword routing (`BAL`/`BALANCE`, `HELP`/`INFO`, and an unrecognized-command
  fallback), matched on the first word, case-insensitively.
- Submit error handling around `possiblySubmitted` — the retry-safety bit: `false`
  means a retry cannot duplicate the message, `true` means it may.

## Run

```bash
# terminal 1
cd ../mock-smsc && ./gradlew run --args="steady 2775"

# terminal 2
bal run
```

Expected output (the mock SMSC pushes a rotating MO stream — a plain greeting and
the keywords `WIN`/`STOP`, neither of which this example recognizes as a balance
command — and answers each reply submit; it also pushes a scripted `id:0123456789`
delivery receipt unrelated to anything this service sent):

```
time=... level=INFO message="reply submitted" to="447700900001" messageId="0000000001"
time=... level=INFO message="reply submitted" to="447700900002" messageId="0000000002"
time=... level=INFO message="reply submitted" to="447700900002" messageId="0000000003"
time=... level=INFO message="balance-enquiry reply delivery receipt" status="DELIVRD" id="0123456789"
```

To see the `BAL` flow itself, point a second client at the mock SMSC's port and
submit a message with body `BAL` from any source address — or adapt the mock's
`pushKeyword` call in `mock-smsc/src/main/java/MockSmsc.java` to send `"BAL"`
instead of `"WIN"`.

## Against a real SMSC

Override host/port/systemId/password (and `shortCode`) via a `Config.toml` (see
[send-sms](../send-sms/)). Two things to size before production traffic:

- `maxConcurrentDispatch` (default 3) bounds inbound dispatch *and* is the effective
  outbound concurrency for a reply-style service; the SMSC sees `ESME_RTHROTTLED`
  when it pushes faster than the slots drain — a normal steady state, not an error.
- The library applies no rate limiting of its own; the carrier's throttling policy
  is yours to respect.

## Next steps

- [send-sms](../send-sms/) — the `Client`-only counterpart, for a pure send program.
- [receive-sms](../receive-sms/) — the minimal `Listener`, without a reply path.
