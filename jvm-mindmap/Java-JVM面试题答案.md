# Java JVM 面试题答案全集

> **分级说明**：★★★ 必考（约占实际面试提问的 70%，必须能完整背诵）；★★ 高频（需理解原理并能举例）；★ 延伸加分项。
>
> **关于 LeetCode**：LeetCode 主站没有 JVM 专项题目，但其 Concurrency（并发）标签下的 1114/ 1115 / 1116 / 1117 / 1188 / 1195 等题直接考JUC 与 JMM，是国内面试的常见形式，已单独整理在第六模块。
---

## 一、内存管理 ★★★

### 运行时数据区划分 ★★★

【线程私有】
1) 程序计数器 PC：记录当前线程执行到的字节码行号；线程切换后靠它回到原位置，执行 native 方法时值为 undefined。
2) 虚拟机栈：每次方法调用压入一个栈帧（局部变量表 + 操作数栈 + 动态链接 + 方法返回地址），-Xss 控制。
3) 本地方法栈：为 native 方法服务，HotSpot 中与虚拟机栈合二为一。

【线程共享】
4) 堆：几乎所有对象实例与数组在此分配，-Xmx 控制，是 GC 主战场。
5) 方法区（JDK7 永久代 / JDK8 元空间 Metaspace，本地内存，-XX:MetaspaceSize 控制）。

【背诵版】
私有三区：PC、虚拟机栈、本地方法栈；共享两区：堆、方法区。
面试口头补充：直接内存（DirectByteBuffer）不在 JVM 规范内，但 NIO/Netty 场景必须排查。

#### StackOverflowError vs OOM 区别 ★★★

StackOverflowError：线程栈深度超过限制，常见于无限递归，捕获 Throwable 后用 while(true){} 占位防止栈溢出进程挂掉。
OOM（Java heap space）：堆内存不足，常见于内存泄漏、大对象、死循环创建对象。
OOM（Metaspace）：类元信息过多，常见于动态生成类、重复加载、应用服务器泄漏。
OOM（unable to create new native thread）：线程数过多或栈内存不足（ulimit -u / -v 限制）。
OOM（Direct buffer memory）：NIO 直接内存未释放，需 -XX:MaxDirectMemorySize 与 netty 配置。
OOM（Requested array size exceeds VM limit）：数组声明超过 Integer.MAX_VALUE。
【关键】面试官问内存溢出时，先答出OOM 的分类，再答排查工具，这是加分点。

#### 堆内存划分与参数 ★★★

年轻代（Young）：Eden（默认 8 份）+ 2 个 Survivor（各 1 份，比例 -XX:SurvivorRatio，默认 8:1:1）。
老年代（Old）：默认 SurvivorRatio 之外的对象长期存活进入；大对象直接进老年代（-XX:PretenureSizeThreshold）。
JDK8 起不再有永久代，元空间在本地内存，默认无限（-XX:MaxMetaspaceSize）。
【常见追问】为什么 Survivor 要两个？对象在两个 Survivor 间来回复制是为了避免碎片化，只有存活对象被复制，无碎片。

### 对象创建过程 ★★★

五步（背下来）：
1) 检查：类是否已加载、解析到 Class 对象，没有则先走类加载流程。
2) 实例化：在堆上分配内存，分配方式取决于堆是否规整——指针碰撞（连续空间，如 Serial/ParNew）或空闲列表（离散空间，如 CMS/G1，多线程用 CAS 保证安全）。
3) 内存清零：把分配的内存初始化为 0，保证字段默认值为 null/0。
4) 设置对象头：写入类型指针、哈希码、GC 年龄、锁状态位。
5) 执行构造方法：执行 <init>，字段初始化 + 方法体。
【补充】多线程抢内存用 TLAB（Thread Local Allocation Buffer）——每个线程预先从 Eden 划一块私有区域，避免频繁 CAS；只有 TLAB 不够才走 CAS 或加同步。
【加分】并发场景下 CPU 缓存行竞争严重会出现伪共享（False Sharing），可用 -XX:-RestrictContended 或 @sun.misc.Contended 解决。

#### 对象内存布局与对象头 ★★★

对象头（Header）= Mark Word + 类型指针（Klass Oop）+ [数组长度]，数组才有长度字段。
Mark Word（64位默认 8 字节）复用了多个字段：
- 哈希码（31位）
- GC 分代年龄（4位）
- 锁标志位（3位，偏向/轻量级/重量级/无锁）
- 偏向线程ID / 偏向时间戳（JDK15 后偏向锁已废弃）
类型指针：压缩指针 4 字节（-XX:+UseCompressedClassPointers），非压缩 8 字节。
实例数据 + 对齐填充（8 字节倍数）。
【面试口径】问「一个空 Object 占多少内存」：没有压缩指针 16 字节，开启压缩指针 12 字节（8+4），再对齐到 16。

#### 对象的四种引用 ★★

强引用 StrongReference：只要还有强引用，永不回收，OOM 的元凶。
软引用 SoftReference：内存不足时回收，-XX:SoftRefLRUPolicyMSPerMB 控制回收时机，适合做内存敏感缓存（缓存淘汰的面试延伸点）。
弱引用 WeakReference：每次 GC 就回收，不决定对象生命周期，常与 ReferenceQueue 配合解决 ThreadLocal 泄漏。
虚引用 PhantomReference：JVM 回收后入队，不能通过它取对象，只为「对象被回收后要做的事」服务（回收堆外内存通知）。
【一句话】软：内存不够才收；弱：GC 立刻收；虚：只做回收后置动作。

#### 逃逸分析与栈上分配 ★

