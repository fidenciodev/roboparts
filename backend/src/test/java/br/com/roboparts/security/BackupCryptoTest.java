package br.com.roboparts.security;

import static org.assertj.core.api.Assertions.*;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class BackupCryptoTest {
    @TempDir Path temporary;
    Method crypt;URLClassLoader loader;
    @BeforeEach void compileStandaloneUtility() throws Exception {
        Path source=Path.of("../scripts/BackupCrypto.java").toAbsolutePath();
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-encoding","UTF-8","-d",temporary.toString(),source.toString())).isZero();
        loader=new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()});
        crypt=loader.loadClass("BackupCrypto").getMethod("crypt",boolean.class,Path.class,Path.class,char[].class);
    }
    @AfterEach void close() throws Exception {if(loader!=null)loader.close();}
    void run(boolean encrypt,Path input,Path output,String phrase) throws Exception {
        try {crypt.invoke(null,encrypt,input,output,phrase.toCharArray());}
        catch(InvocationTargetException exception){throw (Exception)exception.getCause();}
    }
    @Test void portableBackupRoundTripAndWrongPhraseNeverPublishesPlaintext() throws Exception {
        Path original=temporary.resolve("source.zip"),backup=temporary.resolve("copy.rpb"),restored=temporary.resolve("restored.zip");
        Files.writeString(original,"dados de teste e chave privada — conteúdo");
        String phrase="Frase exclusiva de proteção dos backups 2026!";
        run(true,original,backup,phrase);
        assertThat(new String(Files.readAllBytes(backup),java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("dados de teste e chave privada");
        assertThatThrownBy(()->run(false,backup,restored,"Outra frase que nao corresponde ao backup!" )).isInstanceOf(Exception.class);
        assertThat(restored).doesNotExist();
        run(false,backup,restored,phrase);
        assertThat(Files.readAllBytes(restored)).isEqualTo(Files.readAllBytes(original));
        assertThatThrownBy(()->run(false,backup,restored,phrase)).isInstanceOf(java.io.IOException.class);
        byte[] tampered=Files.readAllBytes(backup);tampered[tampered.length-1]^=1;Files.write(backup,tampered);
        Path invalid=temporary.resolve("tampered.zip");
        assertThatThrownBy(()->run(false,backup,invalid,phrase)).isInstanceOf(Exception.class);
        assertThat(invalid).doesNotExist();
        try(var files=Files.list(temporary)){assertThat(files.filter(p->p.toString().endsWith(".part")).count()).isZero();}
    }
}
