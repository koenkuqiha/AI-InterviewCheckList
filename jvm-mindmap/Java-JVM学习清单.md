# Java JVM 学习清单（9阶段路线）

> **说明**：共 9 个阶段，总时长约 13-15 天（每天 3-4 小时有效投入）。每个阶段包含「学习内容 + 动手任务 + 验收标准」。
>
> **权重提示**：阶段 3（GC）与阶段 4（并发）是面试权重最高的两块；阶段 5（排查实战）与阶段 7（项目包装）是真正拉开差距的部分。
---

## 阶段 0 · 环境与工具（0.5 天） ★★

【目标】有可直接跑实验的环境，后续所有知识点都能动手验证，而不是死记。
【环境准备】
1) JDK 8 + JDK 17 都装上（JDK8 验证 CMS/Parallel，JDK17 验证 G1/ZGC），统一用 -XX:+PrintFlagsFinal 确认默认值。
2) IDEA 配置：-ea 开启断言、-Xmx 调小（故意设成 64m 便于触发 OOM）。
3) 工具清单（重点）：
   - jps / jstat / jmap / jstack / jcmd（内置，必会）
   - MAT（内存分析，Eclipse MAT，导入 hprof）
   - Arthas（线上诊断，dashboard/thread/trace/memory）
   - async-profiler（火焰图，low 抖动）
   - GCEasy（GC 日志在线解析）
   - JOL（Java 对象布局查看器，看对象头/字段对齐）
4) 调试远程 JVM：-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005（只开内网端口，勿暴露公网）。
【验收标准】能一条命令拿到进程列表、GC 统计、线程栈、堆快照四类数据。

#### 推荐动手小实验 ★

写一个 30 行的 DeadLockDemo（两个线程相反顺序加 A、B 两把锁），故意卡死，然后用 jstack 抓出来看到 "Found one Java-level deadlock"。
这个实验能一次打通「线程状态 + 死锁四条件 + jstack 用法」三个知识点，性价比最高。

## 阶段 1 · 内存区域与对象模型（2 天） ★★★

【学习顺序】这是最底层也最死板的一块，必须先啃。
1) 读《深入理解 Java 虚拟机》第 2、3 章（周志明）。
2) 每个区域做一件事：找代码验证。
3) 用 JOL 看对象布局，用 -XX:+PrintFlagsFinal 看参数默认值。
【时间分配建议】Day1 上午：运行时区域 + 堆栈；Day1 下午：对象创建 + 对象布局；Day2：引用类型 + 异常 + 参数。

#### 1.1 运行时数据区（必背） ★★★

学习内容：
- 程序计数器、虚拟机栈、本地方法栈、堆、方法区各自的职责与线程隔离属性；
- 栈帧的四个组成部分（局部变量表、操作数栈、动态链接、返回地址）；
- 为什么 PC 是线程私有的（线程切换后能恢复执行位置）；
- 方法区在 JDK7/JDK8 的演变（永久代 → 元空间，为什么放本地内存）。
动手任务：
- 写 3 个方法，一个用 int、long、double、float 局部变量，观察字节码（javap -c）看 slot 占用；
- 对比 static 变量、普通变量、局部变量的存放位置。
验收标准：能在白板上默画出五个区域，说清每个区域的线程属性和典型异常。

#### 1.2 对象创建与内存布局（必背） ★★★

学习内容：
- 对象创建五步：检查 → 实例化 → 清零 → 设置对象头 → 执行构造方法；
- 指针碰撞 vs 空闲列表，什么条件下用哪种；
- TLAB 的作用与原理（为什么多线程分配要 TLAB）；
- 对象头 Mark Word 复用的字段（哈希/年龄/锁标志位/偏向线程 ID）；
- 类型指针、压缩指针（CompressedOops）、对齐填充；
- 用 JOL 验证 Object 占 16 字节的原因。
动手任务：
- JOL 实测 Object、Integer(带缓存)、Long 对象的大小；
- 加 -XX:+PrintCompressedOops 观察开关；
- 计算「一个有 10 个 int 字段的对象占多少字节」。
验收标准：能算任意普通对象的内存占用（含对齐）。