JIT 通过逃逸分析判断对象是否逃出方法；若不逃逸，则在栈上分配（标量替换），不进入堆，减少 GC 压力。
开启：-XX:+DoEscapeAnalysis（默认开）、-XX:+EliminateAllocations、-XX:+PrintEscapeAnalysis 可观测。
【限制】栈上有大小限制（-XX:MaxInlineSize/-XX:EscapeAnalysisObjectSize，默认标量替换上限 16KB 级别），大对象仍进堆。

## 二、类加载机制 ★★★

### 类的生命周期七阶段 ★★★

加载 → 验证 → 准备 → 解析 → 初始化 → 使用 → 卸载。
【准备阶段易错点】为 static 修饰的变量分配内存并设置「类型零值」，不是代码里的初始值：
static int i = 1 → 准备阶段 i = 0；
static final int j = 1 → 准备阶段 j = 1（编译期常量直接赋真值）；
【初始化阶段】执行 <clinit>()：static 变量赋值、static 块，按源码顺序执行；
同类中多个 <clinit>() 只会执行一次，且先父后子。
【重点提醒】final 修饰的基本类型/字符串是编译期常量，运行时常量池中不再有对象；final 修饰的对象引用是「引用不变」，对象内容可变。

#### 双亲委派模型 ★★★

委派流程：加载器收到类请求 → 先委派给父加载器（Bootstrp → Platform(JDK9，原Ext) → App）→ 父加载不到才自己加载。
【两个优势】1) 安全：核心类（如 java.lang.String）永远由 Bootstrap 加载，防止恶意替换；2) 唯一性：同一个类由不同加载器加载会产生两个不同的 Class 对象，导致类型转型异常（ClassCastException）。
【实现关键】ClassLoader.loadClass() 里是「先 parent.loadClass，再 findClass」，自定义加载器一般只重写 findClass 而不是 loadClass，重写 loadClass 就破坏了委派。

#### 破坏双亲委派的四个经典场景 ★★★

1) SPI（JDBC/日志）：JDBC 驱动由各厂商提供，AppClassLoader 找不到 → 改用线程上下文类加载器（Thread.currentThread().getContextClassLoader()）加载。
2) Tomcat / Spring Boot：每个容器有独立类加载器，实现「先自己加载（子优先），加载不到再委派父」，实现 Web 应用隔离。
3) OSGi：网状依赖，按需动态加载模块。
4) 热部署/动态代理：自定义 ClassLoader + 字节码生成（CGLIB/Javassist/ByteBuddy）。
【面试话术】「双亲委派保证了安全与一致性，但牺牲了灵活性；打破它的本质都是把『委派顺序』或『加载器选择权』从固定变成可配置。」

#### 类初始化时机 ★★★

【主动引用（会触发初始化）】
1) new 一个类实例化；
2) 读取/设置一个类的 static 变量（编译期常量除外）；
3) 调用类的 static 方法；
4) 反射（Class.forName / getDeclaredConstructor）；
5) 初始化子类时，先初始化父类；
6) 使用动态语言的 MethodHandle 解析。

【被动引用（不触发初始化）】
1) 编译期常量：final int A = 1 → 引用方常量池直接替换；
2) 通过子类引用父类静态变量 → 只初始化子类；
3) 定义数组：int[] a = new int[10] → 只初始化 [Ljava.lang.Integer;（数组类）不初始化 int；
4) 通过子类引用父类常量。
【口诀】除了四个（常量、数组、子类引父、父类常量），其余基本都触发。

#### 类卸载与类加载器泄漏 ★★

类卸载需同时满足三条件：
1) 该类的所有实例都已被回收（堆中无任何引用）；
2) 加载该类的 ClassLoader 已被回收；
3) 该类对应的 Class 对象没有被任何地方引用（含反射、缓存）。
【生产事故点】动态代理、ThreadLocal、JDBC DriverManager 注册、String.intern 常驻、应用服务器 reload —— 都可能导致 ClassLoader 无法回收、元空间持续增长，最终 Metaspace OOM。
【排查】jstat -gcmetaspace 观察元空间增长趋势，或 jcmd VM.metaspace 查看加载器明细。

#### 自定义类加载器场景 ★

需要类隔离（插件化/容器）、热部署、加密字节码解密、Web 应用共享 jar 优化（同一 jar 只加载一次）。
实现要点：继承 ClassLoader，重写 findClass(byte[] → defineClass)，避免重写 loadClass 以免破坏委派。

## 三、垃圾回收（GC） ★★★

#### 对象存活判定 ★★★

【引用计数法】给对象加计数器，减到0回收。致命缺陷：循环引用无法回收 → Java 实际不用。
【可达性分析算法（根可达性）】从 GC Roots 出发做图遍历，不可达即回收。
GC Roots 清单：
1) 虚拟机栈中引用的对象（方法局部变量）；
2) 方法区/元空间中类静态属性引用的对象；
3) 方法区/元空间中常量引用的对象；
4) 本地方法栈中 JNI 引用的对象；
5) JVM 内部引用（Class 对象等）。
【注意】栈里的引用会随栈帧出栈而销毁，所以方法执行完、栈帧弹出，局部变量引用的对象就有可能被回收 —— 这是「方法执行完对象就没了」的理论依据。

### 四种垃圾回收算法 ★★★

1) 标记清除（Mark-Sweep）：标记存活对象，清除其余。缺点：产生内存碎片，回收后需整理；效率随存活对象增多而下降。
2) 复制算法（Copying）：内存分两半，把存活对象复制到另一空间，清空原空间。优点：无碎片、分配快（只需移动指针）；缺点：空间浪费一半，存活率高时不合适。
3) 标记整理（Mark-Compact）：标记后把存活对象向一端移动，清除端外内存。优点：无碎片、利用率高；缺点：移动对象成本高。
4) 分代收集：新生代存活率低用复制，老年代存活率高用标记整理/清除。
【设计思想一句话】没有一种算法能解决所有场景，JVM 用分代假设把不同阶段的问题拆开。

