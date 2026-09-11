# Two-way SMS: a balance-enquiry short code

## Overview

This example shows a two-way SMS flow: a subscriber texts a keyword (`BAL`, `HELP`, ...) to a short code, and the service replies on the same SMPP session using an `smpp:Caller`, without opening a second connection.

Replying on the same session needs a transceiver bind (`bindType: smpp:TRANSCEIVER`) — a receiver-only bind cannot transmit.

### Prerequisites

- An SMPP v3.4 SMSC endpoint (host, port, `system_id`, and password) that accepts a transceiver bind.

### Configuration

Edit `Config.toml` with your values:

```toml
[two_way_sms]
host = "smsc.example.com"
port = 2775
systemId = "your-system-id"
password = "your-password"
shortCode = "12345"
```

### Run

```shell
$ bal run
```

Text `BAL`, `HELP`, or any other keyword to the configured short code and observe the reply.
