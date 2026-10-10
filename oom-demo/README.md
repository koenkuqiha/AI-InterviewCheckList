# Java OOM 五大场景复现 Demo

5 个可独立运行的 demo，每个复现一种 `OutOfMemoryError`，全部在 **JDK17** 上实际编译运行验证通过。

---

## 快速开始（IDEA）

### 方式一：直接导入运行配置（推荐）

`.idea/runConfigurations/` 下已放好 5 个配置文件。用 IDEA 打开本项目（`File > Open` 选 `oom-demo` 目录），
右上角运行配置下拉框里就能直接看到：

```
OOM-1 堆溢出(Java heap space)
OOM-2 元空间溢出(Metaspace)
OOM-3 线程数溢出(native thread)
OOM-4 直接内存溢出(Direct buffer)
OOM-5 数组越界(Array size exceeds VM limit)
```

> 如果列表为空或报模块找不到：右键项目名 `Mark Directory as` 无需操作，
> 直接在 `Edit Configurations` 里把 `Module` 改成你自己的模块名即可。

### 方式二：手动新建运行配置

`Run > Edit Configurations... > + > Application`，按下表填写：

| # | Main class | VM options | 备注 |
|---|---|---|---|
| 1 | `HeapOOMDemo` | `-Xmx64m` | 必须限制堆，否则要很久才 OOM |
| 2 | `Demo2_Metaspace` | `-XX:MaxMetaspaceSize=32m` | 不设则默认无上限，撑不爆 |
| 3 | `Demo3_NativeThread` | `-Xmx64m -Xss256k` | Windows 无 ulimit，靠小栈快速撞上限；**耗时最久，约 2-4 分钟** |
| 4 | `Demo4_DirectBufferMemory` | `-XX:MaxDirectMemorySize=32m` | 不设则默认≈-Xmx |
| 5 | `Demo5_ArraySizeLimit` | 留空 | 任何配置都能复现 |

**关键操作路径**：
- `Run > Edit Configurations...` → 左侧选 Application → `+`
- **VM options** 框在 `Modify options` 里若看不到，点右边的 `Add run options` 图标，
  勾选 `Add VM options` 才会出现该输入框（这是 IDEA 2023+ 的常见坑）
- `Working directory` 填 `$PROJECT_DIR$`

### 方式三：命令行直接跑（最快，无需 IDEA）

```bash
cd oom-demo
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"

# 1. 编译
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d out src/*.java

# 2. 各场景运行
"$JAVA_HOME/bin/java" -Xmx64m -cp out HeapOOMDemo
"$JAVA_HOME/bin/java" -XX:MaxMetaspaceSize=32m -cp out Demo2_Metaspace
"$JAVA_HOME/bin/java" -Xmx64m -Xss256k -cp out Demo3_NativeThread
"$JAVA_HOME/bin/java" -XX:MaxDirectMemorySize=32m -cp out Demo4_DirectBufferMemory
"$JAVA_HOME/bin/java" -cp out Demo5_ArraySizeLimit
```

---

## 五个场景速查

### 1. `OutOfMemoryError: Java heap space`
- **参数**：`-Xmx64m`
- **成因**：对象堆积或泄漏。无上限的 List 持续持有 16KB 对象 → GC 无法回收
- **排查**：`jmap -histo:live` 找实例数最多的类 → dump hprof → MAT Leak Suspects
- **易错点**：OOM 也可能是**设计问题**（百万级流水一次性加载）而非泄漏，加内存只是推迟崩溃

### 2. `OutOfMemoryError: Metaspace`
- **参数**：`-XX:MaxMetaspaceSize=32m`
- **成因**：类元信息过多。典型是频繁 `new URLClassLoader`（热部署）、CGLIB 代理、
  动态生成类。本demo 用手写 class 字节码 + 强引用持有，5340 个类撑爆 32m
- **排查**：`jcmd <pid> GC.class_stats`，看 `Unloaded` 是否增长；不增长 = ClassLoader 泄漏
- **踩坑记录**：必须**强引用持有**加载器与类，否则被 GC 卸载后元空间回收复用，永远撑不爆
- **彩蛋现象**：Metaspace 满时**字符串拼接也会失败**，因为 invokedynamic 需要生成隐藏类