#### 1.3 引用类型与内存异常（重点） ★★★

学习内容：
- 强/软/弱/虚引用的语义、适用场景、与 ReferenceQueue 的配合；
- SoftReference 与 LRU 缓存淘汰的关联（面试延伸点）；
- 五种 OOM + StackOverflowError 的异常信息识别；
- -Xms/-Xmx/-Xss/-XX:MetaspaceSize/-XX:MaxMetaspaceSize 的作用与容器环境的 MaxRAMPercentage。
动手任务：
- 故意写一个 -Xmx32m 的程序并不断塞 List 触发 OOM，观察堆快照；
- 递归到爆栈，观察 StackOverflowError。
验收标准：看到 OOM 异常信息能立刻说出「大概率原因 + 排查方向」。

#### 1.4 逃逸分析（了解） ★★

学习内容：栈上分配、标量替换、锁消除的原理；为什么只在 C2 编译后生效。
动手任务：加 -XX:+PrintEscapeAnalysis 观察哪些方法发生了逃逸分析。
验收标准：能解释「为什么 synchronized 有时会被优化掉」。

## 阶段 2 · 类加载机制（1.5 天） ★★★

【为什么重要】类加载是 Java 唯一跨 JVM 厂商的规范部分，也是「SPI 为什么会用上下文类加载器」这类经典题的根源。
【学习顺序】生命周期 → 加载器体系 → 双亲委派 → 破坏场景 → 初始化时机 → 卸载条件。
【源码建议】直接看 OpenJDK 的 ClassLoader.loadClass 源码（很短，核心 20 行），比看任何博客都清楚。

#### 2.1 生命周期与类初始化时机（必背） ★★★

学习内容：
- 加载→验证→准备→解析→初始化→使用→卸载七阶段；
- 准备阶段赋「类型零值」而非代码初始值（static int i=1 准备阶段为 0）；
- final 常量在准备阶段就是真值；
- <clinit>() 的执行顺序（先父后子，只执行一次）；
- 主动引用 6 种 vs 被动引用 4 种，逐一背例子。
动手任务：写一个类，里面放 static int、static final int、static 块、构造块，按顺序打日志，验证执行顺序。
验收标准：给你 10 个代码片段，能判断是否触发类初始化。

#### 2.2 类加载器与双亲委派（必背） ★★★

学习内容：
- Bootstrap（rt.jar，C++ 实现）、Platform（JDK9 替换 Ext）、AppClassLoader、自定义加载器；
- loadClass 的两段核心逻辑：先 parent 委派，再 findClass；
- 委派模型的两个优势（安全性 + 类唯一性）；
- 为什么自定义加载器只重写 findClass 不重写 loadClass；
- 类身份 = 全限定名 + 类加载器，交叉加载导致 ClassCastException 的原理。
动手任务：写一个自定义 ClassLoader，从指定目录加载 class 文件，打印「哪个加载器加载的」。
验收标准：能默写 loadClass 的伪代码，并能解释「为什么 java.lang.String 不会被自己替换」。

#### 2.3 破坏场景与类卸载（重点） ★★★

学习内容：
- SPI 场景：DriverManager 用 Thread.currentThread().getContextClassLoader() 加载驱动；
- Tomcat/Spring Boot 的「子优先」类加载实现 Web 应用隔离；
- OSGi 网状依赖；动态代理/热部署；
- 类卸载的三个必要条件，ClassLoader 泄漏的典型来源（JDBC Driver、动态代理、ThreadLocal）。
动手任务：读一遍 DriverManager 的源码，看静态初始化块里的 loadInitialDrivers。
验收标准：能完整解释「为什么用了 SPI 却不违反双亲委派原则」。