#### 分代收集与对象晋升 ★★★

分代依据：绝大多数对象「朝生夕死」，少数对象长期存活。
晋升老年代的四种情况：
1) Survivor 空间装不下（动态年龄判断：同年龄对象总和超过 Survivor 一半，则这些对象及更老的直接进老年代）；
2) 对象年龄 >= MaxTenuringThreshold（默认 15，可调）；
3) 大对象（-XX:PretenureSizeThreshold 指定阈值，G1 默认超过 Region 一半直接进老年代）；
4) 由 ObjectAge 变化触发的提前晋升。
【调优提示】晋升过早 → 老年代被填满引发 Full GC；晋升过晚 → Survivor 反复复制增加 Minor GC 耗时。调参核心就是 MaxTenuringThreshold 与 SurvivorRatio。

#### GC 类型 ★★★

Minor GC / Young GC：只回收新生代（Eden + Survivor），触发条件是 Eden 满了，回收后存活对象复制到 Survivor；
Full GC / Major GC：回收整个堆 + 方法区，触发条件：老年代满了、元空间满了、显式 System.gc()、分配大对象 Eden 放不下且 Survivor 也放不下。
Mixed GC（G1 特有）：只回收老年代部分 Region，配合新生代一起回收，目标是一次回收掉一半以上的老年代空间。
【GC 代价指标】GC 并不是「Free」时间，也不是「Real」时间，而是 User + Sys 时间中应用暂停的那部分 —— 因为 GC 用多线程，wall clock 不等于 CPU 时间。

### 七大垃圾收集器 ★★★

【对比表，务必记住定位】
Serial：单线程 Stop-The-World，Young 复制 + Old 标记整理。适合小堆/单核/客户端，STW 最短。
Serial Old：Serial 的老年代版本，配合 CMS 使用（CMS 失败时降级给它）。
Parallel Scavenge / Parallel Old：吞吐量优先（Throughput），多线程回收。适合批处理、后台计算，-XX:+UseParallelGC 是 8u 前的默认。
CMS：Concurrent Mark Sweep，低延迟，初始/重新标记 STW，并发阶段与业务线程并行。适合 Web 响应优先，但有三个致命缺陷（见下）。
G1：Region 化 + 可预测停顿模型，JDK9 起默认，兼顾吞吐与延迟。
ZGC：超低延迟（<1ms 停顿），JDK11 实验性、JDK15 正式，着色指针 + 读屏障，堆越大优势越明显。
Shenandoah：与 ZGC 同量级，基于 Brooks 指针做并发压缩，OpenJDK 中由 Azul 贡献。
Epsilon：不做任何回收，纯粹分配到 OOM，用于性能极限测试。
【选型结论】小应用/延迟极敏感 → ZGC 或 G1（小堆）；批处理 → Parallel；JDK8 遗留系统 → Parallel 或 CMS（已废弃）。

#### CMS 收集器 ★★★

核心问题（面试高频）：
1) 产生【浮动垃圾】：并发标记期间业务线程仍在分配新对象，这些新对象被漏标记，只能等下次 GC 回收。
2) 产生【内存碎片】：标记清除算法导致，CMS 采用「标记清除」不做整理；补救是开启 -XX:+UseCMSCompactAtFullCollection（Full GC 时压缩）或 -XX:+UseCMSFullMarkSweepGC=false 改用 Parallel GC。
3) 【Concurrent Mode Failure】：CMS 并发失败时退化为 Serial Old 全线程停顿 STW，是真正的灾难现场。
【其他】CMS 只能处理老年代，不能收集新生代，需配合 ParNew 使用（JDK8 后推荐 Parallel 代替）。JDK14 起 CMS 被废弃，JDK15 正式移除，参数失效。
【调优】-XX:+UseConcMarkSweepGC、-XX:InitiatingOccupancyFraction（默认 92%）、-XX:ParallelGCThreads。

#### G1 收集器 ★★★

【五个必答点】
1) Region 化：把堆划分为 2048 个左右等大 Region（1MB~32MB），Eden/S0/S1/Old 都是 Region 的逻辑组合，Humongous（超过 Region 一半的大对象）单独占用连续 Region。
2) 停顿时间模型：-XX:MaxGCPauseMillis=N，G1 通过「回收收益」（回收空间 / 停顿成本）自动挑选 Region，优先回收「高回收收益」的年轻代。
3) 记忆集 Remembered Set：用「写屏障 + 脏卡片（Card Table）」记录跨 Region 引用，避免为维护引用关系而扫描整个老年代。
4) SATB（原始快照）：并发标记开始时做一次全堆快照，用「写前屏障」记录被覆盖的引用，Remark 阶段处理；相比增量更新（Incremental Update，Dijkstra 式）SATB 产生更少垃圾，但会产生更多浮动垃圾。
5) 并发标记四阶段（Remark 阶段重新标记 STW）：初始标记 → 并发标记 → 重新标记（STW，处理 SATB 记录）→ 清理（STW，回收完全空闲 Region）。
【优缺点】优点：可预测停顿、内存利用率高（无碎片）、支持大堆；缺点：写屏障开销、Remembered Set 内存占用高、并发标记阶段 CPU 消耗大。
【关键参数】-XX:InitiatingHeapOccupancyPercent=45（默认 45% 启动并发标记）、-XX:G1HeapRegionSize、-XX:G1ReservePercent、-XX:ConcGCThreads。

#### ZGC 与 Shenandoah ★

