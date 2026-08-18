package ink.harsh.pipe.strenc;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Generates the tiny runtime decryptor injected into the shipped jar.
 *
 * The generated class is `ink.harsh.pipe.strenc.Codec` with one method,
 * `static String d(String)`, that reverses {@code StringEncryptor.cipher}. It is
 * generated rather than hand-compiled so the tool has no compiled resource to
 * keep in sync, and it lands in a neutral package the relocation step never
 * touches — so both the unrelocated PipePipe classes and the
 * `ink.harsh.pipe.shaded.*` NewPipe classes resolve the same symbol at runtime.
 *
 * Injected into exactly one jar (the primary), because all three share the app
 * classpath and a second copy would be a duplicate-class error at dex time.
 *
 * Equivalent Java:
 * <pre>
 *   public static String d(String s) {
 *       char[] a = s.toCharArray();
 *       for (int i = 0; i &lt; a.length; i++) {
 *           a[i] = (char) (a[i] ^ (KEY + i));
 *       }
 *       return new String(a);
 *   }
 * </pre>
 */
final class CodecGen {

    static byte[] generate(String internalName, int key) {
        // COMPUTE_FRAMES is safe here: this class touches only java.lang types,
        // which the default getCommonSuperClass resolves without a custom
        // classloader.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            internalName, null, "java/lang/Object", null);

        MethodVisitor m = cw.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "d",
            "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        m.visitCode();

        // char[] a = s.toCharArray();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
            "toCharArray", "()[C", false);
        m.visitVarInsn(Opcodes.ASTORE, 1);

        // int i = 0;
        m.visitInsn(Opcodes.ICONST_0);
        m.visitVarInsn(Opcodes.ISTORE, 2);

        Label loop = new Label();
        Label end = new Label();

        // while (i < a.length)
        m.visitLabel(loop);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitInsn(Opcodes.ARRAYLENGTH);
        m.visitJumpInsn(Opcodes.IF_ICMPGE, end);

        // a[i] = (char) (a[i] ^ (key + i));
        m.visitVarInsn(Opcodes.ALOAD, 1);          // array (for CASTORE)
        m.visitVarInsn(Opcodes.ILOAD, 2);          // index (for CASTORE)
        m.visitVarInsn(Opcodes.ALOAD, 1);          // array
        m.visitVarInsn(Opcodes.ILOAD, 2);          // index
        m.visitInsn(Opcodes.CALOAD);               // a[i]
        visitIntConst(m, key);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitInsn(Opcodes.IADD);                 // key + i
        m.visitInsn(Opcodes.IXOR);                 // a[i] ^ (key + i)
        m.visitInsn(Opcodes.I2C);
        m.visitInsn(Opcodes.CASTORE);

        // i++
        m.visitIincInsn(2, 1);
        m.visitJumpInsn(Opcodes.GOTO, loop);

        // return new String(a);
        m.visitLabel(end);
        m.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        m.visitInsn(Opcodes.DUP);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String",
            "<init>", "([C)V", false);
        m.visitInsn(Opcodes.ARETURN);

        m.visitMaxs(0, 0); // recomputed by COMPUTE_FRAMES
        m.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void visitIntConst(MethodVisitor m, int value) {
        if (value >= -1 && value <= 5) {
            m.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            m.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            m.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            m.visitLdcInsn(value);
        }
    }

    private CodecGen() {
    }
}
