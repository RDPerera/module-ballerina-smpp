# receive-sms

The smallest possible `ballerina/smpp` listener program: bind to an SMSC as a
**receiver** and log every inbound message and delivery receipt. This is the
starting point for any receive-side integration — two-way SMS, delivery tracking,
campaign ingestion, and so on.

It implements the one callback a receiver needs, `onDeliverSm`, and distinguishes a
mobile-originated (MO) message from a delivery receipt via `sms.deliveryReceipt`. It
also implements `onError` to show how a session drop is surfaced (a `RECEIVER`/
`TRANSCEIVER` listener rebinds automatically per `rebindPolicy`; `onError` just gets
told about it).

## Run

```bash
# terminal 1
cd ../mock-smsc && ./gradlew run --args="steady 2775"

# terminal 2
bal run
```

Expected output (the mock SMSC rotates through this stream every 3s):

```
time=... level=INFO module=ballerina_examples/receive_sms message="inbound SMS received" from="447700900001" to="12345" text="Hello from the mock SMSC #0"
time=... level=INFO module=ballerina_examples/receive_sms message="inbound SMS received" from="447700900002" to="12345" text="WIN"
time=... level=INFO module=ballerina_examples/receive_sms message="inbound SMS received" from="447700900002" to="12345" text="STOP"
time=... level=INFO module=ballerina_examples/receive_sms message="delivery receipt received" from="447700900001" status="DELIVRD" id="0123456789"
```

## Against a real SMSC

Override the defaults with a `Config.toml`:

```toml
host = "smsc.example.com"
port = 2775
systemId = "your-system-id"
password = "your-password"
```

## Next steps

- [send-sms](../send-sms/) — the `Client` counterpart: connect, submit, close.
- [two-way-sms](../two-way-sms/) — reply to an inbound message on the same session,
  via `smpp:Caller` and a `TRANSCEIVER` bind.
