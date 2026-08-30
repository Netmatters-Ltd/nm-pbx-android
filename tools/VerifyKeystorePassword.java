import java.io.*;
import java.security.KeyStore;
import java.util.*;

/**
 * Prompts for the keystore password, checks it against app/nmpbx-upload.jks and,
 * on success, writes correctly escaped storePassword/keyPassword entries into
 * keystore.properties (Properties.load() treats backslashes as escapes, so any
 * backslash in the password has to be doubled in the file).
 *
 * Usage from the project root:
 *   javac -d build/tools tools/VerifyKeystorePassword.java
 *   java -cp build/tools VerifyKeystorePassword
 */
public class VerifyKeystorePassword {
    private static final String KEYSTORE = "app/nmpbx-upload.jks";
    private static final String PROPS = "keystore.properties";

    public static void main(String[] args) throws Exception {
        Console console = System.console();
        if (console == null) {
            System.out.println("No interactive console available. Run this from a normal terminal.");
            return;
        }

        char[] password = console.readPassword("Keystore password: ");
        byte[] store = new FileInputStream(KEYSTORE).readAllBytes();

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try {
            keyStore.load(new ByteArrayInputStream(store), password);
        } catch (IOException e) {
            System.out.println("That password does not open " + KEYSTORE);
            System.out.println("  " + e.getMessage());
            return;
        }
        System.out.println("Password accepted. Aliases: " + Collections.list(keyStore.aliases()));

        String escaped = new String(password).replace("\\", "\\\\");
        List<String> lines = new ArrayList<>();
        for (String line : new String(new FileInputStream(PROPS).readAllBytes(), "ISO-8859-1").split("\n")) {
            String body = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (body.startsWith("storePassword=")) {
                body = "storePassword=" + escaped;
            } else if (body.startsWith("keyPassword=")) {
                body = "keyPassword=" + escaped;
            }
            lines.add(body);
        }
        try (Writer out = new OutputStreamWriter(new FileOutputStream(PROPS), "ISO-8859-1")) {
            out.write(String.join("\n", lines));
        }
        System.out.println("Wrote escaped password into " + PROPS + " - the Gradle build should now sign.");
    }
}