## 阶段 3 · 垃圾回收（3 天 · 最高权重） ★★★

【为什么是最高权重】GC 是 JVM 面试占比最大的一块（约 30%-40%），也是最能区分「背过」和「用过」的地方。
【学习顺序】
Day1：存活判定 + 四种算法 + 分代与晋升（理论地基）
Day2：七大收集器 + G1 深挖（面试主战场）
Day3：CMS/ZGC + 参数 + 日志分析 + 内存泄漏排查（实战能力）
【学习原则】收集器部分不要死记参数，要理解每个设计是为了解决什么问题 —— 面试官问「G1 的记忆集解决什么问题」，答不上来就是没理解。

#### 3.1 存活判定与回收算法（必背） ★★★

学习内容：
- 引用计数法的循环引用缺陷；
- 可达性分析 + GC Roots 完整清单（5 类，容易漏说 JNI）；
- 四种算法：标记清除、复制、标记整理、分代；每种的优缺点和适用场景；
- 分代假设：为什么新生代用复制、老年代用标记整理/清除；
- 对象晋升的四种路径 + 动态年龄判断。
动手任务：
- 复制算法手算：给一个 Eden+S0+S1 的对象分布图，说明一次 Minor GC 后各区怎么变；
- 想清楚「为什么 Survivor 需要两个」。
验收标准：给一个具体堆状态图，能推演 GC 后的变化。

#### 3.2 GC 类型与七大收集器（必背） ★★★

学习内容：
- Minor GC / Full GC / Mixed GC 的触发条件与回收范围；
- 七个收集器（Serial、Serial Old、Parallel、CMS、G1、ZGC、Shenandoah、Epsilon）的定位与适用场景；
- JDK 版本演进：JDK8 默认 Parallel → JDK9 默认 G1 → CMS 在 JDK14 废弃、15 移除；
- 「吞吐量优先」vs「延迟优先」两种设计哲学。
动手任务：
- 分别用 -XX:+UseParallelGC / -XX:+UseG1GC / -XX:+UseZGC 跑同一个压测程序，对比 GC 次数和总耗时；
- 记录数据，形成自己的对比表（这一步比看十篇文章有用）。
验收标准：能画出一张收集器对比表（内存占用/复杂度/吞吐量/延迟/适用 JDK 版本）。

#### 3.3 CMS 深入（重点） ★★★

学习内容：
- CMS 的三色标记算法、初始标记/并发标记/重新标记/并发清除四个阶段；
- 浮动垃圾：为什么产生，如何缓解；
- 内存碎片：为什么产生，Compact 补救方案；
- Concurrent Mode Failure：什么场景触发，代价是什么（降级 Serial Old 全 STW）；
- 参数：-XX:+UseConcMarkSweepGC、-XX:InitiatingOccupancyFraction、-XX:ParallelGCThreads。
动手任务：用 JDK8 跑一个 CMS 场景，人为制造大对象，观察碎片化和 Full GC。
验收标准：能说出 CMS 三个致命缺陷及各自的成因（这是高频追问）。

#### 3.4 G1 深入（最高频） ★★★

学习内容（这是 GC 部分的题王）：
- Region 化：2048 个 Region、Humongous 对象、逻辑分区概念；
- 停顿时间模型：MaxGCPauseMillis 与「回收收益」选择算法（G1 的核心思想）；
- 记忆集 Remembered Set：写屏障 + Card Table 解决跨 Region 引用；
- SATB 原始快照：写前屏障的工作方式、与增量更新的取舍；
- 四阶段并发标记（初始标记 STW → 并发标记 → 重新标记 STW → 清理 STW）；
- Mixed GC 与 G1HumongousObjectAllocationRetries；
- 关键参数：InitiatingHeapOccupancyPercent、RegionSize、ConcGCThreads、G1ReservePercent。
动手任务：
- 用 G1 跑压测，通过 GC 日志找到 Mixed GC、并发标记的触发时机；
- 对比 CMS 与 G1 在同一程序上的日志差异；
- 看一遍《G1 论文/官方白皮书》的图。
验收标准：「G1 的记忆集解决什么问题」「SATB 和增量更新各有什么代价」这两个问题能各答 90 秒。

