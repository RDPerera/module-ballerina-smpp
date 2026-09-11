import ballerina/smpp;

listener smpp:Listener lis = new ("localhost", "x", "y");

service on lis {
    remote isolated function onDeliverSm(smpp:Sms sms, string... extras) returns error? {
    }

    remote isolated function onDataSm(smpp:Sms sms, int count) returns error? {
    }
}