ZGC 核心突破：用【着色指针 Colored Pointer】把「标记位/Mark Bit」塞进指针空闲位，辅以【读屏障 Load Barrier】做染色，从而实现「并发标记 + 并发转移」，几乎无 STW。
代价：读屏障拖慢约 10%~20%，且 64 位指针被占用 3 位导致堆地址空间受限（JDK17+ 弱化该限制）。
Shenandoah 用 Brooks 指针（前向指针）做并发压缩，同样不依赖读屏障，比 ZGC 理论更优但实现更复杂。
【选型结论】延迟敏感型交易/广告系统（大堆、要求 P99 低）选 ZGC；一般业务仍首选 G1。

### GC 参数与日志分析 ★★★

【必备参数】
-Xms / -Xmx：初始堆 / 最大堆（生产建议设为相等，避免运行期反复扩缩）；
-Xmn：新生代大小；
-XX:MaxTenuringThreshold：晋升年龄，默认 15；
-XX:SurvivorRatio：Eden:Survivor，默认 8:1:1；
-XX:+UseG1GC / -XX:+UseParallelGC；
-XX:MaxGCPauseMillis=200（G1 停顿目标）；
-XX:InitiatingHeapOccupancyPercent=45（G1 启动并发标记的堆占用阈值）；
-XX:+HeapDumpOnOutOfMemoryError（OOM 自动 dump，必配）；
-Xlog:gc*:file=gc.log:time,uptime:filecount=10,filesize=20M（JDK9+）；JDK8 用 -XX:+PrintGCDetails -XX:+PrintGCTimeStamps -Xloggc:./gc.log。

【GC 日志怎么读】
Minor GC 行解读：GC 前后 Eden/S0/S1/Old 占用、总耗时；
Full GC 行解读：触发原因（Allocation Failure / Metadata GC Threshold / Explicit GC）；
关注三个数：GC 频率（次/分钟）、单次 STW 耗时、GC 后老年代基线是否持续上涨（上涨 = 内存泄漏）。
【工具】GCViewer、GCEasy（在线解析 gc.log 出图）、GC Log Viewer、MAT（JDK Mission Control）。

#### 内存泄漏排查方法论 ★★★

标准四步法（背下来，面试直接用）：
1) 确定 OOM 类型：看异常信息，Java heap space / Metaspace / native thread / Direct buffer —— 不同类型排查方向完全不同。
2) 判断是否泄漏：jstat -gcutil <pid> 1000 连续观察，关注 O（老年代）使用率是否单调上升到 90%+，Full GC 后是否降不下去（降下去 = 正常波动，不降 = 泄漏）。
3) dump 分析：jmap -dump:live,format=b,file=heap.hprof <pid>，用 MAT 打开 → Leak Suspects → Dominator Tree（按 retained Heap 排序）→ Path to GC Roots → 排除虚引用的 GC Roots。
4) 代码定位并修复：常见泄漏源 —— 未关闭的流/连接（try-with-resources）、无界集合（缓存未设上限）、ThreadLocal 未 remove、监听器未注销、静态 Map 缓存用户数据、线程池与队列堆积。
【加分表述】「dump 只能看到某个时刻的快照，如果线上内存还在涨，我会用 Arthas 的 memory 插件做多次采样对比，看哪个对象的实例数持续增长。」

## 四、并发与内存模型（JVM 高频） ★★★

#### JMM 三大特性 ★★★

原子性（Atomicity）：操作不可分割。JMM 保证基本类型读写原子，但 i++ 不是原子（读→加→写三步），需要 AtomicInteger 或加锁。
可见性（Visibility）：一个线程修改了变量，其他线程能立即看到。问题根源：CPU 多级缓存 + 编译器重排。解决：volatile、synchronized、锁。
有序性（Ordering）：程序执行顺序在多线程下与代码书写顺序可能不同。解决：volatile（禁止重排）、synchronized。
【关键概念】happens-before 是 JMM 的核心规则集；只要 A happens-before B，则 A 的结果对 B 可见且 A 在 B 之前执行。
【重排序三类】编译器重排、CPU 指令重排（as-if-serial 只保证单线程语义）、JIT 优化重排。

#### happens-before 八条规则 ★★★

1) 程序顺序规则：同一线程中，前面的操作 happens-before 后面的操作（仅编译器重排语义下）。
2) 管程锁定规则：对 synchronized 锁的 unlock happens-before 后续对同一锁的 lock。
3) volatile 规则：对 volatile 变量的写 happens-before 后续对它的读。
4) 线程启动规则：Thread.start() 之前的所有操作 happens-before 该线程 run() 中的所有操作。
5) 线程终止规则：线程中的所有操作 happens-before 其他线程从 Thread.join() 成功返回。
6) 中断规则：对线程 interrupt() 的调用 happens-before 被中断线程检测到中断事件。
7) 对象终结规则：构造器执行完成 happens-before finalize() 开始。
8) 传递性规则：A happens-before B，B happens-before C，则 A happens-before C（最常考、最易忘）。
【应用例子】ConcurrentHashMap 的 size()、线程池提交任务前的初始化，都是靠 happens-before 保证可见性。

#### volatile ★★★

【两大作用】1) 保证可见性：写操作直接落到主内存（加了 StoreStore 屏障），读操作从主内存读取（加了 LoadLoad 屏障）；2) 保证有序性：禁止指令重排（写后加 StoreStore+StoreLoad 屏障，读前后加 LoadLoad+LoadStore 屏障）。
【不保证原子性】count++ 用 volatile 无效，因为「读-加-写」三步可被打断。要用 AtomicInteger（CAS 自旋）或 LongAdder（分段 CAS，高并发下优于 AtomicInteger）。
【四种内存屏障】StoreStore（写写，禁止写写重排）、StoreLoad（读写，最严格）、LoadLoad（读读）、LoadStore（读写）。
【典型用途】1) 状态标记 boolean running = true/false；2) 单例模式 DCL 中防止指令重排（必须加！否则另一个线程可能拿到未初始化完的对象）；3) JUC 里的各种容器（ConcurrentHashMap 的 value 可见性、AQS 的 state）。
【volatile 与synchronized 对比（面试爱问）】volatile 轻量、无锁、不阻塞线程，但只保证可见性和有序性；synchronized 重量、互斥、同时保证三者。
【volatile 引用类型陷阱】volatile 只能保证「引用」的可见性，引用指向的对象内部状态的变化（如数组元素、其他字段）不受 volatile 保护。

