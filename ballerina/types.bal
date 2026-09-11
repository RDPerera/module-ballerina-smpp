// Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

// Types shared between `client.bal` (active outbound: submit_sm, submit_multi, data_sm,
// query_sm, cancel_sm, replace_sm over a transmitter/receiver/transceiver bind) and
// `listener.bal` (passive inbound: dispatches deliver_sm/data_sm to a service, with a
// session-bound `Caller` for same-session replies). Neither `Client`-only config
// (`ClientConfig`, using the full `BindType`) nor `Listener`-only config
// (`ListenerConfig`, using `ListenerBindType`) lives here — only what both sides need.

import ballerina/crypto;
import ballerina/log;

# The SMPP bind mode, per SMPP v3.4 §2.2. The full set of modes defined by the spec.
# `ListenerConfig.bindType` (the `Listener`) narrows this to `ListenerBindType` (see
# there for why `TRANSMITTER` is excluded there); `ClientConfig.bindType` (the `Client`)
# may use any of the three, since a `Client` only ever sends and TRANSMITTER is exactly
# a send-only bind.
public enum BindType {
    # Transmitter bind — send only. Per the SMPP spec, a transmitter-bound session is
    # never sent `DELIVER_SM`/`DATA_SM` by the SMSC, so it cannot drive a `Listener`'s
    # callbacks; a `Client` bound this way can still `submit_sm`/`submit_multi`/`data_sm`/
    # `query_sm`/`cancel_sm`/`replace_sm`.
    TRANSMITTER,
    # Receiver bind — the session only receives inbound PDUs (MO messages, delivery
    # receipts). A `Client` bound this way cannot submit.
    RECEIVER,
    # Transceiver bind — a single session that both sends and receives.
    TRANSCEIVER
}

# The bind modes usable with a `Listener`. Excludes `BindType:TRANSMITTER` at the type
# level — a transmitter-bound session structurally cannot receive `DELIVER_SM`/`DATA_SM`
# (see `BindType`), so configuring it on a `Listener` is rejected at compile time rather
# than connecting successfully and then never invoking the attached service. The `Client`
# has no such restriction and accepts the full `BindType`.
public type ListenerBindType RECEIVER|TRANSCEIVER;

# Controls automatic rebinding to the SMSC after an unexpected session drop (e.g. the SMSC
# closes the connection, or a network failure occurs) — detected via jsmpp's
# `SessionStateListener`, not engaged for a user-initiated `gracefulStop`/`immediateStop`.
# Only meaningful for the `Listener` today (a dropped `Client` session is surfaced to the
# caller as `LINK_DOWN`/`LINK_ABANDONED` on the next operation rather than rebound
# automatically), but kept here rather than in `listener.bal` since a future `Client`
# auto-reconnect option would reuse this exact shape.
#
# The attached service's optional `onError` remote method is notified once for the initial
# drop, then again for *every* failed rebind attempt (not just the final one), and once
# more if rebinding is eventually exhausted after `maxRebindAttempts` — `onError` can fire
# repeatedly during an extended outage, not just once or twice.
public type RebindPolicy record {|
    # Delay before the first rebind attempt, in seconds. Must not be negative
    # (validated at `Listener` init).
    decimal initialRebindDelay = 1;
    # Maximum delay between rebind attempts, in seconds — caps the exponential backoff.
    # Must be >= `initialRebindDelay` (validated at `Listener` init).
    decimal maxRebindDelay = 60;
    # Multiplier applied to the delay after each consecutive failure (exponential
    # backoff). The exponent grows across a *flapping* link too — a bind that succeeds
    # and then quickly drops counts as a failure, so the delay keeps compounding instead
    # of snapping back to `initialRebindDelay` on every short-lived bind. Must be >= 1
    # (validated at `Listener` init) — values below 1 would shrink, not back off.
    decimal backOffMultiplier = 2.0;
    # Maximum number of CONSECUTIVE failures before giving up, where a failure is either
    # a failed bind attempt or a bind that did not stay up for the stability window
    # (~60s continuously bound). The counter resets only after a stable window, at the
    # next drop — never on a successful bind alone, so this cap is reachable against
    # exactly the flapping link it exists for. On give-up the listener latches dead:
    # `onError` is notified once more, a WARN is logged, and every subsequent `submit`
    # fails with `LINK_ABANDONED` — only a new `Listener` recovers. `0` disables automatic
    # rebinding entirely (a drop still notifies `onError` once, latching `LINK_ABANDONED`
    # immediately). `-1` (the default) retries indefinitely. Other negative values are
    # rejected at `Listener` init.
    int maxRebindAttempts = -1;
|};