### 3. `OutOfMemoryError: unable to create new native thread`
- **参数**：`-Xmx64m -Xss256k`
- **成因**：OS 拒绝再创建原生线程。本 demo 达到**92554 个线程**时触发
- **排查**：`ulimit -u`、`jstack <pid> | grep '^"' | wc -l`、按 Thread.State 统计状态分布
- **JDK17 实际措辞**：`unable to create native thread: possibly out of memory or process/resource limits reached`
  （JDK8 是 `unable to create new native thread`，含义相同，别死记字符串）
- **耗时提示**：需创建 9 万+线程，IDEA 里跑请耐心等 2-4 分钟

### 4. `OutOfMemoryError: Direct buffer memory`
- **参数**：`-XX:MaxDirectMemorySize=32m`
- **成因**：NIO/Netty 直接内存耗尽。本demo 32MB 精确撑爆，报错含
  `Cannot reserve 1048576 bytes of direct buffer memory (allocated: 33554432, limit: 33554432)`
- **关键性质**：不走堆、**不计入-Xmx**、靠 Cleaner 在真正 GC 后回收
- **常见误区**：以为反复 `System.gc()` 能救。若开了 `-XX:+DisableExplicitGC`，
  显式 GC 被禁，Cleaner 永远等不到 GC 机会——**关键不是调 System.gc()，而是修引用泄漏**
- **排查**：NMT (`-XX:NativeMemoryTracking=summary`) 做两次快照 diff；Netty 开 leakDetection

### 5. `OutOfMemoryError: Requested array size exceeds VM limit`
- **参数**：无
- **成因**：分配的**数组长度本身非法或超限**，不是内存真的不足。加内存没用，必须改代码
- **隐蔽来源**：int 溢出（`count * 2` 变负数）、分页 size 被传入超大值、文件长度计算错误
- **与 heap space 的本质区别**：heap space 是内存真不够；exceeds VM limit 是长度非法

---

## 关键调试经验（本轮实跑踩到的坑）

**① 堆 OOM 时，catch 块自己也会炸**
堆彻底耗尽后，连字符串拼接所需的临时对象都分配不出来，catch 块里的诊断代码根本执行不了。
生产上的标准做法：**预留一块应急 buffer，OOM 时先丢弃它**。
本demo 演示了 `emergencyBuffer = null` 的写法。

**② Metaspace 满时字符串拼接同样失败**
Java 9+ 的 `+` 编译成 invokedynamic（StringConcatFactory），需要在 Metaspace 生成隐藏类。
元空间耗尽时连 `"a" + b` 都会抛 OOM。解法是用**编译期常量**或 StringBuilder。

**③ 类不被强引用就撑不爆 Metaspace**
第一版 Demo2 反复 `new ClassLoader()` 但不持有，加载了 259 万个类仍未 OOM——
因为它们变成垃圾被卸载、元空间被回收复用了。必须强引用持有才复现。

**④ byte[] 拼进字符串等于没持有**
第一版 HeapOOMDemo 写的是 `list.add(key + new byte[65536])`，
byte[] 被拼成地址字符串后就成了垃圾，堆里只剩 String。改成 `list.add(byte[])` 才真正撑爆堆。

---

## 面试答题模板

被问「线上遇到 OOM 怎么排查」，按这个顺序答（**别一上来就调大内存**）：

1. **先分类**：看 OOM 消息判断是堆、元空间、直接内存还是线程问题
2. **堆 OOM** → `jmap -histo:live` 看实例数最多的类；导 hprof 用 MAT 找 Dominator Tree
3. **Metaspace** → `jcmd GC.class_stats` 看 Unloaded 是否增长，判断是不是 ClassLoader 泄漏
4. **Direct** → NMT 两次快照 diff 定位泄漏点
5. **native thread** → `ulimit -u` + `jstack` 按状态统计，找线程泄漏源
6. **定根因**：区分「泄漏」和「设计问题」。后者加内存无效，必须改代码（如分页/流式）
7. **给方案**：内存泄漏修引用；设计问题改架构；参数只是辅助，且要先设上限让问题早暴露

**加分点**：主动说「Metaspace 满时字符串拼接也会失败，因为 invokedynamic 要生成隐藏类」，
或「堆 OOM 时 catch 块里的诊断代码也可能执行不了，需要预留应急 buffer」——
这类实跑踩过的细节能明显区分「跑过代码」和「只看过文档」。