# Ballerina SMPP Examples

This directory contains a set of examples to demonstrate how to use the `ballerina/smpp` connector.

## Examples

- [**Send an SMS**](send-sms): submit a single SMS using `smpp:Client`.
- [**Receive SMS and delivery receipts**](receive-sms): bind `smpp:Listener` as a receiver and log inbound traffic.
- [**Two-way SMS: a balance-enquiry short code**](two-way-sms): reply on the same session using `smpp:Caller`.

## Running the Examples

Each example is a self-contained Ballerina package. From inside an example's directory:

```shell
$ bal run
```

Each example resolves `ballerina/smpp` from your local Ballerina repository — build and publish it there first by running `./gradlew build` from the repository root.

All examples need a real SMPP v3.4 SMSC to connect to; edit each example's `Config.toml` with your SMSC's connection details before running it.