#### 3.5 ZGC / Shenandoah（了解） ★★

学习内容：着色指针 + 读屏障的实现思路（并发转移而非并发标记）；Brooks 指针；为什么 ZGC 停顿能到亚毫秒级。
动手任务：JDK17 加 -XX:+UseZGC 跑一遍，看日志格式与其他收集器的差异。
验收标准：知道 ZGC 的核心取舍（读屏障换停顿），能一句话说清。

#### 3.6 参数与日志分析（必背） ★★★

学习内容：
- 堆参数：-Xms/-Xmx/-Xmn/-XX:MaxTenuringThreshold/-XX:SurvivorRatio/-XX:PretenureSizeThreshold；
- 收集器开关：-XX:+UseG1GC 等；
- 日志：JDK8 的 -XX:+PrintGCDetails + -Xloggc；JDK9+ 的 -Xlog:gc* 统一语法（含文件轮转配置）；
- GC 日志的关键字段读法：GC 前后各区变化、暂停时间、Full GC 触发原因；
- 分析指标：GC 频率、单次 STW 时长、GC 后老年代基线是否回升。
动手任务：
- 收集至少 10 份不同场景的 gc.log，用 GCEasy 解析并截出「晋升速率图」「老年代基线图」；
- 刻意制造一次 Full GC，看日志里触发行怎么写的。
验收标准：给你一份 gc.log，能在 3 分钟内说出「有没有泄漏、频率是否异常、优化方向是什么」。

#### 3.7 内存泄漏排查方法论（必背） ★★★

学习内容：
- 四步法：定类型 → jstat -gcutil 观察趋势 → jmap dump + MAT 分析 → 定位代码；
- MAT 核心操作：Leak Suspects、Dominator Tree、Path to GC Roots、Histogram 对比；
- 六大常见泄漏源：无界集合、ThreadLocal、监听器、未关闭资源、静态缓存、线程池堆积；
- 多次采样思路（Arthas memory）vs 单次快照的局限。
动手任务：
- 造一个 ThreadLocal 泄漏（线程池场景），用 MAT 找到泄漏路径；
- 再造一个无界 Map 泄漏，对比两者在 Dominator Tree 中的区别。
验收标准：完整走一遍四步法并写出排查报告。

## 阶段 4 · 并发与内存模型（3 天 · 高权重） ★★★

【为什么重要】面试中并发模块几乎必问，且最能体现「有没有真正理解底层」——很多 10 年经验的候选人在这块只停留在 API 层。
【学习顺序】
Day1：JMM 三特性 + happens-before + volatile + 指令重排（理论基础）
Day2：synchronized 锁升级 + AQS + CAS（实现原理）
Day3：线程池 + ThreadLocal + ConcurrentHashMap + 死锁（实战组件）
【学习原则】每一个并发工具都要问三个问题：它解决了什么问题、它的底层原理是什么、它的代价是什么。

#### 4.1 JMM 三大特性（必背） ★★★

学习内容：
- 原子性/可见性/有序性的定义与各自的典型问题（i++ 不原子、多线程改标志位不可见、指令重排破坏单线程语义）；
- CPU 多级缓存（MESI 协议）与 Java 内存模型的对应关系；
- 三类重排：编译器重排、CPU 指令重排、JIT 重排；
- as-if-serial 语义。
动手任务：写一个「两个线程共享 boolean 标志位」的例子，加上/不加 volatile 各跑 1 万次，观察结果差异。
验收标准：能画出「主内存 + 工作内存」的交互图并标注每一步的同步动作。

#### 4.2 happens-before 八条规则（必背） ★★★

