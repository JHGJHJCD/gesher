package gesher.services;

import com.gesher.app.LogLines;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** Runs actual Android libcore channels/decoding instead of a host file adapter. */
public final class LauncherLogBoundsTest {
    public static void main(String[] args) throws Exception {
        File file = File.createTempFile("gesher-log-bounds-", ".log");
        try {
            byte[] row = "שלום line\r\n".getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = new FileOutputStream(file)) {
                for (int i = 0; i < 100000; i++) output.write(row);
            }
            List<String> tail = LogLines.readTail(file, 40);
            if (tail.size() != 40 || !tail.get(0).equals("שלום line")) {
                throw new AssertionError("UTF-8/CRLF tail differs");
            }
            Files.write(file.toPath(), new byte[] {(byte) 0xff, '\n', 'o', 'k', '\n'});
            if (!LogLines.readTail(file, 1).equals(Arrays.asList("ok"))) {
                throw new AssertionError("Discarded prefix suppressed valid diagnostics");
            }
            try (OutputStream output = new FileOutputStream(file)) {
                byte[] chunk = new byte[8192];
                Arrays.fill(chunk, (byte) 'x');
                for (int i = 0; i < 2048; i++) output.write(chunk);
            }
            tail = LogLines.readTail(file, 40);
            if (tail.size() != 1 || tail.get(0).length() > 1048700 ||
                    !tail.get(0).startsWith("[gesher: earlier log content omitted] ")) {
                throw new AssertionError("Long-line budget or omission label missing");
            }
            System.out.println("LauncherLogBoundsTest all passed: Android channels, UTF-8, CRLF and long-line budget");
        } finally {
            if (!file.delete()) throw new AssertionError("Temporary log cleanup failed");
        }
    }
}
