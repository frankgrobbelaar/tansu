# R8 rules for tansu's release build. Libraries that need keep rules get them here, with a line on why.

# BouncyCastle's JCA provider registers its algorithms by class name, and sshj and smbj look them up
# through it, so R8 can't see which ones are used.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# sshj builds its ciphers, MACs, key exchanges and key formats from factories found by name;
# ed25519 comes from the EdDSA library the same way.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }

# smbj wires its connection with an event bus (mbassador) that finds handlers by annotation.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }

# Parts of smbj, sshj and mbassador tansu doesn't use: Kerberos sign-in and expression filters.
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.el.**
