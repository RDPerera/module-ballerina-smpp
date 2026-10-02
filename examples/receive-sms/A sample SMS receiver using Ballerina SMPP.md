# Receive SMS and delivery receipts

## Overview

This example shows how to bind an `smpp:Listener` as a receiver and log every inbound mobile-originated SMS and delivery receipt.

### Prerequisites

- An SMPP v3.4 SMSC endpoint (host, port, `system_id`, and password) that accepts a receiver bind.

### Configuration

Edit `Config.toml` with your values:

```toml
[receive_sms]
host = "smsc.example.com"
port = 2775
systemId = "your-system-id"
password = "your-password"
```

### Run

```shell
$ bal run
```

Each inbound message or delivery receipt is logged as it arrives.