学习内容：8 条规则逐条背，重点是传递性；理解「A happens-before B ⇒ A 的写对 B 可见且 A 在 B 之前」。
动手任务：用 happens-before 分析一段代码，说明为什么 ConcurrentHashMap 的 size() 能读到最新值。
验收标准：给任意两行代码，能判断是否满足 happens-before 关系并说明理由。

#### 4.3 volatile 原理（必背） ★★★

学习内容：
- 两个作用（可见性 + 有序性）及其做不到的事（原子性）；
- 四种内存屏障（StoreStore / StoreLoad / LoadLoad / LoadStore）及其插入位置；
- volatile 与 synchronized 的选型对比；
- volatile 修饰引用类型的陷阱；
- 典型用途：DCL 单例、状态标记、JUC 容器内部。
动手任务：
- 用 -XX:+PrintAssembly 或 -XX:+PrintCompilation 看 volatile 读写生成的屏障指令（进阶）；
- 手写 DCL 单例，解释不加 volatile 有什么问题（半个对象被其他线程看到）。
验收标准：能说出「写 volatile 后插 StoreStore+StoreLoad，读 volatile 后插 LoadLoad+LoadStore」。

#### 4.4 synchronized 与锁升级（必背） ★★★

学习内容：
- monitorenter/monitorexit 字节码 + ACC_SYNCHRONIZED 标志；
- ObjectMonitor 结构（_owner / _entryList / _waitSet）；
- 四级升级：无锁 → 偏向 → 轻量级 → 重量级，各自的 Mark Word 锁标志位（01/00/10）；
- 偏向锁原理（线程 ID 写进 Mark Word）及 JDK15 废弃的原因（JEP 374）；
- 锁消除（逃逸分析）与锁粗化；
- 与 ReentrantLock 的对比（可重入性、可中断、超时、公平性、多条件队列）；
- 不可重入导致的自己锁死自己。
动手任务：
- javap -c 看同步代码块与同步方法的字节码差异；
- 用 JOL 观察不同锁状态下对象头的变化（这是最直观的验证方式）。
验收标准：「一个线程从进入 synchronized 到发生重量级锁，Mark Word 经历了哪几次变化」能完整说清。

#### 4.5 AQS 原理（必背） ★★★

学习内容：
- 三个核心：volatile state、CLH 双向队列、独占/共享两种模式；
- acquire/release 的完整流程（tryAcquire → addWaiter → acquireQueued → selfPark）；
- 模板方法模式：父类定流程，子类定策略；
- 公平锁 hasQueuedPredecessors() 的作用；
- Condition 实现（条件队列 + await/signal）。
动手任务：
- 读一遍 AbstractQueuedSynchronizer 源码（重点看 acquire、release、ConditionObject）；
- 读 ReentrantLock 的 NonfairSync/FairSync；
- 推导 CountDownLatch 和 Semaphore 如何复用 AQS（state 的不同语义）。
验收标准：能口述一个线程从「调 acquire」到「被唤醒后拿到锁」的完整过程。

#### 4.6 CAS 与 ABA（必背） ★★★

学习内容：
- Unsafe.compareAndSwapInt 的 CPU 指令层实现；
- 三大问题：ABA、自旋开销、单变量原子性；
- 解法：AtomicStampedReference、LongAdder（Cell 分段 + base + Striped64）；
- LongAdder 的热点探测机制。
动手任务：写一个 ABA 复现案例；用 JMH 或简单压测对比 AtomicLong 与 LongAdder 在高并发下的吞吐。
验收标准：能解释 LongAdder 为什么比 AtomicInteger 快（分段 + 热点 Cell 分散竞争）。

#### 4.7 线程池（必背） ★★★