# Type of number, per SMPP v3.4 §5.2.5.
#
# Member names are prefixed because Ballerina enum members are **module-scoped string
# constants**: unprefixed, `UNKNOWN` would collide with `DeliveryReceiptStatus.UNKNOWN`
# and `NATIONAL` would collide with `NumberingPlanIndicator`, producing build warnings.
#
# Each member's value equals its member name, so what a `Config.toml` author writes is
# exactly what the docs show. The native layer strips the `TON_` prefix and resolves the
# remainder against `org.jsmpp.bean.TypeOfNumber`.
public enum TypeOfNumber {
    # `TypeOfNumber.UNKNOWN` (0) — let the SMSC decide.
    TON_UNKNOWN = "TON_UNKNOWN",
    # `TypeOfNumber.INTERNATIONAL` (1) — E.164 without a leading `+`.
    TON_INTERNATIONAL = "TON_INTERNATIONAL",
    # `TypeOfNumber.NATIONAL` (2).
    TON_NATIONAL = "TON_NATIONAL",
    # `TypeOfNumber.NETWORK_SPECIFIC` (3).
    TON_NETWORK_SPECIFIC = "TON_NETWORK_SPECIFIC",
    # `TypeOfNumber.SUBSCRIBER_NUMBER` (4).
    TON_SUBSCRIBER_NUMBER = "TON_SUBSCRIBER_NUMBER",
    # `TypeOfNumber.ALPHANUMERIC` (5) — an alphanumeric sender ID rather than digits.
    TON_ALPHANUMERIC = "TON_ALPHANUMERIC",
    # `TypeOfNumber.ABBREVIATED` (6) — short codes.
    TON_ABBREVIATED = "TON_ABBREVIATED"
}

# Numbering plan indicator, per SMPP v3.4 §5.2.6. Prefixed, value-equals-member-name,
# and prefix-stripped natively — for the same reasons as `TypeOfNumber`.
public enum NumberingPlanIndicator {
    # `NumberingPlanIndicator.UNKNOWN` (0).
    NPI_UNKNOWN = "NPI_UNKNOWN",
    # `NumberingPlanIndicator.ISDN` (1) — E.163/E.164, the usual choice for an MSISDN.
    NPI_ISDN = "NPI_ISDN",
    # `NumberingPlanIndicator.DATA` (3) — X.121.
    NPI_DATA = "NPI_DATA",
    # `NumberingPlanIndicator.TELEX` (4) — F.69.
    NPI_TELEX = "NPI_TELEX",
    # `NumberingPlanIndicator.LAND_MOBILE` (6) — E.212.
    NPI_LAND_MOBILE = "NPI_LAND_MOBILE",
    # `NumberingPlanIndicator.NATIONAL` (8).
    NPI_NATIONAL = "NPI_NATIONAL",
    # `NumberingPlanIndicator.PRIVATE` (9).
    NPI_PRIVATE = "NPI_PRIVATE",
    # `NumberingPlanIndicator.ERMES` (10).
    NPI_ERMES = "NPI_ERMES",
    # `NumberingPlanIndicator.INTERNET` (14) — IP.
    NPI_INTERNET = "NPI_INTERNET",
    # `NumberingPlanIndicator.WAP` (18) — WAP client id.
    NPI_WAP = "NPI_WAP"
}

# An SMPP address: the digits plus the two fields that say how to read them. SMPP carries
# `typeOfNumber`/`numberingPlanIndicator` alongside every address, and getting them wrong is a common cause of an SMSC
# silently misrouting an otherwise correct MSISDN.
#
# Where a plain `string` is accepted instead of this record, it is shorthand for
# `{value: <the string>}` — i.e. the defaults below.
public type Address record {|
    # The address itself. For `TON_INTERNATIONAL` this is E.164 **without** a leading `+`
    # (the `typeOfNumber` field is what carries that meaning on the wire). Empty means "absent",
    # which is spec-legal for a source address — and when it is empty, the connector
    # sends TON/NPI as Unknown/Unknown regardless of the fields below (§4.4.1: a NULL
    # address and its TON/NPI move together).
    string value;
    # Type of number. Defaults to `TON_INTERNATIONAL`, the right answer for an ordinary
    # MSISDN; short codes and alphanumeric sender IDs need an explicit value.
    TypeOfNumber typeOfNumber = TON_INTERNATIONAL;
    # Numbering plan. Defaults to `NPI_ISDN` (E.163/E.164), which pairs with the `typeOfNumber`
    # default above.
    NumberingPlanIndicator numberingPlanIndicator = NPI_ISDN;
|};

