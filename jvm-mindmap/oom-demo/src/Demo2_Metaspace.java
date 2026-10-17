import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景 2：java.lang.OutOfMemoryError: Metaspace
 *
 * 成因：类元信息( Class Metadata ) 区域被占满。注意 Metaspace 在【本地内存/Native Memory】，
 *       不在堆里，所以 -Xmx 调大也没用，必须单独设 -XX:MaxMetaspaceSize。
 *
 * 典型触发源（面试高频）：
 *   1. 【动态生成类】：CGLIB/ByteBuddy/Javassist 代理、Lambda 表达式、动态代理；
 *   2. 【频繁 new URLClassLoader】：热部署/插件化，每次 new 都开一份独立的 Metaspace，
 *      老 ClassLoader 没被 GC → 它们加载的类元信息也一起泄漏（最经典的泄漏模式）；
 *   3. 大量重复加载同名类（比如反复 redefine 同一个类）。
 *
 * 本 demo 用纯 JDK 零依赖方式复现：
 *   用 JavaCompiler(JDK 编译期 API) 或手写字节码。这里采用【最稳的手写 class 字节码】方式，
 *   每个循环 define 一个新类，撑爆 MaxMetaspaceSize。不依赖任何第三方库。
 *
 * 运行参数：-XX:MaxMetaspaceSize=32m  (如果不给，JDK 默认几乎无上限 -XX:MaxMetaspaceSize≈unlimited，
 *          就难以在测试里快速复现，所以必须显式给小值)
 * IDEA 设置：VM options 填 -XX:MaxMetaspaceSize=32m
 */
public class Demo2_Metaspace {

    // 构造一个最小的合法 class 字节码：public class GenN { }
    // 结构：magic(4) minor(2) major(2) cp_count(2) 常量池[Class->Utf8(name)] access(2) this(2) super(2) if(2) field(2) method(2) attr(2)
    private static byte[] buildClassBytes(int classIndex) {
        String internalName = "Gen" + classIndex;
        String classFileName = internalName + ".class";

        // 简化：用一个稳妥的通用字节码生成 —— 用 JavaCompiler 动态编译更直观但慢；
        // 这里改用手写字节码，纯内存、可控、零依赖。
        return MinimalClassBuilder.build(classFileName);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Metaspace 上限请通过 -XX:MaxMetaspaceSize 设置，本 demo 建议 32m\n");
        reportMetaspace("初始", 0);

        // 关键：必须【强引用持有】加载器与类，否则它们变成垃圾会被卸载、Metaspace 被回收复用，
        // 结果是永远撑不爆。这正是生产上 ClassLoader 泄漏的等价模型：
        // 老加载器被别处（线程/静态变量/连接池）引用住 -> 它加载的类元信息永久无法回收。
        List<Object> keepAlive = new ArrayList<>();

        int i = 0;
        try {
            while (true) {
                Object ref = defineOneClass(i);
                keepAlive.add(ref);   // 持有强引用，模拟“老加载器/类被外部引用住”
                i++;
                if (i % 2000 == 0) {
                    reportMetaspace("已加载", i);
                }
            }
        } catch (OutOfMemoryError e) {
            // 注意：元空间已满时，连字符串拼接(StringConcatFactory 需生成隐藏类)都会失败。
            // 所以这里全部用固定字符串输出，避免二次 OOM 掩盖真正的错误信息。
            System.out.println();
            for (String line : DIAGNOSIS) {
                System.out.println(line);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("异常类型: ").append(e.getClass().getName())
              .append(" | 异常信息: ").append(e.getMessage());
            System.out.println(sb);
            reportMetaspace("OOM时", i);
        }
    }

    /** 编译期常量诊断信息：元空间耗尽时直接输出，不会触发 invokedynamic 生成隐藏类 */
    private static final String[] DIAGNOSIS = {
        "================ 触发 OOM ================",
        "--- 排查思路 ---",
        "1. jcmd <pid> GC.class_stats 或 jstat -gcmetacapacity <pid> 实时看 Metaspace 使用量",
        "2. jcmd <pid> GC.class_histogram  |  jcmd <pid> VM.classloader_stats  看加载器与类数",
        "3. 看 Unloaded 数量是否【不增长】：不增长说明 ClassLoader 泄漏（老加载器没被 GC）",
        "",
        "--- 面试标准答案要点 ---",
        "· Metaspace 在本地内存，用 -XX:MaxMetaspaceSize 限制（不是 -Xmx）；",
        "· 泄漏典型：每次 new URLClassLoader(热部署) 加载新版本类，",
        "  老 ClassLoader 被 Thread/静态变量/连接池引用而无法回收，",
        "  它加载的所有类元信息也永久泄漏，重建加载器无法真正卸载。",
        "· 排查抓手：jcmd GC.class_stats 看 Loaded/Unloaded；Unloaded 不涨 = 泄漏。",
        "· 缓解：设上限让问题早暴露；代理类要复用而非每次 new。",
        "· 顺带现象：Metaspace 满时字符串拼接也会失败，因为 invokedynamic 需要生成隐藏类。",
    };

    private static Object defineOneClass(int idx) {
        byte[] bytes = buildClassBytes(idx);
        // 每次新建一个加载器 —— 模拟“频繁 new URLClassLoader”，同时把 loader 也返回出去持有
        MetaspaceLoader loader = new MetaspaceLoader();
        Class<?> c = loader.defineFrom(bytes);
        // 返回一个把 loader 和 class 都串起来的小对象，确保两者都有强引用
        return new Object[] { loader, c };
    }

    private static void reportMetaspace(String stage, int count) {
        long loaded = ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount();
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(stage).append("] 本demo已定义=").append(count)
          .append(" 个类, JVM累计已加载类数=").append(loaded);
        System.out.println(sb);
    }

    /** 独立 ClassLoader：每次新建，模拟“频繁 new 加载器导致 Metaspace 泄漏” */
    static class MetaspaceLoader extends ClassLoader {
        Class<?> defineFrom(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}