学习内容：
- 7 大参数 + 6 种 BlockingQueue + 4 种拒绝策略；
- 精确的执行流程（核心线程 → 队列 → 非核心 → 拒绝），每一步的判断条件；
- 为什么阿里规约禁用 Executors 创建线程池（两个 OOM 场景）；
- execute vs submit 的异常传播差异；
- 线程池大小计算公式及其物理含义（CPU 密集 vs IO 密集）；
- 优雅关闭：shutdown / shutdownNow / awaitTermination；
- 任务堆积的排查与监控埋点。
动手任务：
- 手写一个完整的 ThreadPoolExecutor 工厂（含命名 ThreadFactory）；
- 写一个把任务抛异常的场景，观察 execute 和 submit 的差异；
- 复现「Executors.newFixedThreadPool 导致 OOM」，用 MAT 看堆积的任务。
验收标准：能不看资料画出线程池执行流程图。

#### 4.8 ThreadLocal（必背） ★★★

学习内容：
- Thread → ThreadLocalMap → Entry 的三层结构；
- key 弱引用 + value 强引用导致的泄漏原理；
- 为什么线程池场景泄漏更严重（线程被复用，永不释放）；
- 阿里规约：必须 static final + 用完 remove；
- Spring InheritableThreadLocal 与 TTL（TransmittableThreadLocal）方案；
- 典型用途：MDC 日志 traceId、用户上下文、数据库连接上下文。
动手任务：
- 用线程池 + ThreadLocal 不 remove 复现泄漏，MAT 抓证据；
- 集成 TransmittableThreadLocal 解决子线程传递问题。
验收标准：能画出 ThreadLocalMap 的结构图并说明 key 变 null 后 value 为什么还在。

#### 4.9 ConcurrentHashMap（必背） ★★★

学习内容：
- JDK8 数据结构（数组+链表+红黑树）与 JDK7（Segment 分段锁）的差异；
- CAS + synchronized 锁桶的并发控制思路；
- size() 的计数和模式（baseCount + CounterCell）；
- 扩容与协助扩容（helpTransfer）；
- 树化条件（链表 >=8 且数组 >=64）与退化条件（<=6）；
- 为什么读操作完全无锁（volatile 保证可见性）。
动手任务：
- 读一遍 ConcurrentHashMap 的 putVal/get/transfer/addCount 源码；
- 写一个多线程并发 put 的测试，验证不丢数据、不死循环；
- 用 jstack 观察扩容期间的线程状态。
验收标准：能画出 1.8 的桶结构并说明三种情况（桶空/桶有值/扩容中）各自的处理。

#### 4.10 死锁与其他（重点） ★★★

学习内容：
- 死锁四条件与 6 种避免策略；
- jstack -l / jcmd / ThreadMXBean.findDeadlockedThreads / Arthas thread -b 四种排查方式；
- wait/sleep/join/yield/interrupt 全面对比（表格记忆）；
- 线程六种状态与状态转换图；
- 常见并发工具类：CountDownLatch、CyclicBarrier、Semaphore、CompletableFuture 的适用场景差异。
动手任务：
- 手写死锁复现 + 4 种方式定位；
- 画一张线程状态转换图并背下来。
验收标准：拿到任意一段有死锁风险的代码，能指出风险点并给出两种以上改造方案。

## 阶段 5 · 排查与实战（1.5 天） ★★★

【为什么必须学】这是拉不开差距的地方。绝大多数候选人只会背 GC 原理，但线上真出问题时不会用工具 —— 面试官一问「你线上遇到过 OOM 怎么排查」，答不上来就直接出局。
【目标】把阶段 1-4 的所有工具串成一条完整的排障链路。

#### 5.1 JDK 诊断工具（必背） ★★★

学习内容：jps、jstat -gcutil 字段含义（S0 S1 E O M CCS YGC YGCT FGC FGCT GCT）、jmap -histo 与 -dump、jstack、jcmd、jinfo、jconsole 远程。
动手任务：把每个命令至少在真实进程上跑一次，特别是 jstat 的字段要能对上号。
验收标准：jstat -gcutil 一行输出能解释每个字段。

