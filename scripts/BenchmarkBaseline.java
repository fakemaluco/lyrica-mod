import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Creates a local QA-only baseline: original renderer plus the identical offline fixture and frame timer. */
public final class BenchmarkBaseline {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("original.jar updated.jar qa-baseline.jar");
        String renderer = "dev/relaylyrics/client/ui/WorldLyricsRenderer.class";
        List<String> fixtures = List.of("dev/relaylyrics/client/ui/WorldLyricsProbe.class",
                "dev/relaylyrics/client/ui/WorldLyricsContextProbe.class", "dev/relaylyrics/client/ui/WorldLyricsFrameProbe.class");
        try (ZipFile original = new ZipFile(args[0]); ZipFile updated = new ZipFile(args[1]);
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(Path.of(args[2])))) {
            for (var entry : Collections.list(original.entries())) {
                if (entry.isDirectory() || fixtures.contains(entry.getName())) continue;
                byte[] bytes = original.getInputStream(entry).readAllBytes();
                if (entry.getName().equals(renderer)) {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(reader, 0);
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                  String signature, String[] exceptions) {
                            var method = super.visitMethod(access, name, descriptor, signature, exceptions);
                            if (!name.equals("render")) return method;
                            return new MethodVisitor(Opcodes.ASM9, method) {
                                @Override public void visitCode() {
                                    super.visitCode();
                                    visitMethodInsn(Opcodes.INVOKESTATIC, "dev/relaylyrics/client/ui/WorldLyricsFrameProbe", "frame", "()V", false);
                                }
                            };
                        }
                    }, 0);
                    bytes = writer.toByteArray();
                }
                output.putNextEntry(new ZipEntry(entry.getName())); output.write(bytes); output.closeEntry();
            }
            for (String fixture : fixtures) {
                var entry = updated.getEntry(fixture);
                if (entry == null) throw new IllegalArgumentException("Updated build lacks fixture " + fixture);
                output.putNextEntry(new ZipEntry(fixture)); output.write(updated.getInputStream(entry).readAllBytes()); output.closeEntry();
            }
        }
    }
}
