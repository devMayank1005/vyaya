/**
 * Robolectric's Conscrypt fails to start on this JDK and, once it has, breaks javax.crypto for the rest of the test
 * JVM (JarVerifier). The JDK's own AES-GCM and HMAC are used in every test instead; on a phone the app uses Android's.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
package app.vyaya.android;

import org.robolectric.annotation.ConscryptMode;
