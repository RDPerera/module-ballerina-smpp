# Send an SMS

## Overview

This example shows how to submit a single SMS to an SMSC using an `smpp:Client` bound as a transmitter.

### Prerequisites

- An SMPP v3.4 SMSC endpoint (host, port, `system_id`, and password) that accepts a transmitter bind.

### Configuration

Edit `Config.toml` with your values:

```toml
[send_sms]
host = "smsc.example.com"
port = 2775
systemId = "your-system-id"
password = "your-password"
destinationNumber = "94771234567"
```

### Run

```shell
$ bal run
```

A successful run prints the SMSC-assigned `messageId` for the submitted message.
