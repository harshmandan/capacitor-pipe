package ink.harsh.pipe.strenc;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Encrypts identifying string literals inside a built extractor jar, so a
 * `strings`/grep over a shipped APK does not reveal that the app extracts
 * YouTube.
 *
 * <p>This runs on FINISHED jars, after the extractors are built and (for
 * NewPipe) relocated. It rewrites two forms in which a plaintext string reaches
 * the class file, and only those two — see {@link #ENCRYPTABLE}:
 *
 * <ul>
 *   <li>every {@code ldc "..."} that loads a matching literal, replaced with
 *       {@code ldc <ciphertext>} + a call to the injected {@code Codec.d(String)};</li>
 *   <li>every {@code static final String} whose {@code ConstantValue} attribute
 *       is a matching literal — the attribute is stripped and the field is
 *       assigned from {@code Codec.d(...)} in {@code <clinit>} instead, so
 *       reflective reads still see the real value.</li>
 * </ul>
 *
 * <p>The cipher is XOR with a position-dependent key. This is obfuscation, not
 * cryptography: it defeats a static scan, not a debugger that watches
 * {@code Codec.d} return. That boundary is the whole point and is documented in
 * ENCRYPTED-STRINGS.md.
 *
 * <p>Usage:
 * <pre>
 *   StringEncryptor &lt;in.jar&gt; &lt;out.jar&gt; &lt;injectCodec:true|false&gt; &lt;report.txt&gt;
 * </pre>
 * {@code injectCodec} adds the shared {@code Codec} class to this jar. Exactly
 * one jar in the shipped set must pass true; all jars reference the same
 * {@code ink/harsh/pipe/strenc/Codec}, so injecting it into more than one would
 * be a duplicate-class error at dex time, and into none an unresolved reference.
 */
public final class StringEncryptor {

    /** Kept in one place so {@code CodecGen} generation and encryption cannot drift apart. */
    static final int KEY = 0x5B;

    static final String CODEC_INTERNAL = "ink/harsh/pipe/strenc/Codec";

    /**
     * Substrings that mark a literal as identifying. Case-insensitive.
     *
     * Deliberately anchored on YouTube/Google hosts and clients, NOT on generic
     * protocol vocabulary. A string containing "youtube" is caught (so
     * `youtubei/v1` goes); a generic InnerTube key like "adaptiveFormats" is
     * not. ENCRYPTED-STRINGS.md explains why that line is where it is.
     *
     * "google" is intentionally NOT here on its own — it would sweep up
     * `com.google.protobuf.*` message strings, some of which protobuf-lite
     * resolves reflectively. The narrower host forms below cannot.
     */
    private static final String[] ENCRYPTABLE = {
        "youtube",
        "googlevideo",
        "googleapis",
        "googleusercontent",
        "ytimg",
        "gstatic",
        "innertube",
        "jnn-pa",
        "pipepipe.dev",
        "google.com/",
    };

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.err.println("usage: StringEncryptor <in.jar> <out.jar> <injectCodec> <report.txt>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        boolean injectCodec = Boolean.parseBoolean(args[2]);
        Path report = Paths.get(args[3]);

        TreeSet<String> encrypted = new TreeSet<>();
        int classesTouched = 0;

        try (JarFile jar = new JarFile(in.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                byte[] bytes = readAll(jar.getInputStream(entry));

                if (entry.getName().endsWith(".class")) {
                    Result r = transform(bytes, encrypted);
                    if (r.changed) {
                        classesTouched++;
                        bytes = r.bytes;
                    }
                }

                // Rebuild the entry so a rewritten size does not disagree with a
                // STORED entry's header; let the stream deflate afresh.
                JarEntry copy = new JarEntry(entry.getName());
                if (entry.getTime() != -1) {
                    copy.setTime(entry.getTime());
                }
                jos.putNextEntry(copy);
                jos.write(bytes);
                jos.closeEntry();
            }

            if (injectCodec) {
                jos.putNextEntry(new ZipEntry(CODEC_INTERNAL + ".class"));
                jos.write(CodecGen.generate(CODEC_INTERNAL, KEY));
                jos.closeEntry();
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String s : encrypted) {
            sb.append(s).append('\n');
        }
        Files.write(report, sb.toString().getBytes(StandardCharsets.UTF_8));

        System.out.printf(
            "strenc: %s -> %s  (%d classes, %d distinct strings%s)%n",
            in.getFileName(), out.getFileName(), classesTouched, encrypted.size(),
            injectCodec ? ", +Codec" : "");
    }

    private static final class Result {
        final byte[] bytes;
        final boolean changed;
        Result(byte[] bytes, boolean changed) {
            this.bytes = bytes;
            this.changed = changed;
        }
    }

    private static Result transform(byte[] classBytes, TreeSet<String> encrypted) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);

        // Never rewrite the codec itself, however it arrived.
        if (CODEC_INTERNAL.equals(cn.name)) {
            return new Result(classBytes, false);
        }

        boolean changed = false;

        // ---- ldc rewrites, in every method ----
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.LDC) {
                    continue;
                }
                Object cst = ((LdcInsnNode) insn).cst;
                if (!(cst instanceof String) || !shouldEncrypt((String) cst)) {
                    continue;
                }
                String plain = (String) cst;
                encrypted.add(plain);

                InsnList replacement = new InsnList();
                replacement.add(new LdcInsnNode(cipher(plain)));
                replacement.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, CODEC_INTERNAL, "d",
                    "(Ljava/lang/String;)Ljava/lang/String;", false));
                mn.instructions.insert(insn, replacement);
                mn.instructions.remove(insn);
                changed = true;
            }
        }

        // ---- ConstantValue fields ----
        List<FieldNode> movedToClinit = new ArrayList<>();
        for (FieldNode fn : cn.fields) {
            if (fn.value instanceof String && shouldEncrypt((String) fn.value)) {
                encrypted.add((String) fn.value);
                movedToClinit.add(fn);
                changed = true;
            }
        }
        if (!movedToClinit.isEmpty()) {
            MethodNode clinit = ensureClinit(cn);
            InsnList prelude = new InsnList();
            for (FieldNode fn : movedToClinit) {
                String plain = (String) fn.value;
                fn.value = null; // drops the ConstantValue attribute
                prelude.add(new LdcInsnNode(cipher(plain)));
                prelude.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, CODEC_INTERNAL, "d",
                    "(Ljava/lang/String;)Ljava/lang/String;", false));
                prelude.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, fn.name, fn.desc));
            }
            clinit.instructions.insert(prelude);
        }

        if (!changed) {
            return new Result(classBytes, false);
        }

        // COMPUTE_MAXS, not COMPUTE_FRAMES: only linear instructions were
        // inserted, so existing stack-map frames remain valid and need no
        // recomputation (which would require loading the whole class hierarchy).
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return new Result(cw.toByteArray(), true);
    }

    private static MethodNode ensureClinit(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name)) {
                return mn;
            }
        }
        MethodNode clinit = new MethodNode(
            Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(clinit);
        return clinit;
    }

    private static boolean shouldEncrypt(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        for (String needle : ENCRYPTABLE) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** XOR each char with KEY + position. Symmetric — the injected {@code Codec.d} reverses it. */
    static String cipher(String plain) {
        char[] c = plain.toCharArray();
        for (int i = 0; i < c.length; i++) {
            c[i] = (char) (c[i] ^ (KEY + i));
        }
        return new String(c);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            return s.readAllBytes();
        }
    }

    private StringEncryptor() {
    }
}