#### synchronized 原理与锁升级 ★★★

【字节码层面】同步代码块对应 monitorenter / monitorexit 指令，同步方法通过 ACC_SYNCHRONIZED 标志位。
【底层数据结构】每个 Java 对象都有一个对象头 Mark Word，其中有个指针指向 monitor（ObjectMonitor，JVM 原生实现，C++）。
【竞争升级过程（必背四级）】
无锁：Mark Word 记录哈希码，锁标志位 01；
偏向锁（Biased Locking）：JDK6 引入，把线程 ID 写进 Mark Word，同一线程再进入无需 CAS。JDK15 起默认关闭并废弃（JEP 374）—— 但面试仍会问原理，要答得出「偏向线程 ID + 撤销偏向需要 STW」。
轻量级锁（Lightweight Locking）：无竞争或轻微竞争，自旋 + CAS，用「复制栈顶锁状态到 Lock Record（Displaced Mark Word）」实现；释放时若恢复了原状态就说明无真实竞争。
重量级锁（Heavyweight Locking）：依赖操作系统 Mutex/CAS + waitSet 阻塞唤醒，产生上下文切换开销。
【不可逆】只能升级不能降级（JDK6 前可以降级，已优化掉）。
【锁消除 / 锁粗化（编译器优化）】
锁消除：逃逸分析发现锁对象不会被其他线程访问 → 直接去掉锁。
锁粗化：一个循环内反复加解锁同一把锁 → 扩大锁范围，减少 CAS 次数。
【优缺点】优点：自动释放、不会死锁（JVM 会检测并抛异常）、语义清晰；缺点：不能超时中断、不可重入数受限（实际可重入）、无公平性。
【面试加分】「我们项目里 synchronized 用在资金流水表的更新上，因为除了互斥，还需要『同一事务的写操作顺序一致』，ReentrantLock 的可中断和超时我们用不到。」

#### AQS 原理 ★★★

AQS（AbstractQueuedSynchronizer）：构建锁和同步器的框架，JUC 的基石（ReentrantLock、Semaphore、CountDownLatch、ReentrantReadWriteLock 都基于它）。
【三个核心要素】
1) volatile int state：表示同步状态（0 无锁、1 独占、>1 重入次数 / 许可数 / 计数），CAS 修改。
2) CLH 双向队列（变体）：Node 链表，prev/next 指针，每个节点一个 Thread 字段。
3) 独占模式（ReentrantLock）/ 共享模式（CountDownLatch、Semaphore）。
【核心方法】acquire() → tryAcquire() → addWaiter() 入队 → acquireQueued() 前驱节点是 head 时尝试自旋获取，否则 acquire()。
【公平与非公平】非公平（默认）：刚释放锁的线程不一定能抢到，新线程可能插队 CAS 成功 —— 吞吐量更高。公平：hasQueuedPredecessors() 判断队列中是否有前驱，有则等待 —— 减少饥饿但吞吐量下降。
【设计模式回答】「AQS 把「获取/释放同步状态」的具体逻辑留给子类（模板方法模式），把「排队、阻塞、唤醒」这套复杂流程封装在父类中。」

#### CAS 与 ABA 问题 ★★★

CAS（Compare-And-Swap）：通过比较内存中的值与期望值，一致则原子更新，否则重试。Java 实现靠 Unsafe 类的 compareAndSwapInt / compareAndSwapObject（底层 CPU 的 cmpxchg 指令）。
【三大问题】
1) ABA：A 从 0→1→0，线程看到值还是 0 但实际已被修改过。
2) 自旋开销：高竞争下大量自旋浪费 CPU。
3) 只能保证单变量原子：多个变量同时更新需要加锁或封装成不可变对象。
【ABA 解法】AtomicStampedReference（带版本戳）、AtomicMarkableReference，或让变量每次修改都变为新值（如 Long 时间戳 + 序号）。
【JUC 中的 CAS 应用】AtomicInteger、LongAdder、AQS 的 state、ConcurrentHashMap 的桶头节点竞争。
【加分回答】「LongAdder 用 Cell 数组分段累加 + base + Striped64 + LongAdder.hlep() 探测热点 Cell，竞争分散后在 sum() 时合并 —— 高并发写场景下远优于 AtomicLong 的自旋。」

#### 线程池 ★★★

【7 大参数】corePoolSize、maximumPoolSize、keepAliveTime、unit、workQueue（6 种 BlockingQueue）、threadFactory、handler（4 种拒绝策略）。
【6 种阻塞队列】ArrayBlockingQueue（有界，数组，单锁）、LinkedBlockingQueue（默认无界！双锁，吞吐量高）、SynchronousQueue（不存储，直接交接，必须配最大线程数）、PriorityBlockingQueue（优先级，无界）、DelayQueue（延迟队列）、LinkedTransferQueue。
【执行流程，关键】
1) 当前线程数 < corePoolSize → 创建核心线程；
2) 达到 core → 尝试入队；
3) 入队失败（队列满）→ 创建非核心线程直到 maximumPoolSize；
4) 线程数达到 maximum → 执行拒绝策略。
【为什么不用 Executors 创建线程池？（阿里规约重点）】
newFixedThreadPool / newSingleThreadExecutor → 使用无界 LinkedBlockingQueue，任务堆积导致 OOM；
newCachedThreadPool / newScheduledThreadPool → maximumPoolSize 是 Integer.MAX_VALUE，多线程创建导致 OOM 且线程可能耗尽系统资源。
【4 种拒绝策略】AbortPolicy（抛 RejectedExecutionException，默认）、CallerRunsPolicy（调用线程自己跑，反压，最常用）、DiscardPolicy（静默丢弃）、DiscardOldestPolicy（丢队头再重试）。
【执行与关闭】execute 无返回值无异常感知（异常会杀死工作线程）；submit 返回 Future，异常被封装进 Future，必须 get() 才能感知；execute 内部 try-catch 兜底最稳。关闭用 shutdown()（有序）而非 shutdownNow()（中断正在执行的任务）。
【线程数设置公式】CPU 密集型 = N+1；IO 密集型 = N × (1 + 平均等待时间/平均计算时间)。本质是让 CPU 和 IO 尽量不同时空转。
【阿里规约要点】线程池必须命名（自定义 ThreadFactory + 阿里泰山命名规则：groupName-businessName-threadId），必须显式指定容量，拒绝策略不能默默丢弃，必须有监控埋点（活跃线程数、队列堆积、拒绝次数、任务耗时）。

