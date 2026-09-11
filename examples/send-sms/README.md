# send-sms

The smallest possible `ballerina/smpp` program: bind to an SMSC as a
**transmitter**, submit one text message via `Client.submit`, and `close`. This is
the starting point for any send-side integration — OTPs, alerts, notifications, and
so on.

## Run

```bash
# terminal 1
cd ../mock-smsc && ./gradlew run --args="steady 2775"

# terminal 2
bal run
```

Expected output:

```
[mock-smsc] <- submit_sm from=esme to=447700900001 id=0000000001 text="Hello from Ballerina SMPP!"
submitted - messageId=0000000001
```

(The mock SMSC's line above appears in terminal 1; the program's own output appears
in terminal 2.)

## Against a real SMSC

Override the defaults with a `Config.toml`:

```toml
host = "smsc.example.com"
port = 2775
systemId = "your-system-id"
password = "your-password"
destinationNumber = "94771234567"
```

or on the command line:

```bash
bal run -- -Chost=smsc.example.com -CsystemId=your-system-id -Cpassword=your-password
```

## Next steps

- [receive-sms](../receive-sms/) — the `Listener` counterpart: bind and log every
  inbound message and delivery receipt.
- [two-way-sms](../two-way-sms/) — reply to an inbound message on the same session,
  via `smpp:Caller`.
