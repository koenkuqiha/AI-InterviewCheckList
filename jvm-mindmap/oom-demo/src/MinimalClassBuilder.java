import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 零依赖的最小 class 文件字节码生成器。
 *
 * 目标：生成一个合法的 `public class GenN {}` 的 class 字节，用于撑爆 Metaspace 演示。
 * 生成的类【不含任何方法】，只有类头/常量池/空的 fields/methods/attributes，
 * 因此最小且合法，defineClass 能成功，不会触发 VerifyError。
 *
 * class 文件结构：
 *   u4 magic=0xCAFEBABE
 *   u2 minor_version=0
 *   u2 major_version=52 (Java 8，JDK17 向下兼容)
 *   u2 constant_pool_count
 *   cp_info constant_pool[cp_count-1]
 *   u2 access_flags
 *   u2 this_class
 *   u2 super_class
 *   u2 interfaces_count=0
 *   u2 fields_count=0
 *   u2 methods_count=0
 *   u2 attributes_count=0
 *
 * 我们的常量池只需要两个条目：
 *   #1 = Class -> #2 (this_class)
 *   #2 = Utf8 -> "java/lang/Object" (super_class 实际指向这个 Class 项)
 * 更规范一点：super_class 也要一个 Class 项指向 Object 的 Utf8，所以用三个：
 *   #1 = Utf8 "GenN"        (this_class 的名字)
 *   #2 = Class -> #1
 *   #3 = Utf8 "java/lang/Object"
 *   #4 = Class -> #3        (super_class)
 */
final class MinimalClassBuilder {

    private MinimalClassBuilder() {}

    static byte[] build(String internalName) {
        // internalName 是内部形式，如 "Gen1"；不含包时直接用类名，含包时用 "pkg/Cls"
        String thisName = internalName.replace('.', '/');

        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        try (DataOutputStream dos = new DataOutputStream(bos)) {

            // ---- 固定头 ----
            dos.writeInt(0xCAFEBABE); // magic
            dos.writeShort(0);        // minor_version
            dos.writeShort(52);       // major_version = Java 8

            // ---- 常量池 ----
            // 计划条目：
            //   #1 Utf8 thisName
            //   #2 Class -> #1
            //   #3 Utf8 "java/lang/Object"
            //   #4 Class -> #3
            dos.writeShort(5); // constant_pool_count = 条目数 + 1

            byte[] thisBytes = thisName.getBytes("UTF-8");
            dos.writeByte(1);            // #1 CONSTANT_Utf8
            dos.writeShort(thisBytes.length);
            dos.write(thisBytes);

            dos.writeByte(7);            // #2 CONSTANT_Class
            dos.writeShort(1);           // -> #1

            byte[] objBytes = "java/lang/Object".getBytes("UTF-8");
            dos.writeByte(1);            // #3 CONSTANT_Utf8
            dos.writeShort(objBytes.length);
            dos.write(objBytes);

            dos.writeByte(7);            // #4 CONSTANT_Class
            dos.writeShort(3);           // -> #3

            // ---- 类结构 ----
            dos.writeShort(0x0021);      // access_flags = ACC_PUBLIC(0x0001) | ACC_SUPER(0x0020)
            dos.writeShort(2);           // this_class = #2
            dos.writeShort(4);           // super_class = #4
            dos.writeShort(0);           // interfaces_count
            dos.writeShort(0);           // fields_count
            dos.writeShort(0);           // methods_count
            dos.writeShort(0);           // attributes_count

        } catch (IOException e) {
            throw new IllegalStateException("构造 class 字节失败", e);
        }
        return bos.toByteArray();
    }
}