#### ThreadLocal ★★★

结构：每个 Thread 对象内部有一个 ThreadLocalMap（Entry 数组），Entry 继承 WeakReference，key 是 ThreadLocal 的弱引用，value 是业务值。
【内存泄漏原理（高频）】ThreadLocal 实例被外部回收后，Entry 的 key 变成 null，但 value 仍被 Entry 强引用持有 → value 无法回收，长生命周期线程（线程池）会不断累积 → 最终 OOM。
【解决方案】
1) 用完立刻 remove()：try { ... } finally { threadLocal.remove(); } —— 唯一正确做法；
2) 避免在线程池中存放大对象，线程复用会放大泄漏；
3) JDK9+ 可用 Cleanable 或设置 TTL；
4) 用静态 ThreadLocal（阿里规约：必须 static final，避免每个实例一份 new 出来的 ThreadLocal 造成 key 泄漏）。
【典型用途】用户上下文透传（登录信息）、事务连接上下文、MDC 日志 traceId、SimpleDateFormat 防线程不安全。
【Spring 中的坑】Spring 用 InheritableThreadLocal 做子线程传递，线程池场景下子线程拿到的是「创建线程时」的旧值 —— 这是生产事故高发点，必须用 TransmittableThreadLocal（TTL）并配合 TtlExecutors。

### 并发容器与工具 ★★★

#### ConcurrentHashMap（1.8） ★★★

数据结构：数组 + 链表 + 红黑树。
【并发控制核心思路：CAS + synchronized 精准锁桶】
1) 读操作完全无锁（get），基于 volatile 可见性；
2) 写：桶为空 → CAS 直接插入；桶非空 → synchronized 锁住桶头节点（不是分段锁，锁粒度小到「单桶」）；
3) putVal 中用 CAS 更新计数 baseCount。
【size() 如何实现】不能遍历（O(n)），用 baseCount + CounterCell[] 分散计数：baseCount 冲突超过阈值时转存到 cell 数组，各线程写自己的 cell，size 时求和；JDK8 用计数和模式（LongAdder 式）。
【扩容：协助扩容】transfer 时其他线程（helpTransfer）也参与搬运，提高扩容效率（ForkJoinPool.commonPool）。
【树化条件】链表长度 >= 8 且数组长度 >= 64 时转红黑树；退化回链表的条件是扩容或删除后长度 <= 6（留 buffer 防止抖动）。
【对比 JDK7】分段锁 Segment（16 段），锁粒度粗、并发度上限 16；1.8 取消 Segment，锁粒度更细。

#### 其他并发容器速记 ★★

CopyOnWriteArrayList：读完全不加锁（拿快照数组），写加锁 + 数组复制。适合「读多写少 + 允许读到旧数据」，如监听器列表、配置白名单。
BlockingQueue 家族：ArrayBlockingQueue（数组、有界、单锁）、LinkedBlockingQueue（链表、默认 Integer.MAX_VALUE、双锁 put/take 分离吞吐高）、SynchronousQueue（零容量直接交接）、PriorityBlockingQueue（堆、无界）、DelayQueue（定时任务，如订单超时关闭）、LinkedTransferQueue（TransferQueue，transfer() 会等消费者取走）。
【队列选型口诀】要限流用有界；要交接用 Synchronous；要延迟用 DelayQueue。

#### 死锁排查与避免 ★★★

【四个必要条件】互斥、占有并等待、不可剥夺、循环等待。破坏任意一个即可避免。
【排查】1) jstack -l <pid>，线程状态 BLOCKED 且含 "Found one Java-level deadlock"；2) jcmd <pid> Thread.print；3) 编程式：ThreadMXBean.findDeadlockedThreads() 返回死锁线程数组；4) Arthas：thread -b 查看阻塞线程。
【工程实践】1) 加锁顺序全局排序（按对象 hash 或固定 ID）；2) 用 tryLock() 超时释放；3) 减少锁嵌套，尽量单锁；4) 用读写分离；5) 数据库层面加超时（innodb_lock_wait_timeout）。
【易死锁写法示例】两个线程以相反顺序获取 A、B 两把锁 —— 面试常现场手写复现。

#### 其他高频并发题 ★★

1) 线程状态六种：NEW、RUNNABLE、BLOCKED、WAITING、TIMED_WAITING、TERMINATED。
2) sleep / wait / join / yield 区别：sleep 是 Thread 静态方法不释放锁；wait 是 Object 方法必须先持有锁、会释放锁、虚假唤醒用 while 循环判断；join 是等待目标线程执行完；yield 提示调度器让出 CPU 但可能无效。
3) 线程池的 execute 与 submit 区别、Future.get 与异常传播。
4) volatile 与 CAS 的 ABA。
5) AtomicInteger 与 LongAdder。
6) 内存屏障与 happens-before 的对应关系。
7) 双重检查锁单例（必须有 volatile）。
8) 乐观锁（版本号 Version / CAS）与悲观锁（synchronized / for update）的选型。

