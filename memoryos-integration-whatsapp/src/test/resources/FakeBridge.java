import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A stand-in for the whatsmeow bridge, run as a real child process by WhatsAppChannelAdapterTest. It speaks the
 * bridge's line protocol, records every command it reads in the file its database address names, and answers with
 * canned results. Two commands are its own: {@code emit} writes the event it carries, as WhatsApp's would, and
 * {@code exit} ends the process as a crash would.
 */
public class FakeBridge {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static int seq;
    /** UTF-8 whatever the locale, as the bridge writes; the adapter starts it with an empty environment. */
    private static final PrintStream OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    public static void main(String[] args) throws IOException {
        Path log = Path.of(System.getenv("MEMORYOS_WHATSAPP_DATABASE"));
        System.err.println("{\"level\":\"WARN\",\"module\":\"Bridge\",\"message\":\"fake bridge started\"}");
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            Files.writeString(log, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            JsonNode command = JSON.readTree(line);
            String op = command.path("op").asString("");
            long id = command.path("id").asLong();
            switch (op) {
                case "ack" -> { }
                case "shutdown" -> { return; }
                case "exit" -> System.exit(1);
                case "emit" -> {
                    ObjectNode event = (ObjectNode) command.path("event");
                    event.put("seq", ++seq);
                    write(event);
                    ok(id, null);
                }
                case "open" -> {
                    boolean pairing = command.path("phone").asString("").endsWith("9");
                    ok(id, JSON.createObjectNode().put("pairing", pairing));
                    if (pairing) {
                        write(JSON.createObjectNode().put("event", "qr").put("seq", ++seq)
                                .put("bot", command.path("bot").asString()).put("code", "2@fake-code")
                                .put("expiresAt", "2030-01-01T00:00:20Z"));
                    }
                }
                case "send" -> ok(id, JSON.createObjectNode().put("messageId", "S" + id));
                case "groups" -> {
                    var groups = JSON.createArrayNode();
                    groups.addObject().put("chat", "120363000000000001@g.us").put("name", "Phòng Kế toán")
                            .putArray("participants");
                    groups.addObject().put("chat", "120363000000000002@g.us").put("name", "").putArray("participants");
                    ok(id, groups);
                }
                case "group" -> ok(id, JSON.createObjectNode().put("chat", command.path("chat").asString())
                        .put("name", "Phòng Kế toán"));
                case "refuse" -> fail(id, "NOT_LINKED");
                default -> ok(id, null);
            }
        }
    }

    private static void ok(long id, JsonNode result) {
        var reply = JSON.createObjectNode().put("id", id).put("ok", true);
        if (result != null) reply.set("result", result);
        write(reply);
    }

    private static void fail(long id, String code) {
        write(JSON.createObjectNode().put("id", id).put("ok", false).put("error", code));
    }

    private static synchronized void write(JsonNode node) {
        OUT.println(JSON.writeValueAsString(node));
    }
}
