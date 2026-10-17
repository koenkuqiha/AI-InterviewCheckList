import java.util.ArrayList;
import java.util.List;

/**
 * 场景 1：java.lang.OutOfMemoryError: Java heap space
 *
 * 成因：堆内存不足。两种典型情况——
 *   (a) 对象堆积：内存泄漏 / 缓存无上限 / 一次加载百万级数据到 List
 *   (b) 分配过大：单个数组或字符串申请超过堆剩余
 *
 * 运行参数：-Xmx64m
 * IDEA 设置：Run -> Edit Configurations -> VM options 填 -Xmx64m
 */
public class HeapOOMDemo {

    // 模拟一个没有设置容量上限的缓存 —— 典型的内存泄漏写法
    static class Cache {
        private final List<byte[]> store = new ArrayList<>();

        void put(byte[] payload) {
            // 真实业务里这里放的是从 DB/接口加载的对象，每次都【持有强引用】进 List
            store.add(payload);
        }

        int size() {
            return store.size();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("最大堆: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        System.out.println("初始堆: " + (Runtime.getRuntime().totalMemory() / 1024 / 1024) + " MB\n");

        Cache cache = new Cache();
        long start = System.currentTimeMillis();

        // 【应急缓冲】堆彻底耗尽时，连 catch 块里的字符串拼接都会失败，导致看不到任何诊断信息。
        // 生产上的标准做法：预留一块缓冲，OOM 时先丢弃它，保证诊断代码能跑完。
        byte[] emergencyBuffer = new byte[4 * 1024 * 1024];

        try {
            while (true) {
                // 每个 payload 16KB，且被 List 强引用住 -> GC 无法回收 -> 必然堆溢出
                cache.put(new byte[16 * 1024]);
                if (cache.size() % 2000 == 0) {
                    long used = (Runtime.getRuntime().totalMemory()
                            - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
                    System.out.println("已放入 " + cache.size() + " 个对象("
                            + (cache.size() * 16 / 1024) + " MB 原始数据)，堆已用 " + used + " MB");
                }
            }
        } catch (OutOfMemoryError e) {
            emergencyBuffer = null; // 先释放缓冲，让下面的诊断代码有内存可用
            System.out.println("\n================ 触发 OOM ================");
            System.out.println("异常类型: " + e.getClass().getName());
            System.out.println("异常信息: " + e.getMessage());
            System.out.println("堆中残留对象数: " + cache.size());
            System.out.println("耗时: " + (System.currentTimeMillis() - start) + " ms");

            System.out.println("\n--- 排查思路（本 demo 模拟生产上真实该做的事）---");
            System.out.println("1. jcmd <pid> GC.heap_info / jmap -histo:live <pid>  → 找哪个类实例数最多");
            System.out.println("2. jcmd <pid> JCM.gc  触发一次 Full GC 后再看 histo，差值即「GC 后仍存活」= 泄漏嫌疑");
            System.out.println("3. jcmd <pid> GC.heap_dump / jmap -dump:live,format=b,file=heap.hprof <pid>");
            System.out.println("4. 用 MAT( Eclipse MAT ) 打开 hprof → Leak Suspects → Dominator Tree");
            System.out.println("\n注意区分：OOM 有可能是「泄漏」(对象本该回收却没回收)，");
            System.out.println("也可能是「设计问题」(一次性加载百万级流水)。后者加内存只是推迟崩溃，");
            System.out.println("正确做法是改成分页/流式处理 (Stream + 批量提交)。");
        }
    }
}