package gg.aurora;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Clé de profil Mojang du joueur (la paire de clés RSA qui signe le chat, fournie et signée par
 * Mojang aux comptes premium). Sert à prouver l'identité au serveur Paranoia.
 */
public record ProfileKeys(long expiresAt, byte[] publicKeyDer, byte[] mojangSignature, PrivateKey privateKey) {

    public byte[] sign(byte[] data) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(privateKey);
        s.update(data);
        return s.sign();
    }

    /** Clé du compte connecté, ou null si indisponible (compte non premium, Mojang injoignable…). */
    public static ProfileKeys get(Minecraft mc) {
        String fake = System.getProperty("aurora.fakemojang", "");
        if (!fake.isEmpty()) return fake(mc, Path.of(fake));
        try {
            Optional<ProfileKeyPair> kp = mc.getProfileKeyPairManager().prepareKeyPair().get(20, TimeUnit.SECONDS);
            if (kp.isEmpty()) return null;
            ProfilePublicKey.Data d = kp.get().publicKey().data();
            return new ProfileKeys(d.expiresAt().toEpochMilli(), d.key().getEncoded(), d.keySignature(), kp.get().privateKey());
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Clé de profil Mojang indisponible : {}", e.toString());
            return null;
        }
    }

    /**
     * Outil de test uniquement (-Daurora.fakemojang=fichier) : une fausse « clé Mojang » signe une clé
     * de joueur générée ici, avec les données calculées par Minecraft lui-même (signedPayload).
     * Le serveur local fait confiance à cette clé de test via MOJANG_TEST_KEY.
     */
    private static ProfileKeys fake(Minecraft mc, Path mojangKeyFile) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey mojang = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(mojangKeyFile).trim())));
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair player = gen.generateKeyPair();
            Instant expires = Instant.now().plusSeconds(3600);
            ProfilePublicKey.Data unsigned = new ProfilePublicKey.Data(expires, player.getPublic(), new byte[0]);
            var m = ProfilePublicKey.Data.class.getDeclaredMethod("signedPayload", UUID.class);
            m.setAccessible(true);
            byte[] payload = (byte[]) m.invoke(unsigned, mc.getUser().getProfileId());
            Signature s = Signature.getInstance("SHA1withRSA");
            s.initSign(mojang);
            s.update(payload);
            return new ProfileKeys(expires.toEpochMilli(), player.getPublic().getEncoded(), s.sign(), player.getPrivate());
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Fausse clé de test : {}", e.toString());
            return null;
        }
    }
}
