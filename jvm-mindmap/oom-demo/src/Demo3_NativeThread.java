/**
 * 场景 3：java.lang.OutOfMemoryError: unable to create new native thread
 *
 * 成因：操作系统层面无法再创建【原生线程】了。这不是堆不够，也不是 Metaspace 不够，
 *       而是受以下三者限制：
 *         1. ulimit -u / threads-max：进程可创建的最大线程数；
 *         2. 进程虚拟内存 / 线程栈：每个线程默认预留栈空间，-Xss 控制（默认 1M/线程，几千线程就吃掉几个G 虚拟内存）；
 *         3. 系统的 PID/句柄上限。
 * 典型业务触发：
 *   1. 大量连接【未关闭】(Socket/DB 连接泄漏)，每次请求创建线程处理，线程只增不减；
 *   2. 死锁 / 阻塞导致线程堆积（线程池打满且拒绝策略失效，疯狂 new Thread）；
 *   3. 大量第三方 SDK / 客户端每调用一次就 new 一个线程（如某些老的 HTTP 客户端）。
 *
 * 运行参数：-Xss256k（把线程栈调小，这样在普通机器上也能较快复现，不用真的开到几千线程）
 *           -XX:+PrintConcurrentLocks(可选)
 * IDEA 设置：VM options 填 -Xss256k
 *
 * 说明：这是 5 个 demo 里最“吃资源”的一个。它会真的创建成百上千线程，
 *      但因为 -Xss 调小 + 及时退出的线程，占用可控，几秒内就会 OOM。放心运行。
 */
public class Demo3_NativeThread {

    // 模拟“一个连接开一个线程处理，处理完不关闭/关闭太慢”的泄漏模型
    static int alive = 0;
    static volatile Object sink; // 防止 JIT 优化掉分配

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== unable to create new native thread 复现 ===\n");
        System.out.println("最大堆: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        System.out.println("可用处理器: " + Runtime.getRuntime().availableProcessors());
        System.out.println();

        // 【复现原理】JDK 的 Thread.start0() 会把创建线程过程中的【任何失败】
        //统一包装成 OutOfMemoryError("unable to create new native thread")：
        //
        //   private void start0() {
        //       try { startThread(); }
        //       catch (Throwable e) { throw new OutOfMemoryError("unable to create new native thread"); }
        //   }
        //
        // 所以只要线程数量【无界增长】，把系统能给的资源（线程栈/进程虚拟内存/句柄）耗尽，
        // 新建线程就会失败并抛出这个错误。
        //
        // 【跨平台说明】
        //  - Linux:还受 ulimit -u(最大线程数) / ulimit -v(地址空间) 限制，容易复现；
        //  - Windows: 没有 ulimit，主要受【进程提交内存(页面文件)】限制。
        //    所以本 demo 用较小的 -Xmx + 较小 -Xss，让线程更快撞到上限。
        System.out.println("提示：Windows 无 ulimit -u，靠进程提交内存上限限制线程数；");
        System.out.println("     想更快复现可把 -Xss 再调小(如 256k)或 -Xmx 调小。\n");

        int count = 0;
        try {
            while (true) {
                // 每次“处理一个连接”起一个线程 —— 无池化、无上限，是泄漏写法
                Thread t = new Thread(() -> {
                    try {
                        synchronized (Demo3_NativeThread.class) {
                            alive++;
                        }
                        // 关键：线程必须【一直活着】，否则线程退出后资源立即释放，永远撞不到上限
                        Thread.sleep(300_000);
                    } catch (InterruptedException ignored) {
                    }
                }, "biz-thread-" + count);
                t.setDaemon(true);
                t.start();   // <-- 资源不足时，失败点在这里，抛出 unable to create new native thread
                count++;

                if (count % 2000 == 0) {
                    long heapUsed = (Runtime.getRuntime().totalMemory()
                            - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
                    System.out.println("已创建线程数 = " + count + ", 仍存活 = " + alive
                            + ", 堆已用 " + heapUsed + " MB");
                }
            }
        } catch (OutOfMemoryError e) {
            System.out.println("\n================ 触发 OOM ================");
            System.out.println("异常类型: " + e.getClass().getName());
            System.out.println("异常信息: " + e.getMessage());
            System.out.println("累计尝试创建线程数 = " + count);
            System.out.println("仍存活线程数 = " + alive);

            System.out.println("\n--- 排查思路 ---");
            System.out.println("1. Linux:   ulimit -u 查最大线程数；ulimit -a 看栈大小限制");
            System.out.println("2. 实时看:  top -H -p <pid>   或   jstack <pid> | grep '^\"' | wc -l  统计线程数");
            System.out.println("3. 看线程状态分布: jstack <pid> > t.txt，然后 grep java.lang.Thread.State 统计各状态个数");
            System.out.println("   - 大量 RUNNABLE → CPU/业务阻塞；大量 WAITING → 线程池打满或等锁；");
            System.out.println("   - 大量 BLOCKED → synchronized 竞争。");
            System.out.println("4. Windows: 任务管理器→服务→线程数；或 jstack 直接数线程。");
            System.out.println("5. 定位泄漏连接：jstack 里搜连接创建点，看哪个业务反复 new Thread 而不 close。");
            System.out.println("6. 注意错误措辞会因 JDK 版本/平台不同而变化：");
            System.out.println("   - unable to create native thread: possibly out of memory or process/resource limits reached (JDK17/HotSpot)");
            System.out.println("   - unable to create new native thread (JDK8 常见措辞)");
            System.out.println("   两者含义相同，不要死记具体字符串。");

            System.out.println("\n--- 面试标准答案要点 ---");
            System.out.println("· unable to create new native thread ≠ 堆不足。它是【OS 层面】拒绝再给线程了；");
            System.out.println("· 三大限制：进程线程数上限(ulimit -u)、线程栈虚拟内存(-Xss,默认1M,乘以线程数很可观)、");
            System.out.println("  系统句柄/PID 上限。调小 -Xss 或增线程上限只是缓解；");
            System.out.println("· 根治：连接/线程必须【有界】—— 用线程池而非 new Thread，用完 close/释放；");
            System.out.println("  加连接池与超时，避免无限堆积。");
        }
    }
}