# Whether, and when, the SMSC should return a delivery receipt for a submitted message,
# per SMPP v3.4 §5.2.17.
#
# Three members, not four: jsmpp also defines `SUCCESS` (`0x03`), but its own javadoc marks
# that as introduced in SMPP 5.0, and `xxxxxx11` is *reserved* in the v3.4 table this
# connector implements.
public enum DeliveryReceiptRequest {
    # `xxxxxx00` — no receipt. The SMPP default.
    NONE,
    # `xxxxxx01` — a receipt on final delivery or final failure.
    ON_SUCCESS_OR_FAILURE,
    # `xxxxxx10` — a receipt only if delivery ultimately fails.
    ON_FAILURE_ONLY
}

# How an outbound message's text is encoded, and hence the `data_coding` it is sent with.
#
# Only the three schemes this connector also **decodes** precisely are offered. The GSM
# 03.38 7-bit default alphabet (`data_coding 0x00`) is **not** available for sending:
# packing text into septets is protocol logic the underlying library does not provide and
# this connector deliberately does not add.
#
# If your SMSC requires `data_coding 0x00`, send a `BinarySms` with `dataCoding: 0` and
# the bytes you want. Note the common case needs no encoder at all: for text that is
# **pure ASCII**, the GSM 03.38 default alphabet agrees with ASCII on every character
# except `@` (0x00 in GSM-7) and a handful of currency/accented symbols, and most SMSCs
# accept unpacked one-byte-per-character `0x00` payloads — so `shortMessageBytes` holding
# the plain ASCII bytes is usually exactly what is wanted. For anything beyond ASCII under
# `0x00`, you need a real GSM-7 encoder of your own.
public enum Encoding {
    # IA5/ASCII — `data_coding 0x01`. 7-bit US-ASCII only; anything else is rejected.
    ASCII,
    # Latin-1 — `data_coding 0x03`. The default: covers English, Afrikaans and most
    # Western European text. Note some carriers/aggregators accept only `0x00`
    # (their provisioned default) and `0x08`, and may reject or transcode `0x03`; for
    # pure-ASCII text, `ASCII` produces byte-identical payloads under
    # `data_coding 0x01` — a zero-cost switch if your SMSC dislikes `0x03`.
    LATIN1,
    # UCS-2 big-endian — `data_coding 0x08`. Any script, at half the characters per PDU.
    UCS2
}

# Fields shared by every outbound message shape. Not used directly — `submit`/
# `submitMulti`/`Caller.submit` take an `OutboundSms`, i.e. `TextSms` or `BinarySms`.
public type OutboundBase record {|
    # Recipient. A plain `string` is shorthand for an international ISDN address. ASCII
    # only: SMPP address fields are octet-counted C-octet strings, and this connector
    # rejects anything a platform charset could inflate on the wire.
    string|Address destinationAddress;
    # Sender. Omitted means the binding's configured default source address, which is the
    # usual arrangement: the short code is a property of the binding, not of each message.
    # ASCII only (see `destinationAddress`) — this includes `TON_ALPHANUMERIC` sender IDs.
    string|Address sourceAddress?;
    # Whether to ask the SMSC for a delivery receipt. A receipt arrives later — at a
    # `Listener`'s `onDeliverSm` with `deliveryReceipt` set, not as part of the submit.
    DeliveryReceiptRequest registeredDelivery = NONE;
    # SMPP `service_type`. Empty (the default) means the SMSC's default service. ASCII only.
    string serviceType = "";
    # SMPP `validity_period`: how long the SMSC should keep trying. Omitted means the
    # SMSC's own default. When set, it must be EXACTLY 16 characters in the §7.1.1 time
    # format `YYMMDDhhmmsstnnp` — absolute, e.g. `240115143000000+` (UTC+offset), or
    # relative, e.g. `000000020000000R` (2 hours). Any other length or shape is rejected
    # locally before anything reaches the wire.
    string validityPeriod?;
|};

# A text message: the connector encodes `shortMessage` per `encoding` and stamps the
# matching `data_coding` on the wire. This is the shape almost every caller wants.
public type TextSms record {|
    *OutboundBase;
    # The message text, encoded per `encoding`. Must not be empty: `sm_length = 0` means
    # "payload is in the message_payload TLV" (§5.2.21), which this connector never sets,
    # so an empty body is rejected locally instead of drawing `ESME_RINVMSGLEN`.
    string shortMessage;
    # How to encode `shortMessage`.
    Encoding encoding = LATIN1;
|};

