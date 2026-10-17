import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景 4：java.lang.OutOfMemoryError: Direct buffer memory
 *
 * 成因：NIO/Netty 的【直接内存 (Direct Memory / Off-Heap)】耗尽。
 * 关键特性（面试高频）：
 *   1. 直接内存【不走堆】，所以【不计入 -Xmx】，也不受 GC 常规管理；
 *   2. 它的释放依赖【引用被GC 后 Cleaner 清理】，所以 GC 感知滞后 —— 堆很空但直接内存满了；
 *   3. 必须显式设置 -XX:MaxDirectMemorySize，否则默认约等于 -Xmx；
 *   4. System.gc() 不会主动回收直接内存（除非开启 DisableExplicitGC 配合，否则显式 gc 对它无效）。
 *      —— 这里是最容易被答错的点：直接内存回收靠【 Cleaner + 一次真正的 GC 】，
 *         显式 System.gc() 若被禁用则回收不了，所以生产上要么别 disable GC，要么配 -XX:SoftRefLRUPolicyMSPerMB。
 *
 * 运行参数：-XX:MaxDirectMemorySize=32m -XX:+PrintNMTStatistics(可选)
 * IDEA 设置：VM options 填 -XX:MaxDirectMemorySize=32m
 */
public class Demo4_DirectBufferMemory {

    private static final int CHUNK = 1024 * 1024; // 每块 1MB

    public static void main(String[] args) throws InterruptedException {
        long maxDirect = maxDirectMemory();
        System.out.println("最大直接内存 MaxDirectMemorySize: "
                + (maxDirect == Long.MAX_VALUE ? "未显式设置(默认约等于 -Xmx)"
                                             : (maxDirect / 1024 / 1024) + " MB"));
        System.out.println("最大堆 -Xmx: "
                + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB\n");

        // 模拟 Netty 分配器/缓冲区池：不断申请直接内存且【持有引用不释放】
        List<ByteBuffer> retained = new ArrayList<>();
        int count = 0;

        try {
            while (true) {
                ByteBuffer buf = ByteBuffer.allocateDirect(CHUNK);
                // 真实场景写入数据才会【提交物理内存】(zero-fill)，这里写一个字节触发提交
                buf.put(0, (byte) 1);
                retained.add(buf); // 关键：持有引用 → Cleaner 无法回收 → 泄漏式堆积
                count++;

                if (count % 8 == 0) {
                    long used = usedDirectMemory();
                    System.out.println("已分配 " + count + " 块，本进程已提交直接内存 "
                            + used + " MB");
                }
            }
        } catch (OutOfMemoryError e) {
            System.out.println("\n================ 触发 OOM ================");
            System.out.println("异常类型: " + e.getClass().getName());
            System.out.println("异常信息: " + e.getMessage());
            System.out.println("已泄漏直接内存约: " + usedDirectMemory() + " MB");

            System.out.println("\n--- 排查思路 ---");
            System.out.println("1. NMT (Native Memory Tracking)：-XX:NativeMemoryTracking=summary");
            System.out.println("   jcmd <pid> VM.native_memory summary scale=MB  → 看 Other 一栏");
            System.out.println("2. jcmd <pid> VM.native_memory base.txt 做 diff，对比两次快照找出泄漏点");
            System.out.println("3. Netty 场景：开启 -Dio.netty.leakDetection.level=paranoid，用 ResourceLeakDetector");
            System.out.println("4. JFR：-XX:StartFlightRecording=duration=60s,filename=oome.jfr");
            System.out.println("   用 JMC 打开 → Memory/File IO/Socket 一栏能看到 DirectBuffer 分配热点");

            System.out.println("\n--- 面试标准答案要点 ---");
            System.out.println("· 直接内存由 Bits/ReserveMemory 管理，超 MaxDirectMemorySize 抛 OOM(SunMisc 或 Direct buffer memory)");
            System.out.println("· 为什么 System.gc() 不一定能救？因为 DisableExplicitGC 禁掉了显式 GC，");
            System.out.println("  而直接内存的 Cleaner 只在【一次真实的 GC】后随弱引用回收才执行。");
            System.out.println("  所以关键不是【反复调 System.gc()】，而是【修引用泄漏】让 Cleaner 有机会跑。");
            System.out.println("· 调优手段：设 MaxDirectMemorySize(别裸奔)、给 Netty 设 PooledByteBufAllocator、");
            System.out.println("  复用 buffer、用完 release()；避免 DisableExplicitGC 与 DirectMemory 冲突。");
        }
    }

    private static long usedDirectMemory() {
        for (java.lang.management.BufferPoolMXBean b :
                java.lang.management.ManagementFactory.getPlatformMXBeans(
                        java.lang.management.BufferPoolMXBean.class)) {
            if ("direct".equals(b.getName())) {
                return b.getMemoryUsed() / 1024 / 1024;
            }
        }
        return CHUNK * (long) countForPrint / 1024 / 1024;
    }

    private static int countForPrint = 0;

    private static long maxDirectMemory() {
        // JDK9+ 模块系统封装了 jdk.internal.misc.VM，反射不可靠。
        // 改用标准 API：BufferPoolMXBean 的 "direct" 池容量就是 MaxDirectMemorySize 的体现。
        try {
            for (java.lang.management.BufferPoolMXBean b :
                    java.lang.management.ManagementFactory.getPlatformMXBeans(
                            java.lang.management.BufferPoolMXBean.class)) {
                if ("direct".equals(b.getName())) {
                    long cap = b.getTotalCapacity();
                    return cap > 0 ? cap : Long.MAX_VALUE;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}