// The code-action anchor: an empty service implements nothing, so nothing would ever
// be dispatched. (This is also where the editor offers the five handler templates.)
import ballerina/smpp;

listener smpp:Listener lis = new ("localhost", "x", "y");

service on lis {
}
