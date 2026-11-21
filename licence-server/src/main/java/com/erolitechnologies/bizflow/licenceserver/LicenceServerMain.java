package com.erolitechnologies.bizflow.licenceserver;

import com.erolitechnologies.bizflow.licensing.LicenceSigner;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;

/**
 * EROLITECHNOLOGIES licence server. Run on a machine YOU control; it holds the PRIVATE signing key (data/signing-private.key).
 *
 *   java -jar licence-server.jar init   --data ./data              (creates key pair; prints the PUBLIC key for the desktop app)
 *   java -jar licence-server.jar serve  --data ./data --port 8443 --keystore server.p12 [--dev]
 *   java -jar licence-server.jar issue-offline --data ./data --key XXXXX-... --device DEVICEID
 *
 * Environment: LICENCE_ADMIN_TOKEN (>= 24 chars), LICENCE_KEYSTORE_PASSWORD (for --keystore).
 */
public final class LicenceServerMain {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        String cmd = args[0];
        Path data = Path.of(opt(args, "--data", "./data"));
        switch (cmd) {
            case "init" -> init(data);
            case "serve" -> serve(data, args);
            case "issue-offline" -> {
                LicenceManager m = manager(data);
                System.out.println(m.activate(req(args, "--key"), req(args, "--device")));
            }
            default -> usage();
        }
    }

    static void init(Path data) throws Exception {
        Files.createDirectories(data);
        Path priv = data.resolve("signing-private.key"), pub = data.resolve("licence-public-key.txt");
        if (Files.exists(priv)) { System.err.println("A signing key already exists at " + priv + ". Refusing to overwrite it (that would invalidate every issued licence)."); System.exit(2); }
        KeyPair kp = LicenceSigner.generateKeyPair();
        Files.writeString(priv, Base64.getMimeEncoder().encodeToString(kp.getPrivate().getEncoded()));
        try { Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
        String pubB64 = Base64.getMimeEncoder().encodeToString(kp.getPublic().getEncoded());
        Files.writeString(pub, pubB64 + "\n");
        System.out.println("Created " + priv + "  (KEEP SECRET, BACK UP OFFLINE, NEVER SHIP IT)");
        System.out.println("Public key written to " + pub + " -> copy it to desktop/src/main/resources/licence-public-key.txt and rebuild the app.");
    }

    static LicenceManager manager(Path data) throws Exception {
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(Files.readString(data.resolve("signing-private.key")).trim())));
        return new LicenceManager(new LicenceStore(data.resolve("licences.db")), new LicenceSigner(key), Clock.systemUTC());
    }

    static void serve(Path data, String[] args) throws Exception {
        boolean dev = java.util.Arrays.asList(args).contains("--dev");
        String adminToken = System.getenv("LICENCE_ADMIN_TOKEN");
        if (adminToken == null || adminToken.length() < 24) { System.err.println("Set LICENCE_ADMIN_TOKEN to a random value of at least 24 characters."); System.exit(2); }
        int port = Integer.parseInt(opt(args, "--port", "8443"));
        String ks = opt(args, "--keystore", null);
        HttpServer server;
        if (ks != null) {
            char[] pw = System.getenv().getOrDefault("LICENCE_KEYSTORE_PASSWORD", "").toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(ks))) { store.load(in, pw); }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, pw);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            HttpsServer https = HttpsServer.create(new InetSocketAddress(port), 50);
            https.setHttpsConfigurator(new HttpsConfigurator(ctx));
            server = https;
        } else if (dev) {
            System.err.println("WARNING: --dev serves plain HTTP on localhost only. Never use for real customers.");
            server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 50);
        } else {
            System.err.println("Refusing to serve without TLS. Provide --keystore server.p12 (or put a TLS reverse proxy in front and use --dev on localhost).");
            System.exit(2); return;
        }
        new LicenceHttpApi(manager(data), adminToken).register(server);
        server.start();
        System.out.println("Licence server listening on port " + port);
    }

    private static String opt(String[] a, String name, String def) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(name)) return a[i + 1];
        return def;
    }

    private static String req(String[] a, String name) {
        String v = opt(a, name, null);
        if (v == null) { System.err.println("Missing " + name); System.exit(2); }
        return v;
    }

    private static void usage() {
        System.out.println("Usage: init | serve | issue-offline   (see class documentation)");
    }
}