## 五、排查工具与实战 ★★★

#### JDK 诊断工具箱 ★★★

jps -l：查看 JVM 进程列表（-l 显示主类全名）。
jstat -gcutil <pid> 1000：每秒打印 GC 统计（jstat -gc 查看原始数值）。
jmap：-dump:live,format=b,file=heap.hprof 导堆快照；-histo:live 看对象数量排行 Top20（最轻量的内存泄漏初判手段）。
jstack <pid>：打印线程栈，查死锁、看阻塞线程。
jcmd：JDK9+ 的统一入口，jcmd <pid> GC.run / Thread.print / VM.metaspace。
jinfo：查看 JVM 运行参数。
Arthas（线上急救三板斧）：
- dashboard：全局概览（线程、内存、GC 实时数据）
- thread -n 3：CPU 占用 Top3 线程（定位 CPU 飙高最快方式）
- trace 类名 方法名：方法调用耗时链路（定位慢接口）
- memory：多次采样对比实例数（定位内存泄漏）
- jad 反编译、watch 观察参数返回。
【加分项】主动汇报：「我们线上还接了 Arthas 和 async-profiler，profiler 做火焰图定位深递归和锁竞争。」

#### CPU 飙高 100% 四步定位法 ★★★

【标准流程，一定能背出来】
1) top -Hp <pid> 找出占用 CPU 最高的线程 ID（十进制，比如 12345）；
2) printf "%x\n" 12345 转成十六进制，得到线程的 nid（Hex），如 3039；
3) jstack <pid> | grep -A 30 "nid=0x3039" 找到该线程栈；
4) 分析栈顶：是死循环（while(true) 无 sleep）、频繁 Full GC（看 GC 线程）、正则回溯、锁自旋、大量序列化、频繁创建大对象。
【加速版】有 Arthas 直接 thread -n 3；再看 thread <id> 反复几次对比栈。
【进阶】async-profiler -e cpu 采 30s 出火焰图，看火焰图的宽度（耗时占比）而不是高度（调用栈深度）。
【面试话术】「我们的做法是先区分是 GC 线程占用还是业务线程占用：如果是 GC 线程，说明是内存或 GC 参数问题；如果业务线程，就顺着栈看具体代码。」

#### 线上生产参数模板 ★★★

【8GB 容器典型配置（背下来当模板）】
JAVA_OPTS="-server -Xms4g -Xmx4g -Xmn1g \
-XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
-XX:InitiatingHeapOccupancyPercent=45 \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/data/dump \
-XX:+PrintGCDetails -Xlog:gc*:file=/data/log/gc.log:time,uptime:filecount=10,filesize=20M \
-Djava.security.egd=file:/dev/./urandom"
容器环境必须注意：JVM 默认只感知容器内存限制的 75%（MaxRAMPercentage），8G 容器堆最大约 6G；要精确控制必须用 -XX:MaxRAMPercentage 或显式 -Xmx。
【金融/交易系统补充】超低延迟场景：-XX:+UseZGC 或 G1 + MaxGCPauseMillis=50~-XX:+AlwaysPreTouch（预热内存，避免运行期 page fault）+ -XX:+UseNUMA（多路机器）+ -XX:ActiveProcessorCount（容器内识别真实核数）+ JIT预热脚本。
【绝不做】不要在容器里用 ExitOnOutOfMemoryError 后不加守护重启策略；不要设置 -Xmx 超过物理内存的 80%。

## 六、LeetCode 高频关联（重要说明） ★★

### 先讲清楚一个前提 ★★★

LeetCode 主站【没有 JVM 专项题目】。但有两类题目与 JVM/JUC 高度对应，是国内面试的常见形式：
【A类：LeetCode 并发标签 Concurrency 题】—— 直接考 JUC 与 JMM。
【B 类：算法高频题】—— 其中若干题的实现依赖 JVM 机制（如 LRU 与缓存淘汰、哈希表扩容）。
【答题策略】遇到 LeetCode 并发题，面试官真正想听的是：你能否说清「用了什么同步器、为什么用它、内存可见性怎么保证、边界条件是什么」。写完代码一定要主动讲这四句。

#### 1114 按序打印 ★★★

要求：线程 A 打印 1、B 打印 2、C 打印 3，顺序固定。
【解法一】CountDownLatch（最简洁）：latch1.await(); print(1); latch2.countDown();  —— 用「门闩」控制进入时机。
【解法二】ReentrantLock + 两个 Condition：conditionB.signal() 精准唤醒指定线程。
【考点】线程间协作、等待/唤醒、可见性。
【加分】「用 Condition 比 notifyAll 更精准，能避免无效唤醒；但代价是要维护多个条件队列，复杂度更高。」

#### 1115 打印奇偶数 ★★★

要求：偶数线程打印 1,3,5...，奇数线程打印 2,4,6...，用 wait/notify 或 Semaphore。
【解法一】synchronized + wait/notify（必须用 while 判断条件，防止虚假唤醒）：
while (!flag) wait(); print(i); flag = !flag; notifyAll();
【解法二】两个 Semaphore（even/odd），acquire/release 交接。
【考点】wait/notify 必须写在 while 里而非 if 里 —— 这是经典送分/送命题。
【易错】notifyAll() 会唤醒所有线程再竞争，而 Semaphore 只唤醒一个，更高效。

#### 1116 打印零与奇偶数 ★★★

