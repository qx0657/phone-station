package dev.phonestation.adbkeep;

/** Launched only against the Python test's loopback TLS relay and generated test certificate. */
public final class ControlTlsFixture {
    public static void main(String[] args) throws Exception {
        try (RelaySocket socket = new RelaySocket(args[0], args[1], RelayTls.context(args[2]).getSocketFactory(), () -> {}, () -> {})) {
            if (args[3].equals("reject")) {
                try { socket.open(); throw new AssertionError("wrong SPKI accepted"); }
                catch (java.io.IOException expected) { System.out.println("REJECTED"); return; }
            }
            socket.open();
            System.out.println("READY"); System.out.flush();
            int executed = 0;
            while (executed < 2) {
                Json op = socket.next();
                if (op.get("type").string().equals("subscribe")) {
                    socket.subscribed(op.get("subscriptionId").string(), false, false); continue;
                }
                String id = op.get("operationId").string();
                Json body = Json.obj().put("jsonrpc","2.0").put("id",op.get("payload").get("id"))
                    .put("result", Json.obj().put("executed", ++executed));
                String result = Json.obj().put("operationId", id).put("response",Json.obj().put("status",200).put("body",body)).emit();
                if (socket.result(result) != 200) { throw new AssertionError("result not committed"); }
                if (socket.result(result) != 200) { throw new AssertionError("original result retry rejected"); }
            }
            System.out.println("DONE");
        }
    }
}