# A pre-encoded payload, sent verbatim: the escape hatch for anything `Encoding` cannot
# express. The connector does not validate, split, or reassemble what you put in it.
public type BinarySms record {|
    *OutboundBase;
    # The payload octets, sent verbatim. Must not be empty (see `TextSms.shortMessage`).
    byte[] shortMessageBytes;
    # The raw `data_coding` byte describing how `shortMessageBytes` is encoded (0-255).
    # Required — the SMSC and handset can only interpret the payload through it.
    int dataCoding;
    # Sets the UDHI bit (`esm_class` bit 6, `0x40`): declares that `shortMessageBytes`
    # STARTS with a User Data Header — required by §5.2.12 whenever a UDH is present
    # (concatenation, WAP push, port addressing per 3GPP TS 23.040, where the first
    # octet is the UDH length). The connector does not validate the UDH structure:
    # setting `udhi` without a well-formed UDH at the front of the payload is a user
    # error nothing here can detect, and leaving it `false` WITH a UDH makes the
    # handset render the header octets as visible garbage text. Independent of
    # `registeredDelivery` and of the receipt bits the SMSC sets on inbound messages.
    boolean udhi = false;
|};

# A message to submit to the SMSC (`submit_sm`, `submit_multi`, or a `Caller.submit` reply).
#
# A union rather than one record with optional fields: text and binary payloads have
# different required fields (`encoding` belongs only to text, `dataCoding`/`udhi` only
# to binary), and the union makes the wrong combinations unrepresentable at compile
# time instead of runtime-rejected. In-line record literals pick their member by shape:
# `{destinationAddress, shortMessage: "hi"}` is a `TextSms`.
public type OutboundSms TextSms|BinarySms;

# The outcome of a successful `submit`/`Caller.submit` (`submit_sm`).
#
# `messageId` is required rather than optional: an SMSC that accepts a `submit_sm` must
# return one (§4.4.2), and typing it optional would push a nil check onto every caller for
# a case a conforming SMSC cannot produce. A non-conforming SMSC returning an empty id
# yields an empty string here — visible, rather than silently absent.
public type SubmitResult record {|
    # The SMSC's `message_id`. Correlate a later delivery receipt against this — see
    # `Sms.receiptedMessageId` for the caveat about which field actually carries it back.
    string messageId;
|};

# The outcome of a successful `submitMulti` (`submit_multi`) — one `message_id` for the
# whole batch, plus the subset of destinations the SMSC could not accept.
public type MultiSubmitResult record {|
    # The SMSC's `message_id` for the batch, as returned by `submit_multi_resp`.
    string messageId;
    # The destination addresses the SMSC reported as unsuccessful in the same
    # `submit_multi_resp` (its `unsuccess_sme` list), rendered as plain address strings.
    # Empty when every destination in the batch was accepted.
    string[] unsuccessfulAddresses = [];
|};

# The outcome of a successful `queryStatus` (`query_sm`) — the SMSC's current view of a
# previously submitted message's delivery state.
public type QueryResult record {|
    # The `final_date` field from `query_sm_resp`: the time the message reached a final
    # state, as the raw `yyMMddHHmm` wire value. `()` while the message has not yet
    # reached a final state (many SMSCs leave this empty until then). Carries no timezone
    # on the wire, for the same reason as `DeliveryReceipt.doneDate`.
    string? finalDate = ();
    # The message's current delivery state, from `query_sm_resp`'s `message_state`.
    # Reuses `DeliveryReceiptStatus` — jsmpp's `MessageState` enumerates the same set of
    # final/in-flight states as an Appendix-B delivery receipt's `stat:` token, just under
    # different Java identifier names (e.g. `MessageState.DELIVERED` maps to
    # `DELIVRD`, `UNDELIVERABLE` to `UNDELIV`, `ACCEPTED` to `ACCEPTD`, `REJECTED` to
    # `REJECTD`); the native layer performs that renaming so callers branch on one enum
    # regardless of whether the state came from a delivery receipt or a query.
    DeliveryReceiptStatus messageState;
    # The `error_code` field from `query_sm_resp` — an SMSC/network-specific error code
    # associated with the message, `0` when there is none.
    int errorCode = 0;
|};