要求：0 → 1 → 0 → 2 → 0 → 3，0 线程和数字线程交替。
【标准解】三个 CountDownLatch：zero、even、odd，
zero.await(); print0; zero.countDown();
zero.await(); print1; even.countDown();
zero.await(); print0; zero.countDown();
...
【考点】多个信号量协作，体现「谁依赖谁」的拓扑设计。
【高频追问】用 Semaphore 怎么做？答：用三个 Semaphore，每次 release 后再 acquire 下一个。

#### 1117 建造区域 / 1188 有限阻塞队列 ★★

1117 建造区域：多线程并发执行建房，非房主线程只能等待房主完成。考点：条件变量与状态机、多线程协作建模。
1188 设计有限阻塞队列：implement BoundedBlockingQueue。考点：BlockingQueue 的完整语义（put/take/peek/poll/full/empty、超时版本）、ReentrantLock + 两个 Condition（notEmpty/notFull）的经典实现 —— 这是手写 AQS 思想的最好练习。
【额外推荐】1195 K 进制（位运算 + 互斥），1226 银行队列（Semaphore 限流）。

#### 与 JVM 机制强相关的算法高频题 ★★★

146 LRU 缓存：用 LinkedHashMap 的 accessOrder=true + removeEldestEntry 覆盖，或手写「HashMap + 双向链表」维护访问顺序。
【面试延伸，必须答】这题就是「软引用/弱引用缓存淘汰」和「Redis LRU 淘汰策略」的原型 —— 面试官问 LRU 往往是想引出 JVM 缓存设计。

1 两数之和：HashMap 一遍遍历，空间 O(n)。
20 有效的括号：用栈（或双端队列），体现栈的后进先出。
剑指 Offer 03 数组中重复的数字：原地置换法 O(n) O(1)，比 HashSet 更优（面试爱问「能不能不用额外空间」）。
141 环形链表：快慢指针，面试官常追问「怎么用 Floyd 算法找入口」。
剑指 22 找第 k 个值：快慢指针定位 k-1 次必达节点，O(1) 空间。
【统一答题话术】「我先说时间空间复杂度，再给方案，最后主动说一个优化版本和边界情况（空输入、单元素、重复元素）。」

#### 现场手写题准备清单 ★★

JVM 岗位常见的现场手写：
1) 手写 LRU 缓存（HashMap + 双向链表，注意容量、线程安全版本用 ConcurrentHashMap + ReadWriteLock）；
2) 手写 BoundedBlockingQueue（ReentrantLock + 两个 Condition）；
3) 手写生产者消费者（wait/notify 或 BlockingQueue）；
4) 手写双重检查锁单例（必须带 volatile，并解释为什么）；
5) 手写线程池的 execute 流程判断题；
6) 手写 CountDownLatch 门闩模式；
7) 手写一个 ConcurrentHashMap 的简化版（CAS + synchronized）。
【准备方法】每个至少手写两遍，默写一遍，能讲清每一行。

## 七、加分延伸（体现深度） ★★

#### JIT 与即时编译 ★★

解释器（JIT 的前提）：逐条字节码解释执行，启动慢、稳态慢。
热点代码（方法调用计数或循环回边计数超阈值，默认 -XX:CompileThreshold=10000）→ 触发 C1 编译（客户端编译，快、优化少）→ 若继续变热用 C2 编译（服务端编译，慢、优化激进，做循环展开、逃逸分析、内联）。
分层编译（-XX:+TieredCompilation，默认开启）把两者结合：先快速编译保证启动，再逐步优化。
【高频问】为什么代码要「写得短一点、把逻辑抽成小方法」？答：因为 HotSpot 有方法内联限制（-XX:MaxInlineSize=35 字节，-XX:FreqInlineSize=325 字节），大方法不会被内联，性能差。

#### 逃逸分析相关优化 ★

在 C2 阶段做：1) 栈上分配（消除堆分配）；2) 标量替换（对象拆成基本字段，用寄存器代替）；3) 锁消除；4) 同步省略（单线程访问的锁去掉）。
开关：-XX:+DoEscapeAnalysis、-XX:+EliminateAllocations、-XX:+PrintEliminateAllocations。
【局限】必须 C2 编译才生效；分析是局部的（跨方法内联后才准确）；不能改变语义（逃逸对象会堆分配）。

#### OOM 的五种类型速查 ★★

1) java.lang.OutOfMemoryError: Java heap space —— 堆不足，看对象堆积/泄漏。
2) OutOfMemoryError: Metaspace —— 类元信息多，看动态生成类（频繁 new URLClassLoader、CGLIB、重复类）。
3) OutOfMemoryError: unable to create new native thread —— 线程数过多，看 ulimit -u、是否有连接未关闭。
4) OutOfMemoryError: Direct buffer memory —— NIO 直接内存，需 -XX:MaxDirectMemorySize + -XX:+DisableExplicitGC（因为它不走堆不计入 GC）。
5) OutOfMemoryError: Requested array size exceeds VM limit —— 数组越界声明。
【加分】再说一个 StackOverflowError 属于栈溢出不是内存溢出。

#### 面试应答策略 ★★★

1) 每个知识点用「一句话结论 + 三个支撑点 + 一个项目实践」结构讲，控制在 90 秒内。
2) 不会的问题不要硬编。说「这块我没在生产用过，但我的理解是……，如果需要我可以说说我会怎么验证」——这比胡编得分高得多。
3) 一定要准备【项目实践】环节：说清你的服务 QPS、堆配置、GC 参数、遇到过什么 OOM、怎么排查定位、修复后效果。10 年经验如果说不出一个线上问题，说服力会大打折扣。
4) 金融行业场景可加分：资金流水的一致性（synchronized vs 分布式锁）、批量结账的大内存处理（分批 + 流式，避免一次读全量）、GC 停顿对 P99 的影响。
5) 反问环节可以问：「贵司线上服务的堆配置和 GC 收集器是什么？有遇到过高 GC 或 OOM 吗？」——显得你真关心生产。
