package com.exam.allocation.config;

import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import java.security.Security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushPropertiesTest {

    @Test
    void generatesARoundTrippableEphemeralKeyPairWhenNoneIsConfigured() throws Exception {
        PushProperties properties = new PushProperties();
        properties.setVapidPublicKey("");
        properties.setVapidPrivateKey("");
        properties.setVapidSubject("mailto:admin@localhost");

        properties.ensureVapidKeys();   // package-private @PostConstruct

        assertTrue(properties.isConfigured());
        assertTrue(properties.isEphemeral());
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        // The encoded keys must load back into a matching pair - the same
        // format the web-push library signs with and the browser decodes.
        assertTrue(Utils.verifyKeyPair(
                Utils.loadPrivateKey(properties.getVapidPrivateKey()),
                Utils.loadPublicKey(properties.getVapidPublicKey())));
    }

    @Test
    void configuredKeysAreKeptExactlyAsSupplied() {
        PushProperties properties = new PushProperties();
        properties.setVapidPublicKey("configured-public");
        properties.setVapidPrivateKey("configured-private");
        properties.setVapidSubject("mailto:ops@college.example");

        properties.ensureVapidKeys();

        assertEquals("configured-public", properties.getVapidPublicKey());
        assertEquals("configured-private", properties.getVapidPrivateKey());
        assertFalse(properties.isEphemeral());
        assertTrue(properties.isConfigured());
    }

    @Test
    void aPartiallyConfiguredPairIsReplacedNotHalfUsed() {
        PushProperties properties = new PushProperties();
        properties.setVapidPublicKey("only-public");
        properties.setVapidPrivateKey("");

        properties.ensureVapidKeys();

        // Never sign with half a pair: both keys are regenerated together.
        assertFalse(properties.getVapidPublicKey().equals("only-public"));
        assertFalse(properties.getVapidPrivateKey().isBlank());
        assertTrue(properties.isEphemeral());
    }
}