#### 5.2 CPU 飙高四步法（必背） ★★★

学习内容：top -Hp → printf %x → jstack grep nid → 分析栈顶。
动手任务：写一个死循环 + 一个正则回溯的 Demo，分别用四步法定位到具体代码行。
验收标准：不查资料完整口述四步，并说明 GC 线程占用 CPU 的情况怎么区分。

#### 5.3 线上参数模板（重点） ★★★

学习内容：容器环境 MaxRAMPercentage、-XX:MaxRAMPercentage vs 显式 -Xmx；堆 dump 自动生成配置；GC 日志轮转；AlwaysPreTouch、ActiveProcessorCount、NUMA。
动手任务：在 Docker 里跑一个 2G 限制的容器，看 JVM 实际识别的最大堆。
验收标准：能给出一个 8G 容器可直接使用的完整 JAVA_OPTS 模板并解释每一行。

#### 5.4 Arthas 线上急救（重点） ★★

学习内容：dashboard 看全局、thread -n 找热点线程、trace 看方法耗时、memory 采样对比、watch 观察参数、jad 反编译。
动手任务：本地起一个 Spring Boot，故意让一个接口变慢（Thread.sleep），用 trace 定位耗时点。
验收标准：会用 trace 输出一份方法调用链耗时报告。

## 阶段 6 · LeetCode 并发题专项（1.5 天） ★★

【定位】LeetCode 主站没有 JVM 题，但 Concurrency 标签下的题直接考 JUC + JMM，是国内面试的常见形式。
【核心策略】「代码只是载体，面试官要听你讲同步原理」。写完代码主动讲四句话：用了什么同步器 → 为什么选它 → 可见性/互斥怎么保证 → 边界条件是什么。

#### 6.1 并发标签必刷题（重点） ★★★

1114 按序打印（CountDownLatch / Condition）
1115 打印奇偶数（wait/notify 或 Semaphore，注意 while 而非 if）
1116 打印零与奇偶数（三个 CountDownLatch 协作）
1195 K 进制（位运算 + 互斥）
1117 建造区域（状态机 + 条件变量）
1188 有限阻塞队列（ReentrantLock + 两个 Condition，完整实现）
1226 银行队列（Semaphore 限流）
【每题都要做】手写两遍 + 口述一遍原理 + 想一个变体（比如改成 4 个线程）。

#### 6.2 与 JVM 强相关的算法题（重点） ★★★

146 LRU 缓存（HashMap + 双向链表；延伸讲软引用缓存与 Redis LRU 淘汰）
1 两数之和（HashMap 一遍遍历）
20 有效的括号（栈）
141 环形链表（快慢指针）
剑指 Offer 03 数组重复数字（原地置换 O(1) 空间）
剑指 Offer 22 找第 k 个值（快慢指针）
【答题模板】先说复杂度 → 给方案 → 说优化版本 → 主动讲边界情况。

#### 6.3 现场手写题清单（重点） ★★★

必须能默写的六道题：
1) LRU 缓存（含线程安全版）；
2) BoundedBlockingQueue 完整实现；
3) 生产者消费者（两种写法）；
4) DCL 单例（含 volatile 解释）；
5) CountDownLatch 门闩模式；
6) 简化版 ConcurrentHashMap（CAS + synchronized）。
【准备方法】每题至少默写两遍，能解释每一行。面试时手写不出是最致命的扣分。

## 阶段 7 · 项目实践包装（1 天 · 最容易被忽略） ★★★

【为什么这是最高性价比的环节】10 年经验的候选人，技术知识差距不会太大，胜负手在「你能不能把知识落到生产实践上」。面试官的必问题：「你线上遇到过最难的技术问题是什么？」——没有准备的候选人只能讲教科书案例。
【核心任务】把你的 10 年经历，包装成 3 个可讲的实战故事，每个故事都要用到 JVM 知识。

#### 7.1 素材盘点（重点） ★★★