# Transport-layer security (TLS) for the SMSC connection. Attach this to a `Client` or
# `Listener` connection configuration's `secureSocket` field to wrap the SMPP session in
# TLS. Whenever a `SecureSocket` is supplied, the SMSC's server certificate is verified
# against `cert`, and (unless `verifyHostName` is turned off) its subject is matched
# against the configured `host`.
public type SecureSocket record {|
    # Trust anchor used to verify the SMSC's server certificate. Either a
    # `crypto:TrustStore` (a PKCS12/JKS truststore file plus its password) or a path to
    # a PEM-encoded CA certificate. Required: a TLS connection with no way to
    # authenticate the peer is not a supported `SecureSocket` — use `InsecureSocket` if
    # you knowingly want an unverified dev/test connection.
    crypto:TrustStore|string cert;
    # Client key material for mutual TLS (mTLS), when the SMSC authenticates the ESME by
    # client certificate: a `crypto:KeyStore` (PKCS12/JKS keystore plus password). Omit
    # for ordinary one-way, server-authenticated TLS, which is what most SMSCs use.
    crypto:KeyStore key?;
    # Enabled TLS protocol versions, as JSSE protocol names. Defaults to TLS 1.3 and
    # TLS 1.2. TLS 1.1 and below are rejected at init — this connector enforces a TLS 1.2
    # floor and will not negotiate a downgraded, known-weak protocol even if configured to.
    string[] protocolVersions = ["TLSv1.3", "TLSv1.2"];
    # Enabled cipher suites, as JSSE suite names. Empty (the default) uses the JDK's
    # default suite set for the negotiated protocol, which already excludes the
    # known-broken suites on a current JDK. Leave it empty unless your SMSC requires a
    # specific suite.
    string[] ciphers = [];
    # Whether the SMSC certificate's subject must match the configured `host` (the
    # CN/SAN hostname check). Leave `true` for production. Setting `false` relaxes ONLY
    # the hostname match; the certificate chain is still fully verified against `cert`.
    # Use it when a test SMSC presents a certificate issued for a name other than the
    # one you dial.
    boolean verifyHostName = true;
|};

# DEV/TEST ONLY — a TLS connection with server-certificate verification turned off
# entirely. Supplying this in place of a `SecureSocket` still encrypts the wire, but
# accepts ANY certificate the peer presents (self-signed, expired, wrong-host, or
# attacker-substituted). That defeats the authentication half of TLS and leaves the
# connection open to a man-in-the-middle, so it must never point at a production SMSC.
# It exists only so a local or self-signed test SMSC can be exercised without minting a
# truststore first — prefer a real `SecureSocket` with a `crypto:TrustStore` even in
# tests where you reasonably can. A warning is logged at init whenever this is in effect.
public type InsecureSocket record {|
    # Must be written explicitly as `true`; the field is required and its type admits no
    # other value. The deliberate friction is the point: verification can never be
    # switched off by a defaulted field or a stray `false` left in a copied config —
    # reaching this state takes naming `InsecureSocket` and spelling the flag out.
    true disableSslVerification;
|};

# Internal, flat resolution of the `SecureSocket|InsecureSocket?` union — the native layer
# reads fixed fields off this record with no union-tag inspection (empty string = absent).
# Shared by `Client` and `Listener` init, both of which call `resolveTls()` below.
type ResolvedTls record {|
    # `true` only for `InsecureSocket`: chain verification is disabled entirely.
    boolean trustAll;
    # Truststore file path, when `cert` was a `crypto:TrustStore`; else empty.
    string trustStorePath;
    # Truststore password, when `cert` was a `crypto:TrustStore`; else empty.
    string trustStorePassword;
    # PEM CA-certificate path, when `cert` was a plain path string; else empty.
    string trustCertPath;
    # Keystore file path, when mTLS `key` was supplied; else empty.
    string keyStorePath;
    # Keystore password, when mTLS `key` was supplied; else empty.
    string keyStorePassword;
    # Validated (TLS 1.2 floor) protocol versions to enable.
    string[] protocolVersions;
    # Cipher suites to enable; empty = JDK defaults.
    string[] ciphers;
    # Whether to enable JSSE endpoint identification (hostname verification).
    boolean verifyHostName;
|};

