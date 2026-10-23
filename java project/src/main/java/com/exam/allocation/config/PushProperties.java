package com.exam.allocation.config;

import jakarta.annotation.PostConstruct;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Base64;

/**
 * Self-hosted Web Push (VAPID) settings bound from {@code application.properties}
 * (prefix {@code app.push}), each overridable through an environment variable:
 *
 * <ul>
 *   <li>{@code VAPID_PUBLIC_KEY} - base64url public key handed to the browser for
 *       {@code pushManager.subscribe()}; public by design.</li>
 *   <li>{@code VAPID_PRIVATE_KEY} - base64url private key that signs requests
 *       towards the browser's push service; <b>secret, server-side only</b>.</li>
 *   <li>{@code VAPID_SUBJECT} - {@code mailto:} or {@code https:} contact per RFC 8292.</li>
 * </ul>
 *
 * <p>Key format matches the {@code nl.martijndwars:web-push} library: base64url
 * of the raw uncompressed P-256 point (public) / the EC private scalar (private).
 *
 * <p>When keys are missing the server generates an <b>ephemeral</b> pair so local
 * development works out of the box (same behaviour as the reference notification
 * tooling) and logs a warning: subscriptions signed with an ephemeral key break
 * on restart, so production must set both variables explicitly.
 */
@Component
@ConfigurationProperties(prefix = "app.push")
public class PushProperties {

    private static final Logger log = LoggerFactory.getLogger(PushProperties.class);

    private String vapidPublicKey = "";
    private String vapidPrivateKey = "";
    private String vapidSubject = "mailto:admin@localhost";

    /** True when the keys in use were generated for this run (dev only). */
    private boolean ephemeral;

    @PostConstruct
    void ensureVapidKeys() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        boolean hasPublic = notBlank(vapidPublicKey);
        boolean hasPrivate = notBlank(vapidPrivateKey);
        if (hasPublic && hasPrivate) {
            vapidPublicKey = vapidPublicKey.trim();
            vapidPrivateKey = vapidPrivateKey.trim();
            return;
        }
        if (hasPublic != hasPrivate) {
            log.warn("Only one VAPID key is configured - both VAPID_PUBLIC_KEY and "
                    + "VAPID_PRIVATE_KEY are required. Generating an ephemeral pair instead.");
        }
        generateEphemeralKeys();
    }

    private void generateEphemeralKeys() {
        try {
            ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("prime256v1");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", "BC");
            generator.initialize(spec);
            KeyPair pair = generator.generateKeyPair();
            vapidPublicKey = Base64.getUrlEncoder()
                    .encodeToString(Utils.encode((ECPublicKey) pair.getPublic()));
            vapidPrivateKey = Base64.getUrlEncoder()
                    .encodeToString(Utils.encode((ECPrivateKey) pair.getPrivate()));
            ephemeral = true;
            log.warn("VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY are not set - generated an "
                    + "EPHEMERAL key pair for local development. Existing push subscriptions "
                    + "break on restart; set real keys before any production deployment.");
        } catch (GeneralSecurityException | RuntimeException ex) {
            vapidPublicKey = "";
            vapidPrivateKey = "";
            log.warn("Could not generate VAPID keys: {}. Web push sending stays disabled; "
                    + "in-app notifications still work.", ex.getMessage());
        }
    }

    /** True when the server can sign Web Push requests. */
    public boolean isConfigured() {
        return notBlank(vapidPublicKey) && notBlank(vapidPrivateKey) && notBlank(vapidSubject);
    }

    public String getVapidPublicKey() { return vapidPublicKey; }
    public void setVapidPublicKey(String vapidPublicKey) { this.vapidPublicKey = vapidPublicKey; }

    public String getVapidPrivateKey() { return vapidPrivateKey; }
    public void setVapidPrivateKey(String vapidPrivateKey) { this.vapidPrivateKey = vapidPrivateKey; }

    public String getVapidSubject() { return vapidSubject; }
    public void setVapidSubject(String vapidSubject) { this.vapidSubject = vapidSubject; }

    public boolean isEphemeral() { return ephemeral; }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
}