回忆并整理以下素材（哪怕是很小的事）：
1) 一次线上 OOM 或 Full GC 频繁：怎么发现的（日志/监控/告警）、怎么定位的（四步法）、根因是什么、怎么修复的、效果如何（前后 RT、GC 次数对比）。
2) 一次性能优化：用什么工具（Arthas/profiler）、定位到什么、改了什么参数或代码、优化前后的量化数据。
3) 一次并发问题：超卖/重复提交/数据不一致/线程池打满，你的方案和最终结果。
4) 一次大促或资金批处理的性能保障：如何做容量评估、压测、预案。
【关键原则】必须有量化数字。说不清「提升了多少」的故事等于没讲。

#### 7.2 故事结构（重点） ★★★

用 STAR+ 量化 结构准备，每段控制在 3 分钟：
S/T（背景与目标）：什么系统、多大规模、什么指标出了问题、当时的目标是什么。
A（行动）：先怎么定位（讲工具和命令，体现硬功夫）→ 定位到什么根因 → 做了什么修改（讲参数或代码改动）。
R（结果）：量化收益（QPS、RT P99、GC 次数、内存占用、资源成本）。
+ 反思：还有什么没做好、下次会怎么改进 —— 这一步能显著加分。
【串联技巧】技术知识作为故事的支撑点提出来，而不是独立背诵。

#### 7.3 行业场景结合（重点） ★★★

金融/资金类系统的 JVM 场景（结合你 5 年司库经验，这是你的独特壁垒）：
- 资金批处理：百万级流水一次性加载会 OOM，怎么用分批 + 流式 + 游标分页处理；
- 对账任务：长事务导致老年代膨胀，怎么调整事务边界；
- 结算高峰：GC 停顿对 RT 的影响，怎么用 ZGC 或调低 MaxGCPauseMillis；
- 缓存设计：账户余额缓存的失效策略（软引用 vs 定时刷新 vs 双写），以及与 GC 的关系；
- 线程池隔离：不同业务线（实时查询、批量跑批）用独立线程池，防止互相拖垮。
【这些话能让面试官记住你】。

## 阶段 8 · 冲刺复盘（1 天） ★★

【总时长约 13-15 天】，可以按每天 3-4 小时有效投入估算。
【最后要产出的四份东西】
1) 一张收集器对比表（自己实测数据的版本，不是网上抄的）；
2) 一份四步法排查 SOP（贴在工位上）；
3) 三个实战故事（STAR+量化，录音自听一遍）；
4) 一份八股背诵清单（每题 90 秒能讲完）。

#### 8.1 背诵节奏（重点） ★★★

Day1-2：内存区域 + 类加载（相对简单，快速过）
Day3-5：GC 全部（重点，反复过，尤其是 G1 和参数）
Day6-8：并发全部（重点，JMM/锁升级/AQS/线程池/ThreadLocal）
Day9：工具与排查 + 线上参数模板
Day10：LeetCode 并发题手写
Day11：项目故事练习（对镜子讲三遍 + 录音）
Day12：全流程模拟（找人或对着 AI 完整过一遍 3 个模块）
【方法论】每题用「一句话结论 + 三个支撑点 + 一个项目实践」结构讲，控制在 90 秒。宁可少讲几个冷门知识点，也不要在高频点上讲得含糊。

#### 8.2 反问与收尾（加分） ★★

面试尾声的反问环节，能体现你在认真思考这份工作：
- 「贵司线上服务的堆配置和 GC 收集器是什么？有遇到过高 GC 或内存泄漏吗？」（关心生产实际）
- 「团队现在的后端技术栈和 JDK 版本是怎样的？有做 JVM 调优的沉淀吗？」
- 「如果是资金/交易相关的业务，系统在并发和数据一致性上是怎么设计的？」
【注意】不要问薪资、不要问加班（问加班可以但措辞要正面）、不要问「有没有培养机制」这种假问题。