# Validates a `SecureSocket` against this connector's TLS floor. Shared by `Client` and
# `Listener` init.
#
# + secureSocket - the TLS configuration to validate
# + return - an `Error` describing the first violated constraint, or `()` if valid
isolated function validateSecureSocket(SecureSocket secureSocket) returns error? {
    crypto:TrustStore|string cert = secureSocket.cert;
    if cert is string {
        if cert.trim().length() == 0 {
            return error Error("secureSocket.cert path must not be empty");
        }
    } else if cert.path.trim().length() == 0 {
        return error Error("secureSocket.cert.path (truststore path) must not be empty");
    }
    if secureSocket.protocolVersions.length() == 0 {
        return error Error("secureSocket.protocolVersions must enable at least one TLS version");
    }
    foreach string v in secureSocket.protocolVersions {
        if v != "TLSv1.2" && v != "TLSv1.3" {
            // Rejects TLSv1.1, TLSv1, SSLv3, SSLv2Hello, and typos alike: this connector
            // enforces a TLS 1.2 floor rather than letting the JVM maybe-negotiate a
            // known-weak protocol.
            return error Error(string `secureSocket.protocolVersions: '${v}' is not allowed - this connector negotiates only TLSv1.2 and TLSv1.3`);
        }
    }
}

# Collapses the public TLS union into `ResolvedTls` (or `()` for plaintext), logging the
# loud dev-only warning when an `InsecureSocket` is in effect. Shared by `Client` and
# `Listener` init.
#
# + secureSocket - the configured union, if any
# + return - the flat resolution, or `()` when the connection should stay plaintext
isolated function resolveTls(SecureSocket|InsecureSocket? secureSocket) returns ResolvedTls? {
    if secureSocket is () {
        return ();
    }
    if secureSocket is InsecureSocket {
        log:printWarn("SMPP TLS: server-certificate verification is DISABLED (InsecureSocket). "
                + "The connection is encrypted but NOT authenticated and is open to "
                + "man-in-the-middle. Never use this against a production SMSC.");
        return {
            trustAll: true,
            trustStorePath: "",
            trustStorePassword: "",
            trustCertPath: "",
            keyStorePath: "",
            keyStorePassword: "",
            protocolVersions: ["TLSv1.3", "TLSv1.2"],
            ciphers: [],
            verifyHostName: false
        };
    }
    string trustStorePath = "";
    string trustStorePassword = "";
    string trustCertPath = "";
    crypto:TrustStore|string cert = secureSocket.cert;
    if cert is crypto:TrustStore {
        trustStorePath = cert.path;
        trustStorePassword = cert.password;
    } else {
        trustCertPath = cert;
    }
    string keyStorePath = "";
    string keyStorePassword = "";
    crypto:KeyStore? key = secureSocket?.key;
    if key is crypto:KeyStore {
        keyStorePath = key.path;
        keyStorePassword = key.password;
    }
    return {
        trustAll: false,
        trustStorePath,
        trustStorePassword,
        trustCertPath,
        keyStorePath,
        keyStorePassword,
        protocolVersions: secureSocket.protocolVersions,
        ciphers: secureSocket.ciphers,
        verifyHostName: secureSocket.verifyHostName
    };
}

# A received short message (DELIVER_SM / DATA_SM) surfaced to a `Listener`'s attached
# service.
public type Sms record {|
    # Source address (sender MSISDN / short code).
    string sourceAddress;
    # Destination address (receiver).
    string destinationAddress;
    # The message payload, decoded according to the PDU's `data_coding` where that encoding
    # is unambiguous (IA5/ASCII, Latin-1, UCS2); falls back to UTF-8 otherwise — see
    # `shortMessageBytes` if you need to decode a GSM 7-bit default-alphabet payload (or
    # anything else the UTF-8 fallback gets wrong) yourself; `properties.dataCoding` tells
    # you when that's needed.
    string shortMessage;
    # The same payload as `shortMessage`, as raw undecoded bytes — captured after SMPP's
    # `message_payload`-over-`short_message` precedence rule is resolved but before any
    # charset decoding. Use this together with `properties.dataCoding` to decode a payload
    # yourself; re-decoding `shortMessage` is not a reliable way to recover the original
    # bytes once a lossy UTF-8 fallback has already been applied to them.
    byte[] shortMessageBytes = [];
    # `true` when this PDU is an SMSC delivery receipt (DLR) rather than a mobile-originated message.
    boolean deliveryReceipt = false;
    # The `receipted_message_id` TLV (0x001E) when the SMSC attached one — the spec's only
    # GUARANTEED correlation key (§5.3.2.12) between a delivery receipt and the
    # `SubmitResult.messageId` a submit returned. The Appendix-B body's `id:` field
    # (`receipt.id`) is vendor specific and can differ in radix; prefer this when present.
    # `()` when the receipt carries no TLV (many SMSCs only populate the body).
    string? receiptedMessageId = ();
    # Protocol metadata not promoted to a typed field above: `dataCoding` (`int`, raw
    # `data_coding` value), `sourceAddressTypeOfNumber`/`sourceAddressNumberingPlanIndicator`/`destinationAddressTypeOfNumber`/`destinationAddressNumberingPlanIndicator`
    # (`int`, address type-of-number/numbering-plan-indicator), `esmClass` (`int`, the raw
    # `esm_class` byte), and `udhi` (`boolean`, User Data Header Indicator — set for
    # concatenated/binary short messages, which this connector does not reassemble).
    map<anydata> properties = {};
    # The parsed delivery receipt, present only when this PDU is an SMSC delivery receipt
    # (`deliveryReceipt == true`) AND jsmpp could parse the Appendix-B receipt body. A
    # delivery receipt whose body doesn't conform to the format leaves this `()` — so
    # `deliveryReceipt == true` does NOT guarantee a non-nil `receipt`; the raw receipt text
    # is always available on `shortMessage`/`shortMessageBytes` regardless. `()` for ordinary
    # mobile-originated messages.
    DeliveryReceipt? receipt = ();
|};

