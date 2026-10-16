package br.com.roboparts.security;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SecurityPrimitivesTest {
    @TempDir Path temporary;
    static class MutableClock extends Clock {
        final AtomicLong time=new AtomicLong(1_000_000);
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(time.get());}
        public long millis(){return time.get();}
    }
    @Test void accountLimitNormalizesIdentityExpiresAndRejectsStaleSuccess() {
        var clock=new MutableClock();var limiter=new LoginAttemptLimiter(clock,2,60,10);
        var old=limiter.acquire(" Alice@Example.com ");
        assertThat(limiter.acquire("alice@example.com").allowed()).isTrue();
        assertThat(limiter.acquire("ALICE@example.com").allowed()).isFalse();
        clock.time.addAndGet(60_000);
        assertThat(limiter.acquire("alice@example.com").allowed()).isTrue();
        limiter.success(old);
        assertThat(limiter.acquire("alice@example.com").allowed()).isTrue();
        assertThat(limiter.acquire("alice@example.com").allowed()).isFalse();
        assertThat(old.toString()).doesNotContain("Alice","example.com",old.key());
    }
    @Test void successReleasesCurrentWindowAndCapacityFailsClosed() {
        var limiter=new LoginAttemptLimiter(new MutableClock(),1,60,1);
        var first=limiter.acquire("first@example.com");
        assertThat(limiter.acquire("second@example.com").allowed()).isFalse();
        limiter.success(first);
        assertThat(limiter.acquire("second@example.com").allowed()).isTrue();
    }
    @Test void concurrentRequestsReserveBeforePasswordVerification() throws Exception {
        var limiter=new LoginAttemptLimiter(new MutableClock(),5,60,100);
        try(var pool=Executors.newFixedThreadPool(12)) {
            var jobs=new ArrayList<Future<Boolean>>();
            for(int i=0;i<40;i++)jobs.add(pool.submit(()->limiter.acquire("same@example.com").allowed()));
            int allowed=0;for(var job:jobs)if(job.get(5,TimeUnit.SECONDS))allowed++;
            assertThat(allowed).isEqualTo(5);
        }
    }
    @ParameterizedTest @CsvSource({"59,94287082","1111111109,07081804","1111111111,14050471","1234567890,89005924","2000000000,69279037","20000000000,65353130"})
    void matchesRfc6238Sha1Vectors(long seconds,String expected) {
        String secret=Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        assertThat(Totp.code(secret,seconds/30,8)).isEqualTo(expected);
    }
    @Test void acceptsSmallClockDriftButRejectsReplayAndOutsideWindow() {
        String secret=Totp.newSecret();Instant now=Instant.ofEpochSecond(3000);
        String current=Totp.code(secret,100,6);
        assertThat(Totp.match(secret,current,now,-1)).isEqualTo(100);
        assertThat(Totp.match(secret,current,now,100)).isEqualTo(-1);
        assertThat(Totp.match(secret,Totp.code(secret,99,6),now,-1)).isEqualTo(99);
        assertThat(Totp.match(secret,Totp.code(secret,98,6),now,-1)).isEqualTo(-1);
        assertThat(Totp.match(secret,"00000",now,-1)).isEqualTo(-1);
        assertThat(secret).matches("[A-Z2-7]{32}");
    }
    @Test void encryptedSeedsSurviveRestartAndAreBoundToTheUser() throws Exception {
        Path key=temporary.resolve("private/security.key");UUID user=UUID.randomUUID();
        var first=new SecuritySecrets("",key.toString());
        String cipher=first.encrypt("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567",user);
        var restarted=new SecuritySecrets("",key.toString());
        assertThat(restarted.decrypt(cipher,user)).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567");
        assertThat(cipher).doesNotContain("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567");
        assertThat(first.encrypt("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567",user)).isNotEqualTo(cipher);
        assertThatThrownBy(()->restarted.decrypt(cipher,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        byte[] bytes=Base64.getDecoder().decode(cipher);bytes[bytes.length-1]^=1;
        assertThatThrownBy(()->restarted.decrypt(Base64.getEncoder().encodeToString(bytes),user)).isInstanceOf(IllegalStateException.class);
        assertThat(first.fingerprint(" A@EXAMPLE.com ")).isEqualTo(restarted.fingerprint("a@example.com")).hasSize(64);
    }
    @Test void corruptExistingKeyIsNeverReplacedOrPrinted() throws Exception {
        Path key=temporary.resolve("bad.key");Files.writeString(key,"private-invalid-key");
        assertThatThrownBy(()->new SecuritySecrets("",key.toString())).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private-invalid-key");
        assertThat(Files.readString(key)).isEqualTo("private-invalid-key");
    }
}