# The final delivery state reported in an SMSC delivery receipt — the seven-character `stat:`
# token defined in SMPP v3.4 Appendix B, as parsed by jsmpp. Only these eight spec-defined
# tokens are represented; a delivery receipt whose `stat:` is a non-standard vendor token
# fails jsmpp's parse entirely (leaving `DeliveryReceipt` `()`), so this enum never carries an
# unknown value — read the raw `stat:` token from `Sms.shortMessage` if your SMSC is exotic.
# Also reused by `QueryResult.messageState` for `query_sm`'s `message_state` — see there.
public enum DeliveryReceiptStatus {
    # In transit; not yet in a final state.
    ENROUTE,
    # Delivered to the handset.
    DELIVRD,
    # Validity period expired before delivery.
    EXPIRED,
    # Deleted by the SMSC.
    DELETED,
    # Undeliverable — a terminal failure.
    UNDELIV,
    # Accepted by the SMSC on the recipient's behalf (no further delivery attempt).
    ACCEPTD,
    # State unknown to the SMSC.
    UNKNOWN,
    # Rejected by the SMSC.
    REJECTD
}

# A parsed SMSC delivery receipt (DLR), as produced by jsmpp's Appendix-B receipt parser
# (`DeliverSm.getShortMessageAsDeliveryReceipt`). This is a faithful surface of jsmpp's
# `DeliveryReceipt` — the connector adds no interpretation of its own. Every field is optional
# because real SMSCs diverge from the Appendix-B layout (omitting/reordering fields), so
# "field absent" is a routine, meaningful outcome. The full raw receipt is always available on
# `Sms.shortMessage`.
public type DeliveryReceipt record {|
    # The SMSC's message id for the original submission (Appendix-B `id:`). Appendix B is
    # "SMSC vendor specific": some SMSCs emit this in a different radix (hex vs decimal)
    # than the `message_id` they returned in the `submit_sm_resp`, so it is NOT a
    # guaranteed correlation key — `Sms.receiptedMessageId` (the §5.3.2.12 TLV) is.
    string id?;
    # The `sub:` count — messages originally submitted (usually 1). Advisory: many SMSCs
    # omit or zero-fill it.
    int submitted?;
    # The `dlvrd:` count — messages delivered (usually 1). Advisory, as `submitted`.
    int delivered?;
    # The `submit date:` field — the original submission time, as the ten-digit `yyMMddHHmm`
    # wire value (a receipt that carries seconds is normalized to `yyMMddHHmm`, matching
    # jsmpp's own receipt date format). It carries NO timezone on the wire, so it is surfaced
    # as a string rather than a `time` value (converting would assume the SMSC's local zone,
    # which is unknown, and present false precision). Parse it against your SMSC's documented
    # timezone.
    string submitDate?;
    # The `done date:` field — the final-state time; same format and same no-timezone caveat
    # as `submitDate`.
    string doneDate?;
    # The final delivery state, from the Appendix-B `stat:` token. See `DeliveryReceiptStatus`.
    DeliveryReceiptStatus finalStatus?;
    # The `err:` field — a network/SMSC-specific error code (typically three characters), NOT
    # standardized by SMPP; present on a failure when the SMSC populates it. (Named
    # `errorCode` because `error` is a reserved Ballerina identifier.)
    string errorCode?;
    # The `Text:` field — a short (≤20 char per Appendix B) echo of the original message.
    # Advisory only; use `Sms.shortMessage` for the full receipt body.
    string text?;
|};

# How a submit-family operation (`submit_sm`, `submit_multi`, `data_sm`, `query_sm`,
# `cancel_sm`, `replace_sm`, or a `Listener` `Caller.submit`) failed, mapped from the jsmpp
# exception that surfaced it. The six members deliberately partition by WHAT THE CALLER
# SHOULD DO, not by exception class:
public enum FailureMode {
    # The SMSC answered with a negative `command_status` — it received the request and
    # said no. `ErrorDetail.commandStatus` carries the exact status. The request was NOT
    # accepted; whether a retry can succeed depends on the status (throttling: yes, after
    # backing off; invalid destination: no).
    REJECTED,
    # No response arrived within `transactionTimeout`. The worst outcome the send path
    # has: SMPP gives no way to tell "the SMSC never got it" from "the SMSC accepted it
    # and the response was slow or lost" — so retrying a submit MAY DELIVER A DUPLICATE to
    # the subscriber. Decide per use case; for billing-relevant traffic, prefer
    # reconciling via delivery receipts / `queryStatus` over blind retry.
    TIMEOUT_DELIVERY_UNKNOWN,
    # The link is the problem: it died while sending or waiting, OR it was already
    # down/rebinding when the operation was attempted (one bucket for one operational
    # condition). For a mid-flight death the message may or may not have reached the SMSC
    # (`ErrorDetail.possiblySubmitted` tells you which, for submit-shaped operations); for
    # an already-down link nothing was sent. If a `Listener`'s `rebindPolicy` is enabled,
    # retry once rebound; when rebinding is disabled or exhausted the operation fails with
    # `LINK_ABANDONED` instead, so this member always means "worth retrying later".
    LINK_DOWN,
    # The link is down and this connector will NOT try to restore it: `rebindPolicy`
    # was disabled (`maxRebindAttempts: 0`) at the time of the drop, or its attempts
    # are exhausted. Unlike `LINK_DOWN`, retrying against this bound session is futile for
    # the rest of its life — the only remedy is a new `Client`/`Listener`. Nothing was sent.
    LINK_ABANDONED,
    # This connector refused to send: the request failed local validation (oversize,
    # unencodable character, bad field) or the lifecycle/config does not permit the
    # operation (not started, stopped, wrong bind type for the operation). Nothing reached
    # the wire; fix the request or the configuration. (A down/rebinding LINK is
    # `LINK_DOWN`, not this.)
    INVALID_REQUEST,
    # jsmpp raised something outside the four categories above (a malformed response,
    # an unexpected runtime failure inside the client). Not safely classifiable;
    # treat like `TIMEOUT_DELIVERY_UNKNOWN` for retry purposes.
    PROTOCOL_ERROR
}

# The detail record carried by `Error`. Deliberately **open** with all-optional fields:
# closed would turn every `e.detail()["anything"]` a user wrote into a compile error, and
# openness costs typed reads nothing. Fields are populated on submit-family operation
# failures; errors from other paths (config validation, start/connect, drops) may carry
# none.
public type ErrorDetail record {
    # Which way the operation failed — the field to branch retry logic on.
    FailureMode failureMode?;
    # The SMPP `command_status` from the negative response, when `failureMode` is
    # `REJECTED`. Compare against SMPP v3.4 §5.1.3 (e.g. 0x00000058 = ESME_RTHROTTLED).
    int commandStatus?;
    # Whether the message may already have reached the SMSC (submit-family operations
    # only) — the single most useful bit for retry logic. `false`: retrying CANNOT
    # duplicate the message (it either provably never left this connector, or the SMSC
    # received it and definitively refused it). `true`: the SMSC may have accepted it
    # (response lost or unusable, or the link died mid-flight), so a retry MAY DELIVER A
    # DUPLICATE to the subscriber. Populated on every submit-family failure.
    boolean possiblySubmitted?;
};

# The distinct error type raised by this SMPP connector. Returned from `Client`/`Listener`
# init on invalid configuration, from a connect/bind on a failed connect/bind, from a
# submit-family operation on failure (with `ErrorDetail` populated — see `FailureMode`),
# and passed to a `Listener` service's `onError` method on an unexpected session drop.
# Match it with `err is smpp:Error` to distinguish connector errors from other errors in
# your handler.
public type Error distinct error<ErrorDetail>;
